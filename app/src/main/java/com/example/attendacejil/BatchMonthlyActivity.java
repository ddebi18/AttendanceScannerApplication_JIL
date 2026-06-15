package com.example.attendacejil;

import android.app.Dialog;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class BatchMonthlyActivity extends AppCompatActivity {

    private final List<Uri> selectedImages = new ArrayList<>();
    private BatchImageAdapter imageAdapter;
    private final List<NameMatcher.DbMember> cachedDbMembers = new ArrayList<>();

    private TextView tvBatchMonth, tvImageCount;
    private Button btnAddImages, btnScanAll, btnResumeBatch;
    private android.widget.CheckBox cbWk1, cbWk2, cbWk3, cbWk4, cbWk5;
    private RecyclerView rvImages;

    private ActivityResultLauncher<Intent> galleryLauncher;
    private java.util.Calendar selectedCalendar = java.util.Calendar.getInstance();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_batch_monthly);

        bindViews();
        setupMonthDisplay();
        setupRecyclerView();
        setupGalleryLauncher();

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnAddImages.setOnClickListener(v -> openGallery());
        btnScanAll.setOnClickListener(v -> scanAllImages());
        
        checkResumeProgress();
    }

    private void checkResumeProgress() {
        File tmp = new File(getCacheDir(), "batch_master_data.json");
        boolean isValid = false;
        if (tmp.exists()) {
            try {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(tmp));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                org.json.JSONArray arr = new org.json.JSONArray(sb.toString());
                if (arr.length() > 0) {
                    isValid = true;
                }
            } catch (Exception e) {
                // Invalid JSON
            }
        }

        if (isValid) {
            btnResumeBatch.setVisibility(View.VISIBLE);
            btnResumeBatch.setOnClickListener(v -> {
                Intent intent = new Intent(this, BatchReviewActivity.class);
                intent.putExtra("json_file_path", tmp.getAbsolutePath());
                intent.putExtra(ReviewActivity.EXTRA_YEAR, selectedCalendar.get(java.util.Calendar.YEAR));
                intent.putExtra(ReviewActivity.EXTRA_MONTH, selectedCalendar.get(java.util.Calendar.MONTH));
                
                boolean[] disabledWeeks = {
                    !cbWk1.isChecked(), !cbWk2.isChecked(), !cbWk3.isChecked(), !cbWk4.isChecked(), !cbWk5.isChecked()
                };
                intent.putExtra("disabled_weeks", disabledWeeks);
                
                startActivity(intent);
            });
        } else {
            if (tmp.exists()) {
                tmp.delete(); // Clean up invalid/empty file
            }
            btnResumeBatch.setVisibility(View.GONE);
            btnResumeBatch.setOnClickListener(v -> {
                showToast("No previous session found.");
            });
        }
    }

    private void bindViews() {
        tvBatchMonth = findViewById(R.id.tvBatchMonth);
        tvImageCount = findViewById(R.id.tvImageCount);
        btnAddImages = findViewById(R.id.btnAddImages);
        btnScanAll = findViewById(R.id.btnScanAll);
        btnResumeBatch = findViewById(R.id.btnResumeBatch);
        cbWk1 = findViewById(R.id.cbWk1);
        cbWk2 = findViewById(R.id.cbWk2);
        cbWk3 = findViewById(R.id.cbWk3);
        cbWk4 = findViewById(R.id.cbWk4);
        cbWk5 = findViewById(R.id.cbWk5);
        rvImages = findViewById(R.id.rvImages);
    }

    private void setupMonthDisplay() {
        Runnable updateText = () -> {
            String month = new SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                    .format(selectedCalendar.getTime()).toUpperCase(Locale.getDefault());
            tvBatchMonth.setText(month);
        };
        updateText.run();

        tvBatchMonth.setOnClickListener(v -> {
            new android.app.DatePickerDialog(this, (view, y, m, d) -> {
                selectedCalendar.set(java.util.Calendar.YEAR, y);
                selectedCalendar.set(java.util.Calendar.MONTH, m);
                updateText.run();
            }, selectedCalendar.get(java.util.Calendar.YEAR), selectedCalendar.get(java.util.Calendar.MONTH), 1).show();
        });
    }

    private void setupRecyclerView() {
        imageAdapter = new BatchImageAdapter(this, selectedImages, this::updateScanButtonState);
        rvImages.setLayoutManager(new GridLayoutManager(this, 3));
        rvImages.setAdapter(imageAdapter);
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        galleryLauncher.launch(intent);
    }

    private void setupGalleryLauncher() {
        galleryLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        if (data.getClipData() != null) {
                            ClipData mClipData = data.getClipData();
                            for (int i = 0; i < mClipData.getItemCount(); i++) {
                                ClipData.Item item = mClipData.getItemAt(i);
                                Uri uri = item.getUri();
                                selectedImages.add(uri);
                            }
                        } else if (data.getData() != null) {
                            Uri uri = data.getData();
                            selectedImages.add(uri);
                        }
                        imageAdapter.notifyDataSetChanged();
                        updateScanButtonState();
                    }
                });
    }

    private void updateScanButtonState() {
        int count = selectedImages.size();
        tvImageCount.setText(count + " pages");
        
        boolean ready = count > 0;
        btnScanAll.setEnabled(ready);
        btnScanAll.setAlpha(ready ? 1.0f : 0.4f);
    }

    // ── Batch Scanning Logic ──────────────────────────────────────────────────

    private void scanAllImages() {
        if (selectedImages.isEmpty()) return;

        // Custom loading dialog
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

        progressBar.setMax(selectedImages.size());
        progressBar.setProgress(0);
        tvCounter.setText("Page 1 of " + selectedImages.size());
        tvPercent.setText("0%");
        batchDialog.show();

        // Accumulate all JSON rows here
        JSONArray allMergedRows = new JSONArray();
        int[] index = {0};
        ArrayList<String> imageLabels = new ArrayList<>();

        fetchDbMembers(() -> processNextSlot(index, allMergedRows, imageLabels, batchDialog,
                progressBar, tvCounter, tvPercent, tvStatus));
    }

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

    private void processNextSlot(
            int[] index,
            JSONArray allMergedRows,
            ArrayList<String> imageLabels,
            Dialog batchDialog,
            com.google.android.material.progressindicator.LinearProgressIndicator progressBar,
            TextView tvCounter,
            TextView tvPercent,
            TextView tvStatus) {

        int total = selectedImages.size();

        if (index[0] >= total) {
            batchDialog.dismiss();
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            openBatchReview(allMergedRows, imageLabels);
            return;
        }

        Uri uri = selectedImages.get(index[0]);
        int current = index[0] + 1;
        int pct = (int) (100f * index[0] / total);
        
        runOnUiThread(() -> {
            progressBar.setProgress(index[0]);
            tvCounter.setText("Page " + current + " of " + total);
            tvPercent.setText(pct + "%");
            tvStatus.setText("Scanning Page " + current + "…");
        });

        try {
            File tmp = uriToTempFile(uri, index[0]);
            if (tmp == null) {
                imageLabels.add("Page " + current);
                index[0]++;
                processNextSlot(index, allMergedRows, imageLabels, batchDialog,
                        progressBar, tvCounter, tvPercent, tvStatus);
                return;
            }

            RequestBody body = RequestBody.create(MediaType.parse("image/jpeg"), tmp);
            MultipartBody.Part part = MultipartBody.Part
                    .createFormData("image", tmp.getName(), body);
                    
            // We pass 1 and 1 to satisfy the API, Gemini will extract all 10 columns anyway.
            RequestBody sundayBody  = RequestBody.create(MediaType.parse("text/plain"), "1");
            RequestBody serviceBody = RequestBody.create(MediaType.parse("text/plain"), "1");

            RetrofitClient.getApiService()
                    .scanImage(part, sundayBody, serviceBody)
                    .enqueue(new Callback<ResponseBody>() {
                        @Override
                        public void onResponse(@NonNull Call<ResponseBody> call,
                                               @NonNull Response<ResponseBody> resp) {
                            if (resp.isSuccessful() && resp.body() != null) {
                                try {
                                    String jsonStr = resp.body().string();
                                    JSONObject obj = new JSONObject(jsonStr);
                                    JSONArray rows = obj.optJSONArray("rows");
                                    if (rows != null) {
                                        // Compute per-image alphabetical label
                                        String firstLetter = null, lastLetter = null;
                                        for (int i = 0; i < rows.length(); i++) {
                                            JSONObject row = rows.getJSONObject(i);
                                            row.put("id", java.util.UUID.randomUUID().toString());
                                            org.json.JSONArray emptyAtt = new org.json.JSONArray();
                                            for (int j = 0; j < 10; j++) emptyAtt.put(false);
                                            row.put("attendance", emptyAtt);
                                            allMergedRows.put(row);
                                            String ln = row.optString("last_name", "").trim().toUpperCase();
                                            if (!ln.isEmpty()) {
                                                String letter = String.valueOf(ln.charAt(0));
                                                if (firstLetter == null || letter.compareTo(firstLetter) < 0) firstLetter = letter;
                                                if (lastLetter == null || letter.compareTo(lastLetter) > 0) lastLetter = letter;
                                            }
                                        }
                                        String label = firstLetter != null
                                                ? (firstLetter.equals(lastLetter) ? firstLetter : firstLetter + " - " + lastLetter)
                                                : "Page " + current;
                                        imageLabels.add(label);
                                    } else {
                                        imageLabels.add("Page " + current);
                                    }
                                } catch (Exception e) {
                                    imageLabels.add("Page " + current);
                                    showToast("Parse error on Page " + current);
                                }
                            } else {
                                imageLabels.add("Page " + current);
                            }
                            //noinspection ResultOfMethodCallIgnored
                            tmp.delete();
                            index[0]++;
                            processNextSlot(index, allMergedRows, imageLabels, batchDialog,
                                    progressBar, tvCounter, tvPercent, tvStatus);
                        }

                        @Override
                        public void onFailure(@NonNull Call<ResponseBody> call,
                                              @NonNull Throwable t) {
                            showToast("Error on Page " + current + ": " + t.getMessage());
                            imageLabels.add("Page " + current);
                            //noinspection ResultOfMethodCallIgnored
                            tmp.delete();
                            index[0]++;
                            processNextSlot(index, allMergedRows, imageLabels, batchDialog,
                                    progressBar, tvCounter, tvPercent, tvStatus);
                        }
                    });

        } catch (Exception e) {
            showToast("Error reading image for Page " + current);
            imageLabels.add("Page " + current);
            index[0]++;
            processNextSlot(index, allMergedRows, imageLabels, batchDialog,
                    progressBar, tvCounter, tvPercent, tvStatus);
        }
    }

    private File uriToTempFile(Uri uri, int idx) {
        try {
            File tmp = new File(getCacheDir(), "batch_bulk_" + idx + "_"
                    + System.currentTimeMillis() + ".jpg");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                if (in == null) return null;
                byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            return tmp;
        } catch (Exception e) {
            return null;
        }
    }

    private void openBatchReview(JSONArray allMergedRows, ArrayList<String> imageLabels) {
        if (allMergedRows.length() == 0) {
            showToast("No attendance data found in the scanned pages.");
            return;
        }
        
        // De-duplicate by last_name + first_name key
        java.util.LinkedHashMap<String, org.json.JSONObject> seen = new java.util.LinkedHashMap<>();
        for (int i = 0; i < allMergedRows.length(); i++) {
            org.json.JSONObject r = allMergedRows.optJSONObject(i);
            if (r == null) continue;
            
            String key = r.optString("last_name", "").trim().toUpperCase() + "|"
                    + r.optString("first_name", "").trim().toUpperCase();
                    
            if (!seen.containsKey(key)) {
                seen.put(key, r);
            } else {
                org.json.JSONObject existing = seen.get(key);
                org.json.JSONArray extAtt = existing.optJSONArray("attendance");
                org.json.JSONArray newAtt = r.optJSONArray("attendance");
                if (extAtt != null && newAtt != null) {
                    org.json.JSONArray mergedAtt = new org.json.JSONArray();
                    for (int j = 0; j < 10; j++) {
                        mergedAtt.put(extAtt.optBoolean(j, false) || newAtt.optBoolean(j, false));
                    }
                    try {
                        existing.put("attendance", mergedAtt);
                    } catch (Exception e) {}
                }
            }
        }
        
        org.json.JSONArray merged = new org.json.JSONArray();
        for (org.json.JSONObject r : seen.values()) {
            merged.put(r);
        }

        NameMatcher.autoCorrectNames(merged, cachedDbMembers);

        try {
            java.io.File tmp = new java.io.File(getCacheDir(), "batch_master_data.json");
            java.io.FileWriter fw = new java.io.FileWriter(tmp);
            fw.write(merged.toString());
            fw.close();
            
            Intent intent = new Intent(this, BatchReviewActivity.class);
            intent.putExtra("json_file_path", tmp.getAbsolutePath());
            intent.putExtra(ReviewActivity.EXTRA_YEAR, selectedCalendar.get(java.util.Calendar.YEAR));
            intent.putExtra(ReviewActivity.EXTRA_MONTH, selectedCalendar.get(java.util.Calendar.MONTH));
            
            boolean[] disabledWeeks = {
                !cbWk1.isChecked(), !cbWk2.isChecked(), !cbWk3.isChecked(), !cbWk4.isChecked(), !cbWk5.isChecked()
            };
            intent.putExtra("disabled_weeks", disabledWeeks);
            
            ArrayList<String> uriStrings = new ArrayList<>();
            for (Uri uri : selectedImages) {
                uriStrings.add(uri.toString());
            }
            intent.putStringArrayListExtra(ImageViewerActivity.EXTRA_IMAGE_URIS, uriStrings);
            intent.putStringArrayListExtra(ImageViewerActivity.EXTRA_IMAGE_LABELS, imageLabels);
            
            startActivity(intent);
        } catch (Exception e) {
            showToast("Error saving temp data.");
        }
    }

    private void showToast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }
}
