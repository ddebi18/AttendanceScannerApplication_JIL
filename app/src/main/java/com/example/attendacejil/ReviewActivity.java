package com.example.attendacejil;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * ReviewActivity — Stage 4 + 5 of the pipeline
 *
 * Receives from CameraActivity / scan response:
 *   EXTRA_ROWS_JSON : JSON string (array of row objects)
 *   EXTRA_SUNDAY    : int 1–5
 *   EXTRA_SERVICE   : int 1–2
 *   EXTRA_FLAGGED   : int (count of flagged rows)
 *
 * Features:
 *   • Horizontally scrollable editable data table (ReviewAdapter)
 *   • Yellow banner when flagged cells remain
 *   • Export CSV button (disabled until no flagged rows and no empty names)
 *   • "+ Add Member" appends blank editable row
 *   • "Remove Deleted" purges soft-deleted rows
 *   • "↩ Rescan" discards and returns to MainActivity
 *   • On successful export: Download + Share sheet
 */
public class ReviewActivity extends AppCompatActivity
        implements ReviewAdapter.OnDataChangedListener {

    // Intent extras — in
    public static final String EXTRA_ROWS_JSON = "rows_json";
    public static final String EXTRA_SUNDAY    = "sunday_num";
    public static final String EXTRA_SERVICE   = "service_num";
    public static final String EXTRA_FLAGGED   = "flagged_count";

    // ── UI ────────────────────────────────────────────────────────────────────
    private RecyclerView   recyclerView;
    private ReviewAdapter  adapter;
    private Button         btnExportCsv, btnRescan, btnPurgeDeleted;
    private LinearLayout   bannerFlagged;
    private TextView       tvBannerText;
    private View           emptyStateLayout;

    // ── Data ──────────────────────────────────────────────────────────────────
    private final List<AttendanceRow> rows = new ArrayList<>();
    private int sundayNum  = 1;
    private int serviceNum = 1;

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_review);

        bindViews();
        parseIntent();
        setupRecyclerView();
        setupButtons();
        refreshBannerAndExport();
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private void bindViews() {
        recyclerView     = findViewById(R.id.reviewRecyclerView);
        btnExportCsv     = findViewById(R.id.btnExportCsv);
        btnRescan        = findViewById(R.id.btnRescan);
        btnPurgeDeleted  = findViewById(R.id.btnPurgeDeleted);
        bannerFlagged    = findViewById(R.id.bannerFlagged);
        tvBannerText     = findViewById(R.id.tvBannerText);
        emptyStateLayout = findViewById(R.id.emptyStateLayout);
    }

    // ── Parse intent extras ───────────────────────────────────────────────────

    private void parseIntent() {
        sundayNum  = getIntent().getIntExtra(EXTRA_SUNDAY,  1);
        serviceNum = getIntent().getIntExtra(EXTRA_SERVICE, 1);
        String json = getIntent().getStringExtra(EXTRA_ROWS_JSON);
        if (json != null) {
            parseRowsJson(json);
        }
    }

    /** Returns 0-based index into the attendance[10] array for the selected session. */
    private int selectedColumn() {
        return (sundayNum - 1) * 2 + (serviceNum - 1);
    }

    /** Human-readable label for the selected session, e.g. "WK1 SVC1". */
    private String sessionLabel() {
        String svc = serviceNum == 1 ? "1ST" : "2ND";
        return "WK" + sundayNum + " " + svc;
    }

    private void parseRowsJson(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);

                String lastName   = obj.optString("last_name",  "");
                int    lastConf   = obj.optInt   ("last_name_conf",  0);
                String firstName  = obj.optString("first_name", "");
                int    firstConf  = obj.optInt   ("first_name_conf", 0);
                String network    = obj.optString("network",    "");
                int    netConf    = obj.optInt   ("network_conf",    0);
                boolean flagged   = obj.optBoolean("flagged",   false);

                boolean[] att = new boolean[10];
                JSONArray attArr = obj.optJSONArray("attendance");
                if (attArr != null) {
                    for (int j = 0; j < Math.min(attArr.length(), 10); j++) {
                        att[j] = attArr.optBoolean(j, false);
                    }
                }

                rows.add(new AttendanceRow(
                        lastName, lastConf,
                        firstName, firstConf,
                        network, netConf,
                        att, flagged));
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not parse scan data: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    // ── RecyclerView ──────────────────────────────────────────────────────────

    private void setupRecyclerView() {
        adapter = new ReviewAdapter(this, rows, this, selectedColumn());
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        // Set the dynamic attendance column header
        TextView tvHdr = findViewById(R.id.tvAttHeader);
        if (tvHdr != null) tvHdr.setText(sessionLabel());

        // Show empty state immediately if no rows were scanned
        updateEmptyState();
    }

    /** Show/hide the empty-state panel based on active (non-deleted) row count. */
    private void updateEmptyState() {
        long active = rows.stream().filter(r -> !r.markedForDeletion).count();
        emptyStateLayout.setVisibility(active == 0 ? View.VISIBLE : View.GONE);
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private void setupButtons() {
        btnRescan.setOnClickListener(v -> confirmRescan());

        btnExportCsv.setOnClickListener(v -> exportCsv());

        findViewById(R.id.btnAddRow).setOnClickListener(v -> {
            rows.add(new AttendanceRow());   // blank, manuallyAdded=true
            adapter.notifyItemInserted(rows.size() - 1);
            recyclerView.scrollToPosition(rows.size() - 1);
            updateEmptyState();
            refreshBannerAndExport();
        });

        btnPurgeDeleted.setOnClickListener(v -> {
            rows.removeIf(r -> r.markedForDeletion);
            adapter.notifyDataSetChanged();
            btnPurgeDeleted.setVisibility(View.GONE);
            updateEmptyState();
            refreshBannerAndExport();
        });
    }

    // ── OnDataChangedListener ─────────────────────────────────────────────────

    @Override
    public void onDataChanged() {
        updateEmptyState();
        refreshBannerAndExport();
    }

    // ── Banner + export button state ──────────────────────────────────────────

    private void refreshBannerAndExport() {
        // Count flagged (not deleted)
        int flaggedCount = 0;
        boolean hasDeleted = false;
        boolean anyInvalid = false;

        for (AttendanceRow r : rows) {
            if (r.markedForDeletion) { hasDeleted = true; continue; }
            if (r.flagged) flaggedCount++;
            if (r.lastName.trim().isEmpty() || r.firstName.trim().isEmpty()
                    || r.network.trim().isEmpty()) anyInvalid = true;
        }

        // Banner: always shown — tells user to mark attendance manually
        bannerFlagged.setVisibility(View.VISIBLE);
        if (flaggedCount > 0) {
            tvBannerText.setText("⚠ " + flaggedCount
                    + " name(s) need review — tap to fix, then mark attendance ✓");
        } else {
            tvBannerText.setText("Tap ✓ on each person who attended this service");
        }

        // Purge button
        btnPurgeDeleted.setVisibility(hasDeleted ? View.VISIBLE : View.GONE);

        // Export button: enabled only when no flagged AND no empty required fields
        // AND at least one non-deleted row exists
        long activeRows = rows.stream()
                .filter(r -> !r.markedForDeletion).count();
        boolean canExport = (flaggedCount == 0) && !anyInvalid && (activeRows > 0);
        btnExportCsv.setEnabled(canExport);
        btnExportCsv.setAlpha(canExport ? 1.0f : 0.4f);
    }

    // ── Rescan ────────────────────────────────────────────────────────────────

    private void confirmRescan() {
        new MaterialAlertDialogBuilder(this, R.style.AttendanceDialogTheme)
                .setTitle("Rescan?")
                .setMessage("This will discard all current review data.")
                .setPositiveButton("Rescan", (d, w) -> {
                    setResult(RESULT_CANCELED);
                    finish();
                })
                .setNegativeButton("Stay", null)
                .show();
    }

    // ── Export CSV ────────────────────────────────────────────────────────────

    private void exportCsv() {
        // Build JSON payload
        try {
            JSONObject payload = new JSONObject();
            payload.put("sunday_num",  sundayNum);
            payload.put("service_num", serviceNum);

            Calendar cal = Calendar.getInstance();
            payload.put("month", new SimpleDateFormat("MMMM", Locale.getDefault())
                    .format(cal.getTime()).toUpperCase(Locale.getDefault()));
            payload.put("year", cal.get(Calendar.YEAR));

            JSONArray rowsArr = new JSONArray();
            for (AttendanceRow r : rows) {
                if (r.markedForDeletion) continue;
                JSONObject obj = new JSONObject();
                obj.put("last_name",  r.lastName);
                obj.put("first_name", r.firstName);
                obj.put("network",    r.network);
                JSONArray attArr = new JSONArray();
                for (boolean b : r.attendance) attArr.put(b);
                obj.put("attendance", attArr);
                rowsArr.put(obj);
            }
            payload.put("rows", rowsArr);

            RequestBody body = RequestBody.create(
                    MediaType.parse("application/json"),
                    payload.toString());

            btnExportCsv.setEnabled(false);
            btnExportCsv.setText("Exporting…");

            RetrofitClient.getApiService()
                    .exportCsv(body)
                    .enqueue(new Callback<ResponseBody>() {
                        @Override
                        public void onResponse(@NonNull Call<ResponseBody> call,
                                               @NonNull Response<ResponseBody> resp) {
                            if (resp.isSuccessful() && resp.body() != null) {
                                saveCsv(resp.body());
                            } else {
                                onFail("Server error: " + resp.code());
                            }
                        }
                        @Override
                        public void onFailure(@NonNull Call<ResponseBody> call,
                                              @NonNull Throwable t) {
                            onFail("Network error: " + t.getMessage());
                        }
                    });

        } catch (Exception e) {
            Toast.makeText(this, "Export error: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void saveCsv(ResponseBody body) {
        try {
            // Use the server's Content-Disposition filename if available
            // e.g. "1ST SERVICE_APR_05 - Check-Ins Report.csv"
            String fname = "attendance_export.csv";
            String contentDisp = body.contentType() != null ? "" : "";
            // Retrofit exposes headers via the raw response — read it from the callback
            // Fallback: build the expected name locally
            Calendar cal   = Calendar.getInstance();
            String month   = new java.text.SimpleDateFormat("MMM", Locale.getDefault())
                    .format(cal.getTime()).toUpperCase(Locale.getDefault());
            String[] ords  = {"", "1ST", "2ND", "3RD", "4TH", "5TH"};
            String svcLbl  = (serviceNum >= 1 && serviceNum <= 5 ? ords[serviceNum] : "1ST");

            // Compute the Sunday date for the filename (Nth Sunday of the month)
            int yr = cal.get(java.util.Calendar.YEAR);
            java.util.Calendar firstOfMonth = java.util.Calendar.getInstance();
            firstOfMonth.set(yr, cal.get(java.util.Calendar.MONTH), 1);
            int dayOfWeek = firstOfMonth.get(java.util.Calendar.DAY_OF_WEEK);
            int firstSunOffset = (java.util.Calendar.SUNDAY - dayOfWeek + 7) % 7;
            int sundayDay = 1 + firstSunOffset + (sundayNum - 1) * 7;
            String dayStr = String.format(Locale.getDefault(), "%02d", sundayDay);

            fname = String.format(Locale.getDefault(),
                    "%s SERVICE_%s_%s - Check-Ins Report.csv",
                    svcLbl, month, dayStr);

            File dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, fname);

            try (InputStream is = body.byteStream();
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[4096]; int n;
                while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            }

            final String finalFname = fname;
            runOnUiThread(() -> showExportSuccess(out, finalFname));

        } catch (IOException e) {
            runOnUiThread(() -> onFail("Save failed: " + e.getMessage()));
        }
    }

    private void showExportSuccess(File csvFile, String fname) {
        btnExportCsv.setEnabled(true);
        btnExportCsv.setText("Export CSV");

        long totalRows   = rows.stream().filter(r -> !r.markedForDeletion).count();
        int  colIdx      = (sundayNum - 1) * 2 + (serviceNum - 1);
        long attendedCnt = rows.stream()
                .filter(r -> !r.markedForDeletion)
                .filter(r -> r.attendance != null
                        && colIdx < r.attendance.length
                        && r.attendance[colIdx])
                .count();

        new MaterialAlertDialogBuilder(this, R.style.AttendanceDialogTheme)
                .setTitle("✅ Export Complete")
                .setMessage("File: " + fname
                        + "\n\nTotal members: " + totalRows
                        + "\nAttended (checked ✓): " + attendedCnt
                        + "\n\nSaved to Downloads.")
                .setPositiveButton("Share", (d, w) -> shareFile(csvFile))
                .setNegativeButton("Done", null)
                .show();
    }

    private void shareFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".provider",
                    file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/csv");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share Attendance CSV"));
        } catch (Exception e) {
            Toast.makeText(this, "Share failed: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void onFail(String msg) {
        runOnUiThread(() -> {
            btnExportCsv.setEnabled(true);
            btnExportCsv.setText("Export CSV");
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
    }
}
