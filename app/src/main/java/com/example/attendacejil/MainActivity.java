package com.example.attendacejil;

import android.Manifest;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.MultipartBody;

import org.json.JSONArray;
import org.json.JSONObject;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * MainActivity — Session Setup Screen
 *
 * Responsibilities:
 *   1. Let the user pick which Sunday (1–5) and Service (1st/2nd)
 *   2. Enable the Camera FAB only when both are selected
 *   3. Open CameraActivity → on photo accepted → POST /scan → ReviewActivity
 *
 * All scanning, preview, editing, and CSV export happen downstream.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CAMERA_PERM = 100;

    // ── Selection state ───────────────────────────────────────────────────────
    private int selectedSunday  = 1;   // 1–5
    private int selectedService = 0;   // 0 = none, 1 = 1st, 2 = 2nd
    private java.util.Calendar selectedCalendar = java.util.Calendar.getInstance();

    // ── Views ─────────────────────────────────────────────────────────────────
    private TextView       chipSunday, chipService;
    private TextView       tvSessionMonth, tvSessionWeek;
    private TextView       tvSelectedSunday, tvSelectedService;
    private TextView       tvSelectionHint;
    private FloatingActionButton fabGallery, fabEnhance, fabManageMembers;

    private Button[] sundayBtns;   // index 0–4

    // ── Launchers ─────────────────────────────────────────────────────────────
    private ActivityResultLauncher<Intent> cameraLauncher;
    private ActivityResultLauncher<Intent> galleryLauncher;
    private ActivityResultLauncher<Intent> enhanceCameraLauncher;
    private ActivityResultLauncher<Intent> enhanceGalleryLauncher;

    private final java.util.List<NameMatcher.DbMember> cachedDbMembers = new java.util.ArrayList<>();

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupCameraLauncher();
        setupGalleryLauncher();
        setupEnhanceLaunchers();
        setupSundayButtons();
        setupServiceButtons();
        setupFabs();
        updateSessionCard();
        refreshChipsAndFab();
    }

    @Override
    protected void onResume() {
        super.onResume();
        SupabaseClient.init(this);
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private void bindViews() {
        chipSunday        = findViewById(R.id.chipSunday);
        chipService       = findViewById(R.id.chipService);
        tvSessionMonth    = findViewById(R.id.tvSessionMonth);
        tvSessionWeek     = findViewById(R.id.tvSessionWeek);
        tvSelectedSunday  = findViewById(R.id.tvSelectedSunday);
        tvSelectedService = findViewById(R.id.tvSelectedService);
        tvSelectionHint   = findViewById(R.id.tvSelectionHint);
        fabGallery        = findViewById(R.id.fabGallery);
        fabEnhance        = findViewById(R.id.fabEnhance);
        fabManageMembers  = findViewById(R.id.fabManageMembers);

        findViewById(R.id.btnBatchScan).setOnClickListener(v -> {
            Intent intent = new Intent(this, BatchMonthlyActivity.class);
            startActivity(intent);
        });

        fabManageMembers.setOnClickListener(v -> openManageMembers());

        tvSessionMonth.setOnClickListener(v -> {
            new android.app.DatePickerDialog(this, (view, y, m, d) -> {
                selectedCalendar.set(java.util.Calendar.YEAR, y);
                selectedCalendar.set(java.util.Calendar.MONTH, m);
                updateSundayButtonStates();
                updateSessionCard();
                refreshChipsAndFab();
            }, selectedCalendar.get(java.util.Calendar.YEAR), selectedCalendar.get(java.util.Calendar.MONTH), 1).show();
        });

        sundayBtns = new Button[]{
            findViewById(R.id.btn1stSunday),
            findViewById(R.id.btn2ndSunday),
            findViewById(R.id.btn3rdSunday),
            findViewById(R.id.btn4thSunday),
            findViewById(R.id.btn5thSunday)
        };
    }

    private void openManageMembers() {
        Intent intent = new Intent(this, ManageMembersActivity.class);
        startActivity(intent);
    }

    // ── Sunday buttons ────────────────────────────────────────────────────────

    private void setupSundayButtons() {
        String[] labels = {"1st", "2nd", "3rd", "4th", "5th"};
        for (int i = 0; i < sundayBtns.length; i++) {
            final int sunday = i + 1;
            sundayBtns[i].setOnClickListener(v -> {
                selectedSunday = sunday;
                updateSundayButtonStates();
                updateSessionCard();
                refreshChipsAndFab();
            });
        }
        updateSundayButtonStates();
    }

    private void updateSundayButtonStates() {
        int totalSundays = countSundaysInMonth(selectedCalendar);

        // Safety check: if user had 5th week selected but new month only has 4, snap back to 4.
        if (selectedSunday > totalSundays) {
            selectedSunday = totalSundays;
        }

        int activeColor   = getColor(R.color.accent_blue);
        int inactiveColor = 0xFF2A2A3D;
        for (int i = 0; i < sundayBtns.length; i++) {
            int sundayIndex = i + 1;
            boolean active = (sundayIndex == selectedSunday);
            boolean available = (sundayIndex <= totalSundays);

            sundayBtns[i].setEnabled(available);

            if (available) {
                sundayBtns[i].setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(
                                active ? activeColor : inactiveColor));
                sundayBtns[i].setTextColor(active ? 0xFFFFFFFF : 0xFF9E9E9E);
                sundayBtns[i].setAlpha(1.0f);
            } else {
                sundayBtns[i].setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(inactiveColor));
                sundayBtns[i].setTextColor(0xFF555555);
                sundayBtns[i].setAlpha(0.4f);
            }
        }
    }

    // ── Service buttons ───────────────────────────────────────────────────────

    private void setupServiceButtons() {
        Button btn1 = findViewById(R.id.btn1stService);
        Button btn2 = findViewById(R.id.btn2ndService);

        btn1.setOnClickListener(v -> {
            selectedService = 1;
            updateServiceButtonStates();
            refreshChipsAndFab();
        });
        btn2.setOnClickListener(v -> {
            selectedService = 2;
            updateServiceButtonStates();
            refreshChipsAndFab();
        });

        updateServiceButtonStates();
    }

    private void updateServiceButtonStates() {
        Button btn1 = findViewById(R.id.btn1stService);
        Button btn2 = findViewById(R.id.btn2ndService);
        int green    = getColor(R.color.service_active);
        int inactive = 0xFF2A2A3D;
        btn1.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                selectedService == 1 ? green : inactive));
        btn2.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                selectedService == 2 ? green : inactive));
        btn1.setTextColor(selectedService == 1 ? 0xFFFFFFFF : 0xFF9E9E9E);
        btn2.setTextColor(selectedService == 2 ? 0xFFFFFFFF : 0xFF9E9E9E);
    }

    // ── Stats / chip refresh ──────────────────────────────────────────────────

    private void updateSessionCard() {
        String month = new SimpleDateFormat("MMM yyyy", Locale.getDefault())
                .format(selectedCalendar.getTime()).toUpperCase(Locale.getDefault());
        tvSessionMonth.setText(month);

        int totalSundays = countSundaysInMonth(selectedCalendar);
        tvSessionWeek.setText("Week " + selectedSunday + " of " + totalSundays);
    }

    private int countSundaysInMonth(Calendar cal) {
        Calendar c = (Calendar) cal.clone();
        c.set(Calendar.DAY_OF_MONTH, 1);
        int count = 0;
        int max = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        for (int d = 1; d <= max; d++) {
            c.set(Calendar.DAY_OF_MONTH, d);
            if (c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) count++;
        }
        return count;
    }

    private void refreshChipsAndFab() {
        // Sunday chip
        String[] ordinals = {"1st", "2nd", "3rd", "4th", "5th"};
        chipSunday.setText(ordinals[selectedSunday - 1] + " Sunday");

        // Service chip
        if (selectedService == 1) {
            chipService.setText("1st Service · 7:00am");
        } else if (selectedService == 2) {
            chipService.setText("2nd Service · 9:00am");
        } else {
            chipService.setText("Select a service");
        }

        // Stats card
        tvSelectedSunday.setText(ordinals[selectedSunday - 1]);
        if (selectedService == 1) {
            tvSelectedService.setText("1st Service · 7:00am");
        } else if (selectedService == 2) {
            tvSelectedService.setText("2nd Service · 9:00am");
        } else {
            tvSelectedService.setText("No service selected");
        }

        // Hint
        boolean ready = selectedService != 0;
        tvSelectionHint.setVisibility(android.view.View.VISIBLE);
        tvSelectionHint.setText(ready
                ? "✅ " + ordinals[selectedSunday - 1] + " Sunday — "
                  + (selectedService == 1 ? "1st Service" : "2nd Service")
                : "⚠ Please select both a Sunday and a Service to enable scanning.");
        tvSelectionHint.setTextColor(ready ? 0xFF81C784 : 0xFFFFB74D);
    }

    // ── FABs ──────────────────────────────────────────────────────────────────

    private void setupFabs() {
        // Gallery FAB — multi-select images from gallery
        fabGallery.setOnClickListener(v -> {
            if (selectedService == 0) {
                showToast("Please select a Sunday and Service first.");
                return;
            }
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.setType("image/*");
            pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            galleryLauncher.launch(pick);
        });

        // Enhance FAB — directly open Gallery to pick images for enhancement
        fabEnhance.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.setType("image/*");
            pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            enhanceGalleryLauncher.launch(pick);
        });
    }

    // ── Camera result → /scan → ReviewActivity ─────────────────────────────

    private void setupCameraLauncher() {
        cameraLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        String path = result.getData()
                                .getStringExtra(CameraActivity.EXTRA_IMAGE_PATH);
                        if (path != null) {
                            scanImageAndOpenReview(path);
                        }
                    }
                });
    }

    // ── Gallery multi-select → /scan each → merge → ReviewActivity ───────────

    private void setupGalleryLauncher() {
        galleryLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;

                    // Collect all selected URIs
                    java.util.List<android.net.Uri> uris = new java.util.ArrayList<>();
                    android.content.ClipData clip = result.getData().getClipData();
                    if (clip != null) {
                        for (int i = 0; i < clip.getItemCount(); i++)
                            uris.add(clip.getItemAt(i).getUri());
                    } else if (result.getData().getData() != null) {
                        uris.add(result.getData().getData());
                    }

                    if (!uris.isEmpty()) scanUrisSequentially(uris);
                });
    }

    // ── Enhance launchers ─────────────────────────────────────────────────────

    private void setupEnhanceLaunchers() {
        enhanceCameraLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        String path = result.getData()
                                .getStringExtra(CameraActivity.EXTRA_IMAGE_PATH);
                        if (path != null) {
                            enhanceAndDownload(path);
                        }
                    }
                });

        enhanceGalleryLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;

                    java.util.List<android.net.Uri> uris = new java.util.ArrayList<>();
                    android.content.ClipData clip = result.getData().getClipData();
                    if (clip != null) {
                        for (int i = 0; i < clip.getItemCount(); i++)
                            uris.add(clip.getItemAt(i).getUri());
                    } else if (result.getData().getData() != null) {
                        uris.add(result.getData().getData());
                    }

                    if (!uris.isEmpty()) {
                        if (uris.size() > 30) {
                            showToast("You can only select up to 30 images.");
                            uris = uris.subList(0, 30);
                        }
                        enhanceUrisSequentially(uris);
                    }
                });
    }

    /**
     * Process each URI through /scan sequentially.
     * Shows a custom dark progress dialog with LinearProgressIndicator.
     * On completion, merges all rows and opens ReviewActivity.
     */
    private void scanUrisSequentially(java.util.List<android.net.Uri> uris) {
        fetchDbMembers(() -> performBatchScan(uris));
    }

    private void performBatchScan(java.util.List<android.net.Uri> uris) {
        // ── Custom batch loading dialog ────────────────────────────────────
        Dialog batchDialog = new Dialog(this);
        batchDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        batchDialog.setContentView(R.layout.dialog_batch_scanning);
        if (batchDialog.getWindow() != null) {
            batchDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        batchDialog.setCancelable(false);

        // Keep screen on during scanning to prevent activity destruction
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        com.google.android.material.progressindicator.LinearProgressIndicator progressBar =
                batchDialog.findViewById(R.id.batchProgressBar);
        TextView tvCounter = batchDialog.findViewById(R.id.tvBatchCounter);
        TextView tvPercent = batchDialog.findViewById(R.id.tvBatchPercent);
        TextView tvStatus  = batchDialog.findViewById(R.id.tvBatchStatus);

        int total = uris.size();
        progressBar.setMax(total);
        progressBar.setProgress(0);
        tvCounter.setText("Image 1 of " + total);
        tvPercent.setText("0%");
        batchDialog.show();
        // ──────────────────────────────────────────────────────────────────

        java.util.List<JSONObject> allRows = new java.util.ArrayList<>();
        int[] index = {0};

        processNextUri(uris, index, allRows, batchDialog,
                progressBar, tvCounter, tvPercent, tvStatus);
    }

    private void processNextUri(
            java.util.List<android.net.Uri> uris,
            int[] index,
            java.util.List<JSONObject> allRows,
            Dialog batchDialog,
            com.google.android.material.progressindicator.LinearProgressIndicator progressBar,
            TextView tvCounter,
            TextView tvPercent,
            TextView tvStatus) {

        int total = uris.size();

        if (index[0] >= total) {
            batchDialog.dismiss();
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            openReviewWithRows(allRows, uris);
            return;
        }

        android.net.Uri uri = uris.get(index[0]);
        int current = index[0] + 1;
        int pct = (int) (100f * index[0] / total);
        runOnUiThread(() -> {
            progressBar.setProgress(index[0]);
            tvCounter.setText("Image " + current + " of " + total);
            tvPercent.setText(pct + "%");
            tvStatus.setText("Scanning image " + current + "…");
        });

        try {
            File tmp = uriToTempFile(uri, current);
            if (tmp == null) {
                index[0]++;
                processNextUri(uris, index, allRows, batchDialog,
                        progressBar, tvCounter, tvPercent, tvStatus);
                return;
            }

            RequestBody body = RequestBody.create(MediaType.parse("image/jpeg"), tmp);
            MultipartBody.Part part = MultipartBody.Part
                    .createFormData("image", tmp.getName(), body);
            RequestBody sundayBody  = RequestBody.create(
                    MediaType.parse("text/plain"), String.valueOf(selectedSunday));
            RequestBody serviceBody = RequestBody.create(
                    MediaType.parse("text/plain"), String.valueOf(selectedService));

            RetrofitClient.getApiService()
                    .scanImage(part, sundayBody, serviceBody)
                    .enqueue(new Callback<ResponseBody>() {
                        @Override
                        public void onResponse(@NonNull Call<ResponseBody> call,
                                               @NonNull Response<ResponseBody> resp) {
                            if (resp.isSuccessful() && resp.body() != null) {
                                try {
                                    JSONObject json = new JSONObject(resp.body().string());
                                    JSONArray rows  = json.optJSONArray("rows");
                                    if (rows != null)
                                        for (int i = 0; i < rows.length(); i++)
                                            allRows.add(rows.getJSONObject(i));
                                } catch (Exception e) {
                                    showToast("Parse error on image " + current);
                                }
                            }
                            //noinspection ResultOfMethodCallIgnored
                            tmp.delete();
                            index[0]++;
                            runOnUiThread(() -> tvStatus.setText("Cooling down (4.5s)..."));
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                processNextUri(uris, index, allRows, batchDialog,
                                        progressBar, tvCounter, tvPercent, tvStatus);
                            }, 4500);
                        }

                        @Override
                        public void onFailure(@NonNull Call<ResponseBody> call,
                                              @NonNull Throwable t) {
                            showToast("Image " + current + " failed: " + t.getMessage());
                            //noinspection ResultOfMethodCallIgnored
                            tmp.delete();
                            index[0]++;
                            runOnUiThread(() -> tvStatus.setText("Cooling down (4.5s)..."));
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                processNextUri(uris, index, allRows, batchDialog,
                                        progressBar, tvCounter, tvPercent, tvStatus);
                            }, 4500);
                        }
                    });

        } catch (Exception e) {
            showToast("Error reading image " + current);
            index[0]++;
            processNextUri(uris, index, allRows, batchDialog,
                    progressBar, tvCounter, tvPercent, tvStatus);
        }
    }

    /** Copy a content URI to a temporary JPEG file in cache. */
    private File uriToTempFile(android.net.Uri uri, int idx) {
        try {
            File tmp = new File(getCacheDir(), "gallery_" + idx + "_"
                    + System.currentTimeMillis() + ".jpg");
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                if (in == null) return null;
                byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            return tmp;
        } catch (Exception e) {
            showToast("Could not read image: " + e.getMessage());
            return null;
        }
    }

    /** Open ReviewActivity with pre-merged rows (de-duplicated by last+first name). */
    private void openReviewWithRows(java.util.List<JSONObject> allRows, java.util.List<android.net.Uri> uris) {
        // De-duplicate by last_name + first_name key
        java.util.LinkedHashMap<String, JSONObject> seen = new java.util.LinkedHashMap<>();
        for (JSONObject r : allRows) {
            String key = r.optString("last_name", "").trim().toUpperCase() + "|"
                    + r.optString("first_name", "").trim().toUpperCase();
            if (!seen.containsKey(key)) {
                seen.put(key, r);
            } else {
                // Merge attendance arrays of duplicate entries by performing an OR on each element
                JSONObject existing = seen.get(key);
                JSONArray extAtt = existing.optJSONArray("attendance");
                JSONArray newAtt = r.optJSONArray("attendance");
                if (extAtt != null && newAtt != null) {
                    JSONArray mergedAtt = new JSONArray();
                    for (int i = 0; i < 10; i++) {
                        mergedAtt.put(extAtt.optBoolean(i, false) || newAtt.optBoolean(i, false));
                    }
                    try {
                        existing.put("attendance", mergedAtt);
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
        }
        JSONArray merged = new JSONArray();
        for (JSONObject r : seen.values()) merged.put(r);

        NameMatcher.autoCorrectNames(merged, cachedDbMembers);

        int flaggedCount = 0;
        for (int i = 0; i < merged.length(); i++) {
            JSONObject row = merged.optJSONObject(i);
            if (row != null && row.optBoolean("flagged", false)) flaggedCount++;
        }

        Intent intent = new Intent(this, ReviewActivity.class);
        intent.putExtra(ReviewActivity.EXTRA_ROWS_JSON, merged.toString());
        intent.putExtra(ReviewActivity.EXTRA_SUNDAY,    selectedSunday);
        intent.putExtra(ReviewActivity.EXTRA_SERVICE,   selectedService);
        intent.putExtra(ReviewActivity.EXTRA_FLAGGED,   flaggedCount);
        intent.putExtra(ReviewActivity.EXTRA_YEAR,      selectedCalendar.get(java.util.Calendar.YEAR));
        intent.putExtra(ReviewActivity.EXTRA_MONTH,     selectedCalendar.get(java.util.Calendar.MONTH));
        
        java.util.ArrayList<String> uriStrings = new java.util.ArrayList<>();
        if (uris != null) {
            for (android.net.Uri uri : uris) {
                uriStrings.add(uri.toString());
            }
        }
        intent.putStringArrayListExtra(ImageViewerActivity.EXTRA_IMAGE_URIS, uriStrings);
        
        startActivity(intent);
    }


    // ── /scan API call ────────────────────────────────────────────────────────

    /** Status messages that cycle while a single image is being scanned. */
    private static final String[] SCAN_STATUSES = {
            "Enhancing image quality…",
            "Sending to Gemini AI…",
            "Reading names and networks…",
            "Verifying extracted data…",
            "Building attendance table…"
    };

    private void fetchDbMembers(Runnable onLoaded) {
        if (SupabaseClient.getApiService() == null) {
            onLoaded.run();
            return;
        }
        SupabaseClient.getApiService().getMembers().enqueue(new Callback<String>() {
            @Override
            public void onResponse(@NonNull Call<String> call, @NonNull Response<String> response) {
                cachedDbMembers.clear();
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        JSONArray arr = new JSONArray(response.body());
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject obj = arr.getJSONObject(i);
                            String id = obj.optString("id", "");
                            String f = obj.optString("first_name", "");
                            String l = obj.optString("last_name", "");
                            String n = obj.optString("network_name", "");
                            cachedDbMembers.add(new NameMatcher.DbMember(id, f, l, n));
                        }
                    } catch (Exception e) {}
                }
                onLoaded.run();
            }

            @Override
            public void onFailure(@NonNull Call<String> call, @NonNull Throwable t) {
                onLoaded.run();
            }
        });
    }

    private void scanImageAndOpenReview(String imagePath) {
        fetchDbMembers(() -> performSingleScan(imagePath));
    }

    private void performSingleScan(String imagePath) {
        // ── Custom dark loading dialog ─────────────────────────────────────
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_scanning);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.setCancelable(false);

        // Keep screen on during scanning to prevent activity destruction
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        TextView tvStatus = dialog.findViewById(R.id.tvScanStatus);
        dialog.show();

        // Cycle through status messages every 4s while waiting
        Handler statusHandler = new Handler(Looper.getMainLooper());
        int[] statusIdx = {0};
        Runnable statusCycler = new Runnable() {
            @Override public void run() {
                if (dialog.isShowing()) {
                    statusIdx[0] = (statusIdx[0] + 1) % SCAN_STATUSES.length;
                    tvStatus.setText(SCAN_STATUSES[statusIdx[0]]);
                    statusHandler.postDelayed(this, 4000);
                }
            }
        };
        statusHandler.postDelayed(statusCycler, 4000);
        // ──────────────────────────────────────────────────────────────────

        File file = new File(imagePath);
        RequestBody body = RequestBody.create(MediaType.parse("image/jpeg"), file);
        MultipartBody.Part part = MultipartBody.Part
                .createFormData("image", file.getName(), body);
        RequestBody sundayBody  = RequestBody.create(
                MediaType.parse("text/plain"), String.valueOf(selectedSunday));
        RequestBody serviceBody = RequestBody.create(
                MediaType.parse("text/plain"), String.valueOf(selectedService));

        RetrofitClient.getApiService()
                .scanImage(part, sundayBody, serviceBody)
                .enqueue(new Callback<ResponseBody>() {
                    @Override
                    public void onResponse(@NonNull Call<ResponseBody> call,
                                           @NonNull Response<ResponseBody> resp) {
                        statusHandler.removeCallbacks(statusCycler);
                        dialog.dismiss();
                        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        if (resp.isSuccessful() && resp.body() != null) {
                            try {
                                openReview(resp.body().string(), imagePath);
                            } catch (IOException e) {
                                showToast("Could not read scan response.");
                            }
                        } else {
                            showToast("Scan error: " + resp.code()
                                    + ". Is the server running?");
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ResponseBody> call,
                                          @NonNull Throwable t) {
                        statusHandler.removeCallbacks(statusCycler);
                        dialog.dismiss();
                        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        showToast("Network error: " + t.getMessage());
                    }
                });
    }

    private void openReview(String fullJson, String imagePath) {
        int flaggedCount = 0;
        String rowsJson  = "[]";
        try {
            org.json.JSONObject obj = new org.json.JSONObject(fullJson);
            JSONArray rowsArray = obj.optJSONArray("rows");
            if (rowsArray != null) {
                NameMatcher.autoCorrectNames(rowsArray, cachedDbMembers);
                
                // Recalculate flagged count and clear attendance
                for (int i = 0; i < rowsArray.length(); i++) {
                    JSONObject row = rowsArray.optJSONObject(i);
                    if (row != null) {
                        row.put("id", java.util.UUID.randomUUID().toString());
                        org.json.JSONArray emptyAtt = new org.json.JSONArray();
                        for (int j = 0; j < 10; j++) emptyAtt.put(false);
                        row.put("attendance", emptyAtt);
                        if (row.optBoolean("flagged", false)) flaggedCount++;
                    }
                }
                rowsJson = rowsArray.toString();
            }
        } catch (Exception e) {
            showToast("Parse error: " + e.getMessage());
            return;
        }

        Intent intent = new Intent(this, ReviewActivity.class);
        intent.putExtra(ReviewActivity.EXTRA_ROWS_JSON, rowsJson);
        intent.putExtra(ReviewActivity.EXTRA_SUNDAY,    selectedSunday);
        intent.putExtra(ReviewActivity.EXTRA_SERVICE,   selectedService);
        intent.putExtra(ReviewActivity.EXTRA_FLAGGED,   flaggedCount);
        intent.putExtra(ReviewActivity.EXTRA_YEAR,      selectedCalendar.get(java.util.Calendar.YEAR));
        intent.putExtra(ReviewActivity.EXTRA_MONTH,     selectedCalendar.get(java.util.Calendar.MONTH));
        
        java.util.ArrayList<String> uriStrings = new java.util.ArrayList<>();
        if (imagePath != null) {
            uriStrings.add(imagePath);
        }
        intent.putStringArrayListExtra(ImageViewerActivity.EXTRA_IMAGE_URIS, uriStrings);
        
        startActivity(intent);
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private boolean checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) return true;
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERM);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQUEST_CAMERA_PERM && grants.length > 0
                && grants[0] == PackageManager.PERMISSION_GRANTED) {
            Intent intent = new Intent(this, CameraActivity.class);
            cameraLauncher.launch(intent);
        } else {
            showToast("Camera permission is required to scan.");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void showToast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    // ── Enhance & Download ────────────────────────────────────────────────

    /** Show a dialog letting the user choose Camera or Gallery as the image source. */
    private void showEnhanceSourceDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_enhance_source);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        dialog.findViewById(R.id.btnEnhanceCamera).setOnClickListener(v -> {
            dialog.dismiss();
            if (!checkCameraPermission()) return;
            Intent intent = new Intent(this, CameraActivity.class);
            enhanceCameraLauncher.launch(intent);
        });

        dialog.findViewById(R.id.btnEnhanceGallery).setOnClickListener(v -> {
            dialog.dismiss();
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.setType("image/*");
            pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            enhanceGalleryLauncher.launch(pick);
        });

        dialog.show();
    }

    /**
     * Send the image to POST /enhance, receive enhanced JPEG,
     * save to device Pictures folder, and notify the user.
     */
    private void enhanceAndDownload(String imagePath) {
        // Show loading dialog
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_enhancing);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.setCancelable(false);
        dialog.show();

        File file = new File(imagePath);
        RequestBody body = RequestBody.create(MediaType.parse("image/jpeg"), file);
        MultipartBody.Part part = MultipartBody.Part
                .createFormData("image", file.getName(), body);

        RetrofitClient.getApiService()
                .enhanceImage(part)
                .enqueue(new Callback<ResponseBody>() {
                    @Override
                    public void onResponse(@NonNull Call<ResponseBody> call,
                                           @NonNull Response<ResponseBody> resp) {
                        dialog.dismiss();
                        if (resp.isSuccessful() && resp.body() != null) {
                            try {
                                byte[] imageBytes = resp.body().bytes();
                                String savedPath = saveEnhancedImage(imageBytes);
                                if (savedPath != null) {
                                    showToast("✅ Enhanced image saved to Pictures!");
                                } else {
                                    showToast("Could not save enhanced image.");
                                }
                            } catch (IOException e) {
                                showToast("Error reading enhanced image.");
                            }
                        } else {
                            showToast("Enhancement error: " + resp.code());
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ResponseBody> call,
                                          @NonNull Throwable t) {
                        dialog.dismiss();
                        showToast("Network error: " + t.getMessage());
                    }
                });
    }

    /**
     * Sequentially enhance a list of image URIs.
     * Shows a batch loading dialog and processes each image.
     */
    private void enhanceUrisSequentially(java.util.List<android.net.Uri> uris) {
        Dialog batchDialog = new Dialog(this);
        batchDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        batchDialog.setContentView(R.layout.dialog_batch_scanning);
        if (batchDialog.getWindow() != null) {
            batchDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        batchDialog.setCancelable(false);

        com.google.android.material.progressindicator.LinearProgressIndicator progressBar =
                batchDialog.findViewById(R.id.batchProgressBar);
        TextView tvCounter = batchDialog.findViewById(R.id.tvBatchCounter);
        TextView tvPercent = batchDialog.findViewById(R.id.tvBatchPercent);
        TextView tvStatus  = batchDialog.findViewById(R.id.tvBatchStatus);

        int total = uris.size();
        progressBar.setMax(total);
        progressBar.setProgress(0);
        tvCounter.setText("Image 1 of " + total);
        tvPercent.setText("0%");
        batchDialog.show();

        int[] index = {0};
        int[] successCount = {0};
        enhanceNextUri(uris, index, successCount, batchDialog, progressBar, tvCounter, tvPercent, tvStatus);
    }

    private void enhanceNextUri(
            java.util.List<android.net.Uri> uris,
            int[] index,
            int[] successCount,
            Dialog batchDialog,
            com.google.android.material.progressindicator.LinearProgressIndicator progressBar,
            TextView tvCounter,
            TextView tvPercent,
            TextView tvStatus) {

        int total = uris.size();

        if (index[0] >= total) {
            batchDialog.dismiss();
            if (successCount[0] > 0) {
                showToast("✅ " + successCount[0] + " enhanced image(s) saved to Pictures!");
            }
            return;
        }

        android.net.Uri uri = uris.get(index[0]);
        int current = index[0] + 1;
        int pct = (int) (100f * index[0] / total);
        runOnUiThread(() -> {
            progressBar.setProgress(index[0]);
            tvCounter.setText("Image " + current + " of " + total);
            tvPercent.setText(pct + "%");
            tvStatus.setText("Enhancing image " + current + "…");
        });

        try {
            File tmp = uriToTempFile(uri, current);
            if (tmp == null) {
                index[0]++;
                enhanceNextUri(uris, index, successCount, batchDialog, progressBar, tvCounter, tvPercent, tvStatus);
                return;
            }

            RequestBody body = RequestBody.create(MediaType.parse("image/jpeg"), tmp);
            MultipartBody.Part part = MultipartBody.Part.createFormData("image", tmp.getName(), body);

            RetrofitClient.getApiService().enhanceImage(part).enqueue(new Callback<ResponseBody>() {
                @Override
                public void onResponse(@NonNull Call<ResponseBody> call, @NonNull Response<ResponseBody> resp) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        try {
                            String savedPath = saveEnhancedImage(resp.body().bytes());
                            if (savedPath != null) successCount[0]++;
                        } catch (IOException e) {
                            showToast("Error saving image " + current);
                        }
                    } else {
                        showToast("Enhancement failed for image " + current);
                    }
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    index[0]++;
                    enhanceNextUri(uris, index, successCount, batchDialog, progressBar, tvCounter, tvPercent, tvStatus);
                }

                @Override
                public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable t) {
                    showToast("Network error on image " + current);
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    index[0]++;
                    enhanceNextUri(uris, index, successCount, batchDialog, progressBar, tvCounter, tvPercent, tvStatus);
                }
            });

        } catch (Exception e) {
            showToast("Error reading image " + current);
            index[0]++;
            enhanceNextUri(uris, index, successCount, batchDialog, progressBar, tvCounter, tvPercent, tvStatus);
        }
    }

    /**
     * Save enhanced JPEG bytes to the device's Pictures/AttendanceJIL/ folder.
     * Uses MediaStore for API 29+ (scoped storage), direct file write otherwise.
     * Returns the saved file path/URI string, or null on failure.
     */
    private String saveEnhancedImage(byte[] imageBytes) {
        String fileName = "Enhanced_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                        .format(new Date()) + ".jpg";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Scoped storage — use MediaStore
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/AttendanceJIL");

            Uri uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;

            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) return null;
                out.write(imageBytes);
                out.flush();
                return uri.toString();
            } catch (IOException e) {
                showToast("Save error: " + e.getMessage());
                return null;
            }
        } else {
            // Legacy storage
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "AttendanceJIL");
            if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            File outFile = new File(dir, fileName);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(imageBytes);
                fos.flush();
                // Notify media scanner
                Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                scanIntent.setData(Uri.fromFile(outFile));
                sendBroadcast(scanIntent);
                return outFile.getAbsolutePath();
            } catch (IOException e) {
                showToast("Save error: " + e.getMessage());
                return null;
            }
        }
    }
}