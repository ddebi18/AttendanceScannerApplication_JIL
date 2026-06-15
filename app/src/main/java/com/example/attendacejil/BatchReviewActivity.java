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
    private android.widget.ImageButton btnViewImage;
    private boolean[] disabledWeeks;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_batch_review);

        if (savedInstanceState != null && savedInstanceState.containsKey("json_file_path")) {
            // Restore from saved state (process death recovery)
            jsonFilePath = savedInstanceState.getString("json_file_path");
            selectedYear = savedInstanceState.getInt("year", java.util.Calendar.getInstance().get(java.util.Calendar.YEAR));
            selectedMonth = savedInstanceState.getInt("month", java.util.Calendar.getInstance().get(java.util.Calendar.MONTH));
            disabledWeeks = savedInstanceState.getBooleanArray("disabled_weeks");
        } else {
            jsonFilePath = getIntent().getStringExtra("json_file_path");
            java.util.Calendar now = java.util.Calendar.getInstance();
            selectedYear = getIntent().getIntExtra(ReviewActivity.EXTRA_YEAR, now.get(java.util.Calendar.YEAR));
            selectedMonth = getIntent().getIntExtra(ReviewActivity.EXTRA_MONTH, now.get(java.util.Calendar.MONTH));
            disabledWeeks = getIntent().getBooleanArrayExtra("disabled_weeks");
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
        outState.putBooleanArray("disabled_weeks", disabledWeeks);
    }

    private void bindViews() {
        tabLayout = findViewById(R.id.tabLayout);
        viewPager = findViewById(R.id.viewPager);
        btnExportAll = findViewById(R.id.btnExportAll);
        btnViewImage = findViewById(R.id.btnViewImage);
        findViewById(R.id.btnBack).setOnClickListener(v -> confirmExit());
    }

    private void setupViewPager() {
        adapter = new BatchReviewPagerAdapter(this, jsonFilePath, disabledWeeks);
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
                (tab, position) -> {
                    int colIdx = adapter.getColumnIndex(position);
                    tab.setText(tabLabels[colIdx]);
                }
        ).attach();
    }

    private void setupButtons() {
        btnExportAll.setText("Export All (Up to 10 CSVs)");
        btnExportAll.setOnClickListener(v -> checkAndExportAll());

        if (btnViewImage != null) {
            btnViewImage.setOnClickListener(v -> {
                ArrayList<String> uris = getIntent().getStringArrayListExtra(ImageViewerActivity.EXTRA_IMAGE_URIS);
                ArrayList<String> labels = getIntent().getStringArrayListExtra(ImageViewerActivity.EXTRA_IMAGE_LABELS);
                if (uris == null || uris.isEmpty()) {
                    Toast.makeText(this, "No original images found.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (uris.size() == 1) {
                    Intent intent = new Intent(BatchReviewActivity.this, ImageViewerActivity.class);
                    intent.putStringArrayListExtra(ImageViewerActivity.EXTRA_IMAGE_URIS, uris);
                    startActivity(intent);
                } else {
                    android.widget.BaseAdapter listAdapter = new android.widget.BaseAdapter() {
                        @Override public int getCount() { return uris.size(); }
                        @Override public Object getItem(int position) { return uris.get(position); }
                        @Override public long getItemId(int position) { return position; }
                        @Override public android.view.View getView(int position, android.view.View convertView, android.view.ViewGroup parent) {
                            if (convertView == null) {
                                convertView = getLayoutInflater().inflate(R.layout.item_image_picker, parent, false);
                            }
                            android.widget.TextView tvTitle = convertView.findViewById(R.id.tvImageTitle);
                            android.widget.TextView tvSub = convertView.findViewById(R.id.tvImageSubtitle);
                            tvTitle.setText("Image " + (position + 1));
                            String label = (labels != null && position < labels.size()) ? labels.get(position) : "Page " + (position + 1);
                            tvSub.setText("Names: " + label);
                            return convertView;
                        }
                    };

                    new MaterialAlertDialogBuilder(this, R.style.AttendanceDialogTheme)
                            .setTitle("Select Image to View")
                            .setAdapter(listAdapter, (d, which) -> {
                                Intent intent = new Intent(BatchReviewActivity.this, ImageViewerActivity.class);
                                intent.putStringArrayListExtra(ImageViewerActivity.EXTRA_IMAGE_URIS, uris);
                                intent.putExtra(ImageViewerActivity.EXTRA_INITIAL_INDEX, which);
                                startActivity(intent);
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                }
            });
        }
    }

    /** Called by a ReviewFragment's adapter when the user edits a row — propagates to all other tabs. */
    public void onRowEditedGlobally(String id, String newLastName, String newFirstName, String newNetwork) {
        for (int i = 0; i < 10; i++) {
            ReviewFragment frag = adapter.getFragment(i);
            if (frag != null) {
                frag.updateRowGlobally(id, newLastName, newFirstName, newNetwork);
            }
        }
    }

    public void onAttendanceToggledGlobally(String id, int colIdx, boolean isPresent) {
        for (int i = 0; i < 10; i++) {
            ReviewFragment frag = adapter.getFragment(i);
            if (frag != null) {
                frag.updateAttendanceGlobally(id, colIdx, isPresent);
            }
        }
    }

    public void onRowAddedGlobally(String id, String lastName, String firstName, String network) {
        for (int i = 0; i < 10; i++) {
            ReviewFragment frag = adapter.getFragment(i);
            if (frag != null) {
                // Fragment will skip if it already added it
                frag.addRowGlobally(id, lastName, firstName, network);
            }
        }
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
        for (int i = 0; i < adapter.getItemCount(); i++) {
            int colIdx = adapter.getColumnIndex(i);
            ReviewFragment frag = adapter.getFragment(colIdx);
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

        // Sync to database first, then export sequentially
        ReviewFragment firstFrag = null;
        for (int i = 0; i < adapter.getItemCount(); i++) {
            firstFrag = adapter.getFragment(adapter.getColumnIndex(i));
            if (firstFrag != null) break;
        }

        if (firstFrag != null) {
            btnExportAll.setEnabled(false);
            btnExportAll.setText("Syncing Database...");
            DbSyncHelper.syncRowsToDb(this, selectedYear, selectedMonth, firstFrag.getRows(), new DbSyncHelper.SyncCallback() {
                @Override
                public void onSuccess() {
                    exportSequential(0, new ArrayList<>());
                }

                @Override
                public void onFailure(String error) {
                    btnExportAll.setEnabled(true);
                    btnExportAll.setText("Export All (Up to 10 CSVs)");
                    Toast.makeText(BatchReviewActivity.this, "DB Sync Failed: " + error, Toast.LENGTH_LONG).show();
                }
            });
        } else {
            exportSequential(0, new ArrayList<>());
        }
    }

    private void exportSequential(int position, List<File> exportedFiles) {
        if (position >= adapter.getItemCount()) {
            showExportSuccess(exportedFiles);
            return;
        }

        int colIdx = adapter.getColumnIndex(position);
        ReviewFragment frag = adapter.getFragment(colIdx);
        if (frag == null) {
            exportSequential(position + 1, exportedFiles);
            return;
        }

        List<AttendanceRow> rowsToExport = frag.getRows();
        
        // Find which column this tab corresponds to
        int sunday = (colIdx / 2) + 1;
        int service = (colIdx % 2) + 1;

        // Count how many people ACTUALLY attended this session
        long attendeesThisSession = rowsToExport.stream()
                .filter(r -> !r.markedForDeletion && r.attendance[colIdx])
                .count();

        if (attendeesThisSession == 0) {
            // Nothing to export for this tab (e.g. 5th Sunday didn't happen)
            exportSequential(position + 1, exportedFiles);
            return;
        }

        btnExportAll.setEnabled(false);
        btnExportAll.setText("Exporting " + (position + 1) + "/" + adapter.getItemCount() + "…");

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
                            exportSequential(position + 1, exportedFiles);
                        }

                        @Override
                        public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable t) {
                            Toast.makeText(BatchReviewActivity.this, "Export failed on WK" + sunday + " SVC" + service, Toast.LENGTH_SHORT).show();
                            exportSequential(position + 1, exportedFiles);
                        }
                    });

        } catch (Exception e) {
            exportSequential(position + 1, exportedFiles);
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
