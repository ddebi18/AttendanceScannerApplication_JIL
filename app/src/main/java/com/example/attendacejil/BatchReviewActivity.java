package com.example.attendacejil;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class BatchReviewActivity extends AppCompatActivity {

    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private Button btnExportAll;
    private BatchReviewPagerAdapter adapter;

    private String jsonFilePath;
    private int selectedYear, selectedMonth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_batch_review);

        if (savedInstanceState != null && savedInstanceState.containsKey("json_file_path")) {
            // Restore from saved state (process death recovery)
            jsonFilePath = savedInstanceState.getString("json_file_path");
            selectedYear = savedInstanceState.getInt("year", java.util.Calendar.getInstance().get(java.util.Calendar.YEAR));
            selectedMonth = savedInstanceState.getInt("month", java.util.Calendar.getInstance().get(java.util.Calendar.MONTH));
        } else {
            jsonFilePath = getIntent().getStringExtra("json_file_path");
            java.util.Calendar now = java.util.Calendar.getInstance();
            selectedYear = getIntent().getIntExtra(ReviewActivity.EXTRA_YEAR, now.get(java.util.Calendar.YEAR));
            selectedMonth = getIntent().getIntExtra(ReviewActivity.EXTRA_MONTH, now.get(java.util.Calendar.MONTH));
        }
        
        if (jsonFilePath == null || jsonFilePath.isEmpty() || !new File(jsonFilePath).exists()) {
            Toast.makeText(this, "Missing scan data", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        bindViews();
        setupViewPager();
        setupButtons();
    }
    
    @Override
    protected void onSaveInstanceState(@androidx.annotation.NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("json_file_path", jsonFilePath);
        outState.putInt("year", selectedYear);
        outState.putInt("month", selectedMonth);
    }

    private void bindViews() {
        tabLayout = findViewById(R.id.tabLayout);
        viewPager = findViewById(R.id.viewPager);
        btnExportAll = findViewById(R.id.btnExportAll);
        findViewById(R.id.btnBack).setOnClickListener(v -> confirmExit());
    }

    private void setupViewPager() {
        adapter = new BatchReviewPagerAdapter(this, jsonFilePath);
        viewPager.setAdapter(adapter);

        // Keep all 10 tabs in memory so we don't lose state
        viewPager.setOffscreenPageLimit(10);

        String[] tabLabels = {
                "WK1 SVC1", "WK1 SVC2",
                "WK2 SVC1", "WK2 SVC2",
                "WK3 SVC1", "WK3 SVC2",
                "WK4 SVC1", "WK4 SVC2",
                "WK5 SVC1", "WK5 SVC2"
        };

        new TabLayoutMediator(tabLayout, viewPager,
                (tab, position) -> tab.setText(tabLabels[position])
        ).attach();
    }

    private void setupButtons() {
        btnExportAll.setText("Export All (Up to 10 CSVs)");
        btnExportAll.setOnClickListener(v -> checkAndExportAll());
    }

    private void confirmExit() {
        new MaterialAlertDialogBuilder(this, R.style.AttendanceDialogTheme)
                .setTitle("Exit Review?")
                .setMessage("This will discard all current batch data.")
                .setPositiveButton("Exit", (d, w) -> finish())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void checkAndExportAll() {
        // Check if all active tabs are ready for export (no flagged rows, no invalid rows)
        int blockingTab = -1;
        for (int i = 0; i < 10; i++) {
            ReviewFragment frag = adapter.getFragment(i);
            if (frag != null && !frag.canExport()) {
                blockingTab = i;
                break;
            }
        }

        if (blockingTab != -1) {
            int finalBlockingTab = blockingTab;
            new MaterialAlertDialogBuilder(this, R.style.AttendanceDialogTheme)
                    .setTitle("Export Blocked")
                    .setMessage("There are flagged or incomplete rows in Tab " + (blockingTab + 1) + ". Please fix them before exporting.")
                    .setPositiveButton("Go to Tab", (d, w) -> viewPager.setCurrentItem(finalBlockingTab, true))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        // Build payloads and export sequentially
        exportSequential(0, new ArrayList<>());
    }

    private void exportSequential(int index, List<File> exportedFiles) {
        if (index >= 10) {
            showExportSuccess(exportedFiles);
            return;
        }

        ReviewFragment frag = adapter.getFragment(index);
        if (frag == null) {
            exportSequential(index + 1, exportedFiles);
            return;
        }

        List<AttendanceRow> rowsToExport = frag.getRows();
        
        // Find which column this tab corresponds to
        int sunday = (index / 2) + 1;
        int service = (index % 2) + 1;
        int colIdx = index; // 0 to 9

        // Count how many people ACTUALLY attended this session
        long attendeesThisSession = rowsToExport.stream()
                .filter(r -> !r.markedForDeletion && r.attendance[colIdx])
                .count();

        if (attendeesThisSession == 0) {
            // Nothing to export for this tab (e.g. 5th Sunday didn't happen)
            exportSequential(index + 1, exportedFiles);
            return;
        }

        btnExportAll.setEnabled(false);
        btnExportAll.setText("Exporting " + (index + 1) + "/10…");

        try {
            JSONObject payload = new JSONObject();
            payload.put("sunday_num", sunday);
            payload.put("service_num", service);

            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.YEAR, selectedYear);
            cal.set(java.util.Calendar.MONTH, selectedMonth);
            payload.put("month", new SimpleDateFormat("MMMM", Locale.getDefault())
                    .format(cal.getTime()).toUpperCase(Locale.getDefault()));
            payload.put("year", selectedYear);

            JSONArray rowsArr = new JSONArray();
            for (AttendanceRow r : rowsToExport) {
                if (r.markedForDeletion) continue;
                JSONObject obj = new JSONObject();
                obj.put("last_name", r.lastName);
                obj.put("first_name", r.firstName);
                obj.put("network", r.network);
                JSONArray attArr = new JSONArray();
                for (boolean b : r.attendance) attArr.put(b);
                obj.put("attendance", attArr);
                rowsArr.put(obj);
            }
            payload.put("rows", rowsArr);

            RequestBody body = RequestBody.create(
                    MediaType.parse("application/json"),
                    payload.toString());

            RetrofitClient.getApiService()
                    .exportCsv(body)
                    .enqueue(new Callback<ResponseBody>() {
                        @Override
                        public void onResponse(@NonNull Call<ResponseBody> call,
                                               @NonNull Response<ResponseBody> resp) {
                            if (resp.isSuccessful() && resp.body() != null) {
                                File f = saveCsvLocal(resp.body(), sunday, service);
                                if (f != null) exportedFiles.add(f);
                            }
                            exportSequential(index + 1, exportedFiles);
                        }

                        @Override
                        public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable t) {
                            Toast.makeText(BatchReviewActivity.this, "Export failed on WK" + sunday + " SVC" + service, Toast.LENGTH_SHORT).show();
                            exportSequential(index + 1, exportedFiles);
                        }
                    });

        } catch (Exception e) {
            exportSequential(index + 1, exportedFiles);
        }
    }

    private File saveCsvLocal(ResponseBody body, int sundayNum, int serviceNum) {
        try {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.YEAR, selectedYear);
            cal.set(java.util.Calendar.MONTH, selectedMonth);
            String month = new SimpleDateFormat("MMM", Locale.getDefault())
                    .format(cal.getTime()).toUpperCase(Locale.getDefault());
            String[] ords = {"", "1ST", "2ND", "3RD", "4TH", "5TH"};
            String svcLbl = ords[serviceNum];

            int yr = selectedYear;
            Calendar firstOfMonth = Calendar.getInstance();
            firstOfMonth.set(yr, selectedMonth, 1);
            int dayOfWeek = firstOfMonth.get(Calendar.DAY_OF_WEEK);
            int firstSunOffset = (Calendar.SUNDAY - dayOfWeek + 7) % 7;
            int sundayDay = 1 + firstSunOffset + (sundayNum - 1) * 7;
            String dayStr = String.format(Locale.getDefault(), "%02d", sundayDay);

            String fname = String.format(Locale.getDefault(),
                    "%s SERVICE_%s_%s - Check-Ins Report.csv",
                    svcLbl, month, dayStr);

            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, fname);

            try (InputStream is = body.byteStream();
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            }

            return out;

        } catch (IOException e) {
            return null;
        }
    }

    private void showExportSuccess(List<File> exportedFiles) {
        btnExportAll.setEnabled(true);
        btnExportAll.setText("Export All (Up to 10 CSVs)");

        new MaterialAlertDialogBuilder(this, R.style.AttendanceDialogTheme)
                .setTitle("✅ Export Complete")
                .setMessage("Successfully exported " + exportedFiles.size() + " CSV files to your Downloads folder. (Empty sessions were skipped).")
                .setPositiveButton("Share All", (d, w) -> shareFiles(exportedFiles))
                .setNegativeButton("Done", null)
                .show();
    }

    private void shareFiles(List<File> files) {
        if (files.isEmpty()) return;
        try {
            ArrayList<Uri> uris = new ArrayList<>();
            for (File f : files) {
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", f);
                uris.add(uri);
            }

            Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE);
            share.setType("text/csv");
            share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share Attendance CSVs"));
        } catch (Exception e) {
            Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
