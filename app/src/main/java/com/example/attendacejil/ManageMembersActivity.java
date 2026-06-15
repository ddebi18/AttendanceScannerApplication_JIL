package com.example.attendacejil;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.io.File;
import android.content.Intent;
import android.net.Uri;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ManageMembersActivity extends AppCompatActivity {

    private RecyclerView rvMembers;
    private MembersAdapter adapter;
    private final List<JSONObject> allMembersList = new ArrayList<>();
    private final List<JSONObject> membersList = new ArrayList<>();
    private ActivityResultLauncher<PickVisualMediaRequest> launcherGallery;
    private android.widget.ProgressBar progressBar;
    private androidx.appcompat.widget.SearchView searchView;
    private android.content.SharedPreferences prefs;
    private java.util.Set<String> dismissedWarningIds;
    private final java.util.Set<String> memberIdsWithWarnings = new java.util.HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_members);
        
        prefs = getSharedPreferences("ManageMembersPrefs", android.content.Context.MODE_PRIVATE);
        dismissedWarningIds = new java.util.HashSet<>(prefs.getStringSet("dismissed_warnings", new java.util.HashSet<>()));

        rvMembers = findViewById(R.id.rvMembers);
        progressBar = findViewById(R.id.progressBar);
        rvMembers.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MembersAdapter();
        rvMembers.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnSync).setOnClickListener(v -> loadMembers());
        findViewById(R.id.fabAddMember).setOnClickListener(v -> showAddMemberDialog());
        findViewById(R.id.fabExportPdf).setOnClickListener(v -> showExportPdfDialog());

        searchView = findViewById(R.id.searchView);
        if (searchView != null) {
            searchView.setOnQueryTextListener(new androidx.appcompat.widget.SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    filterMembers(query);
                    return true;
                }

                @Override
                public boolean onQueryTextChange(String newText) {
                    filterMembers(newText);
                    return true;
                }
            });
        }
        findViewById(R.id.btnMassScan).setOnClickListener(v -> {
            launcherGallery.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        });

        launcherGallery = registerForActivityResult(new ActivityResultContracts.PickMultipleVisualMedia(20), uris -> {
            if (uris != null && !uris.isEmpty()) {
                processMassScan(uris);
            }
        });

        loadMembers();
    }

    private void processMassScan(List<Uri> uris) {
        Dialog progressDialog = new Dialog(this);
        progressDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        progressDialog.setContentView(R.layout.dialog_batch_scanning);
        if (progressDialog.getWindow() != null) {
            progressDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        progressDialog.setCancelable(false);
        progressDialog.show();
        
        com.google.android.material.progressindicator.LinearProgressIndicator progressBar =
                progressDialog.findViewById(R.id.batchProgressBar);
        TextView tvCounter = progressDialog.findViewById(R.id.tvBatchCounter);
        TextView tvPercent = progressDialog.findViewById(R.id.tvBatchPercent);
        TextView tvStatus  = progressDialog.findViewById(R.id.tvBatchStatus);
        
        progressBar.setMax(uris.size());
        progressBar.setProgress(0);
        tvCounter.setText("Image 1 of " + uris.size());
        tvPercent.setText("0%");

        List<JSONObject> allScannedRows = new ArrayList<>();
        int[] index = {0};

        scanNextUri(uris, index, allScannedRows, progressDialog, progressBar, tvCounter, tvPercent, tvStatus);
    }

    private void scanNextUri(List<Uri> uris, int[] index, List<JSONObject> allScannedRows, Dialog progressDialog,
                             com.google.android.material.progressindicator.LinearProgressIndicator progressBar,
                             TextView tvCounter, TextView tvPercent, TextView tvStatus) {
        
        int total = uris.size();

        if (index[0] >= total) {
            progressDialog.dismiss();
            if (allScannedRows.isEmpty()) {
                showToast("No members found in images.");
                return;
            }
            JSONArray arr = new JSONArray();
            for (JSONObject row : allScannedRows) {
                String ln = row.optString("last_name", "").trim().toLowerCase();
                String fn = row.optString("first_name", "").trim().toLowerCase();
                boolean isDuplicate = false;
                
                // Compare to current members to prevent adding duplicates
                for (JSONObject dbMember : membersList) {
                    String dLn = dbMember.optString("last_name", "").trim().toLowerCase();
                    String dFn = dbMember.optString("first_name", "").trim().toLowerCase();
                    int dist = NameMatcher.levenshtein(ln, dLn) + NameMatcher.levenshtein(fn, dFn);
                    if (dist <= 2) {
                        isDuplicate = true;
                        break;
                    }
                }
                
                if (isDuplicate) {
                    try { row.put("marked_for_deletion", true); } catch (Exception ignored) {}
                }
                arr.put(row);
            }
            
            Intent intent = new Intent(this, BulkAddReviewActivity.class);
            intent.putExtra(BulkAddReviewActivity.EXTRA_JSON_DATA, arr.toString());
            startActivity(intent);
            return;
        }

        int current = index[0] + 1;
        int pct = (int) (100f * index[0] / total);
        runOnUiThread(() -> {
            progressBar.setProgress(index[0]);
            tvCounter.setText("Image " + current + " of " + total);
            tvPercent.setText(pct + "%");
            tvStatus.setText("Scanning Image " + current + "…");
        });

        try {
            Uri uri = uris.get(index[0]);
            File tmp = new File(getCacheDir(), "mass_scan_" + index[0] + ".jpg");
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                if (in != null) {
                    byte[] buf = new byte[4096]; int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
            }

            okhttp3.MultipartBody.Part filePart = okhttp3.MultipartBody.Part.createFormData(
                    "image", tmp.getName(),
                    RequestBody.create(okhttp3.MediaType.parse("image/jpeg"), tmp));

            RequestBody dummyNum = RequestBody.create(okhttp3.MediaType.parse("text/plain"), "1");

            RetrofitClient.getApiService().scanImage(filePart, dummyNum, dummyNum)
                    .enqueue(new Callback<okhttp3.ResponseBody>() {
                        @Override
                        public void onResponse(@NonNull Call<okhttp3.ResponseBody> call, @NonNull Response<okhttp3.ResponseBody> response) {
                            try {
                                if (response.isSuccessful() && response.body() != null) {
                                    String json = response.body().string();
                                    JSONObject root = new JSONObject(json);
                                    JSONArray rows = root.optJSONArray("rows");
                                    if (rows != null) {
                                        for (int i = 0; i < rows.length(); i++) {
                                            allScannedRows.add(rows.getJSONObject(i));
                                        }
                                    }
                                }
                            } catch (Exception ignored) {}
                            index[0]++;
                            scanNextUri(uris, index, allScannedRows, progressDialog, progressBar, tvCounter, tvPercent, tvStatus);
                        }
                        @Override
                        public void onFailure(@NonNull Call<okhttp3.ResponseBody> call, @NonNull Throwable t) {
                            index[0]++;
                            scanNextUri(uris, index, allScannedRows, progressDialog, progressBar, tvCounter, tvPercent, tvStatus);
                        }
                    });
        } catch (Exception e) {
            index[0]++;
            scanNextUri(uris, index, allScannedRows, progressDialog, progressBar, tvCounter, tvPercent, tvStatus);
        }
    }

    private void loadMembers() {
        if (SupabaseClient.getApiService() == null) {
            showToast("Supabase not configured. Please go to DB Settings.");
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        rvMembers.setVisibility(View.GONE);

        SupabaseClient.getApiService().getMembers().enqueue(new Callback<String>() {
            @Override
            public void onResponse(@NonNull Call<String> call, @NonNull Response<String> response) {
                progressBar.setVisibility(View.GONE);
                rvMembers.setVisibility(View.VISIBLE);
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        JSONArray arr = new JSONArray(response.body());
                        allMembersList.clear();
                        for (int i = 0; i < arr.length(); i++) {
                            allMembersList.add(arr.getJSONObject(i));
                        }
                        
                        memberIdsWithWarnings.clear();
                        for (int i = 0; i < allMembersList.size(); i++) {
                            JSONObject m1 = allMembersList.get(i);
                            String id1 = m1.optString("id");
                            String fn1 = m1.optString("first_name", "").toLowerCase().trim();
                            if (fn1.isEmpty()) continue;
                            
                            for (int j = i + 1; j < allMembersList.size(); j++) {
                                JSONObject m2 = allMembersList.get(j);
                                String id2 = m2.optString("id");
                                String fn2 = m2.optString("first_name", "").toLowerCase().trim();
                                if (fn2.isEmpty()) continue;
                                
                                int dist = NameMatcher.levenshtein(fn1, fn2);
                                if (dist == 0 || dist == 1 || (dist == 2 && fn1.length() >= 5 && fn2.length() >= 5)) {
                                    if (!dismissedWarningIds.contains(id1)) memberIdsWithWarnings.add(id1);
                                    if (!dismissedWarningIds.contains(id2)) memberIdsWithWarnings.add(id2);
                                }
                            }
                        }
                        // Sort alphabetically
                        allMembersList.sort((a, b) -> {
                            String nameA = a.optString("last_name", "") + a.optString("first_name", "");
                            String nameB = b.optString("last_name", "") + b.optString("first_name", "");
                            return nameA.compareToIgnoreCase(nameB);
                        });
                        filterMembers(searchView != null ? searchView.getQuery().toString() : "");
                    } catch (Exception e) {
                        showToast("Error parsing members: " + e.getMessage());
                    }
                } else {
                    showToast("Failed to load members: " + response.code());
                }
            }

            @Override
            public void onFailure(@NonNull Call<String> call, @NonNull Throwable t) {
                progressBar.setVisibility(View.GONE);
                rvMembers.setVisibility(View.VISIBLE);
                showToast("Network error: " + t.getMessage());
            }
        });
    }

    private void showExportPdfDialog() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        new android.app.DatePickerDialog(this, (view, y, m, d) -> {
            exportPdfForMonth(y, m);
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), 1).show();
    }

    private void exportPdfForMonth(int targetYear, int targetMonth) {
        if (SupabaseClient.getApiService() == null) {
            showToast("Supabase not configured.");
            return;
        }

        int prevYear = targetYear;
        int prevMonth = targetMonth - 1;
        if (prevMonth < 0) {
            prevMonth = 11;
            prevYear--;
        }

        progressBar.setVisibility(View.VISIBLE);
        
        SupabaseClient.getApiService().getAttendance("eq." + prevYear, "eq." + prevMonth).enqueue(new Callback<String>() {
            @Override
            public void onResponse(@NonNull Call<String> call, @NonNull Response<String> response) {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        JSONArray attArr = new JSONArray(response.body());
                        List<JSONObject> validatedMembers = new ArrayList<>();

                        for (JSONObject member : allMembersList) {
                            String memberId = member.optString("id", "");
                            boolean attended = false;

                            for (int i = 0; i < attArr.length(); i++) {
                                JSONObject attObj = attArr.getJSONObject(i);
                                if (memberId.equals(attObj.optString("member_id", ""))) {
                                    if (attObj.optBoolean("wk1", false) ||
                                        attObj.optBoolean("wk2", false) ||
                                        attObj.optBoolean("wk3", false) ||
                                        attObj.optBoolean("wk4", false) ||
                                        attObj.optBoolean("wk5", false)) {
                                        attended = true;
                                        break;
                                    }
                                }
                            }

                            if (attended) {
                                validatedMembers.add(member);
                            }
                        }

                        if (validatedMembers.isEmpty()) {
                            progressBar.setVisibility(View.GONE);
                            showToast("No members attended in the previous month.");
                            return;
                        }

                        PdfGenerator.generateAttendancePdf(ManageMembersActivity.this, targetYear, targetMonth, validatedMembers, new PdfGenerator.PdfCallback() {
                            @Override
                            public void onSuccess(Uri fileUri) {
                                progressBar.setVisibility(View.GONE);
                                showToast("PDF Saved to Downloads!");
                                Intent intent = new Intent(Intent.ACTION_VIEW);
                                intent.setDataAndType(fileUri, "application/pdf");
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                try {
                                    startActivity(intent);
                                } catch (Exception e) {
                                    // No PDF viewer
                                }
                            }

                            @Override
                            public void onError(String error) {
                                progressBar.setVisibility(View.GONE);
                                showToast("Error generating PDF: " + error);
                            }
                        });

                    } catch (Exception e) {
                        progressBar.setVisibility(View.GONE);
                        showToast("Error parsing attendance: " + e.getMessage());
                    }
                } else {
                    progressBar.setVisibility(View.GONE);
                    showToast("Failed to fetch previous month attendance: " + response.code());
                }
            }

            @Override
            public void onFailure(@NonNull Call<String> call, @NonNull Throwable t) {
                progressBar.setVisibility(View.GONE);
                showToast("Network error: " + t.getMessage());
            }
        });
    }

    private void showAddMemberDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_add_member);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        TextInputEditText etLastName = dialog.findViewById(R.id.etLastName);
        TextInputEditText etFirstName = dialog.findViewById(R.id.etFirstName);
        TextInputEditText etNetwork = dialog.findViewById(R.id.etNetwork);

        dialog.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.btnAdd).setOnClickListener(v -> {
            String ln  = AttendanceRow.normalize(etLastName.getText()  != null ? etLastName.getText().toString().trim()  : "");
            String fn  = AttendanceRow.normalize(etFirstName.getText() != null ? etFirstName.getText().toString().trim() : "");
            String net = etNetwork.getText() != null ? etNetwork.getText().toString().trim() : "";

            if (ln.isEmpty() || fn.isEmpty()) {
                showToast("Name fields cannot be empty");
                return;
            }

            // Check for duplicates in the current DB member list
            JSONObject dup = findDuplicateMember(fn, ln);
            if (dup != null) {
                String dupName = dup.optString("last_name", "") + ", " + dup.optString("first_name", "");
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.AttendanceDialogTheme)
                        .setTitle("⚠️ Possible Duplicate")
                        .setMessage("\"" + ln + ", " + fn + "\" looks similar to an existing member:\n\n• " + dupName
                                + "\n\nDo you want to add anyway, or view the existing member?")
                        .setPositiveButton("Add Anyway", (d, w) -> {
                            dialog.dismiss();
                            addMemberToDb(ln, fn, net);
                        })
                        .setNegativeButton("Show Duplicate", (d, w) -> {
                            dialog.dismiss();
                            scrollToMember(dup);
                        })
                        .setNeutralButton("Cancel", null)
                        .show();
                return;
            }

            addMemberToDb(ln, fn, net);
            dialog.dismiss();
        });

        dialog.show();
    }

    /** Fuzzy search for a member matching the given first+last name (Levenshtein distance ≤ 2). */
    private JSONObject findDuplicateMember(String first, String last) {
        String ln = last.toLowerCase();
        String fn = first.toLowerCase();
        for (JSONObject m : allMembersList) {
            int dist = NameMatcher.levenshtein(ln, m.optString("last_name", "").toLowerCase())
                     + NameMatcher.levenshtein(fn, m.optString("first_name", "").toLowerCase());
            if (dist <= 2) return m;
        }
        return null;
    }

    /** Scroll to and flash-highlight a member card in the list. */
    private void scrollToMember(JSONObject target) {
        // Clear search so the member is visible
        if (searchView != null && !searchView.getQuery().toString().isEmpty()) {
            searchView.setQuery("", true);
        }
        int pos = membersList.indexOf(target);
        if (pos < 0) {
            filterMembers("");
            pos = membersList.indexOf(target);
        }
        if (pos < 0) return;
        final int finalPos = pos;
        rvMembers.smoothScrollToPosition(finalPos);
        rvMembers.postDelayed(() -> {
            androidx.recyclerview.widget.RecyclerView.ViewHolder vh =
                    rvMembers.findViewHolderForAdapterPosition(finalPos);
            if (vh != null) {
                android.view.View card = vh.itemView;
                android.animation.ArgbEvaluator evaluator = new android.animation.ArgbEvaluator();
                android.animation.ValueAnimator anim = android.animation.ValueAnimator
                        .ofObject(evaluator, 0xFFFF6F00, 0xFF1C1C2E);
                anim.setDuration(1200);
                anim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                anim.setRepeatCount(1);
                anim.addUpdateListener(a -> card.setBackgroundColor((int) a.getAnimatedValue()));
                anim.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator animation) {
                        card.setBackground(null);
                    }
                });
                anim.start();
            }
        }, 400);
    }

    private void addMemberToDb(String lastName, String firstName, String networkName) {
        if (SupabaseClient.getApiService() == null) {
            showToast("Supabase not configured.");
            return;
        }

        try {
            // In a full 3NF, we would check if Network exists, insert if not, get Network ID, then insert Member.
            // For simplicity here, we insert Member. If we need 3NF, we should do the network lookup first.
            // Let's assume we just add the member with a null network_id for now, or the DB has a trigger/handle.
            // Actually, a simpler 3NF: Member has network_name if we don't strictly use UUIDs for networks.
            // Let's just insert to Members with first_name, last_name.
            JSONObject member = new JSONObject();
            member.put("first_name", firstName);
            member.put("last_name", lastName);
            member.put("network_name", networkName);
            
            RequestBody body = RequestBody.create(MediaType.parse("application/json"), member.toString());
            SupabaseClient.getApiService().addMember("return=representation", body).enqueue(new Callback<String>() {
                @Override
                public void onResponse(@NonNull Call<String> call, @NonNull Response<String> response) {
                    if (response.isSuccessful()) {
                        showToast("Member added to DB!");
                        loadMembers();
                    } else {
                        showToast("Failed to add: " + response.code());
                    }
                }

                @Override
                public void onFailure(@NonNull Call<String> call, @NonNull Throwable t) {
                    showToast("Error adding member: " + t.getMessage());
                }
            });
        } catch (Exception e) {
            showToast("Error: " + e.getMessage());
        }
    }

    private void showToast(String msg) {
        runOnUiThread(() -> Toast.makeText(ManageMembersActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    private class MembersAdapter extends RecyclerView.Adapter<MembersAdapter.MemberViewHolder> {

        @NonNull
        @Override
        public MemberViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_member_row, parent, false);
            return new MemberViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull MemberViewHolder holder, int position) {
            JSONObject member = membersList.get(position);
            String name = member.optString("last_name", "").toUpperCase() + ", " + member.optString("first_name", "");
            holder.tvMemberName.setText(name);
            
            String netName = member.optString("network_name", "").trim();
            if (!netName.isEmpty()) {
                holder.tvMemberNetwork.setText(netName);
            } else {
                holder.tvMemberNetwork.setText("No Network");
            }

            String id = member.optString("id");
            if (memberIdsWithWarnings.contains(id)) {
                holder.tvDuplicateWarning.setVisibility(View.VISIBLE);
                holder.tvDuplicateWarning.setText("⚠ Similar First Name (Tap to dismiss)");
                holder.tvDuplicateWarning.setOnClickListener(v -> {
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(ManageMembersActivity.this)
                            .setTitle("Dismiss Warning")
                            .setMessage("Hide the similar name warning for " + member.optString("first_name") + "?")
                            .setPositiveButton("Dismiss", (dialog, which) -> {
                                dismissedWarningIds.add(id);
                                prefs.edit().putStringSet("dismissed_warnings", dismissedWarningIds).apply();
                                memberIdsWithWarnings.remove(id);
                                notifyItemChanged(position);
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                });
            } else {
                holder.tvDuplicateWarning.setVisibility(View.GONE);
                holder.tvDuplicateWarning.setOnClickListener(null);
            }
            
            holder.itemView.setOnClickListener(v -> {
                showEditMemberDialog(member);
            });
            holder.btnEdit.setOnClickListener(v -> {
                showEditMemberDialog(member);
            });
            holder.btnDelete.setOnClickListener(v -> {
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(ManageMembersActivity.this)
                        .setTitle("Delete Member")
                        .setMessage("Are you sure you want to delete " + name + "?")
                        .setPositiveButton("Delete", (dialog, which) -> {
                            deleteMemberFromDb(member.optString("id", ""));
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }

        @Override
        public int getItemCount() {
            return membersList.size();
        }

        class MemberViewHolder extends RecyclerView.ViewHolder {
            TextView tvMemberName, tvMemberNetwork, tvDuplicateWarning;
            android.widget.ImageButton btnEdit, btnDelete;

            MemberViewHolder(@NonNull View itemView) {
                super(itemView);
                tvMemberName = itemView.findViewById(R.id.tvMemberName);
                tvMemberNetwork = itemView.findViewById(R.id.tvMemberNetwork);
                tvDuplicateWarning = itemView.findViewById(R.id.tvDuplicateWarning);
                btnEdit = itemView.findViewById(R.id.btnEdit);
                btnDelete = itemView.findViewById(R.id.btnDelete);
            }
        }
    }

    private void filterMembers(String query) {
        String lowerQuery = query.toLowerCase().trim();
        membersList.clear();
        for (JSONObject member : allMembersList) {
            String ln = member.optString("last_name", "").toLowerCase();
            String fn = member.optString("first_name", "").toLowerCase();
            if (lowerQuery.isEmpty() || ln.contains(lowerQuery) || fn.contains(lowerQuery)) {
                membersList.add(member);
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void showEditMemberDialog(JSONObject member) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_add_member);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        
        TextInputEditText etLastName = dialog.findViewById(R.id.etLastName);
        TextInputEditText etFirstName = dialog.findViewById(R.id.etFirstName);
        TextInputEditText etNetwork = dialog.findViewById(R.id.etNetwork);
        Button btnAdd = dialog.findViewById(R.id.btnAdd);
        btnAdd.setText("Save");

        etLastName.setText(member.optString("last_name", ""));
        etFirstName.setText(member.optString("first_name", ""));
        etNetwork.setText(member.optString("network_name", ""));

        dialog.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        btnAdd.setOnClickListener(v -> {
            String ln  = AttendanceRow.normalize(etLastName.getText()  != null ? etLastName.getText().toString().trim()  : "");
            String fn  = AttendanceRow.normalize(etFirstName.getText() != null ? etFirstName.getText().toString().trim() : "");
            String net = etNetwork.getText() != null ? etNetwork.getText().toString().trim() : "";

            if (ln.isEmpty() || fn.isEmpty()) {
                showToast("Name fields cannot be empty");
                return;
            }

            updateMemberInDb(member.optString("id", ""), ln, fn, net);
            dialog.dismiss();
        });

        dialog.show();
    }

    private void updateMemberInDb(String id, String lastName, String firstName, String networkName) {
        if (SupabaseClient.getApiService() == null) {
            showToast("Supabase not configured.");
            return;
        }

        try {
            JSONObject member = new JSONObject();
            member.put("first_name", firstName);
            member.put("last_name", lastName);
            member.put("network_name", networkName);
            
            RequestBody body = RequestBody.create(MediaType.parse("application/json"), member.toString());
            SupabaseClient.getApiService().updateMember("eq." + id, body).enqueue(new Callback<String>() {
                @Override
                public void onResponse(@NonNull Call<String> call, @NonNull Response<String> response) {
                    if (response.isSuccessful()) {
                        showToast("Member updated!");
                        loadMembers();
                    } else {
                        showToast("Failed to update: " + response.code());
                    }
                }

                @Override
                public void onFailure(@NonNull Call<String> call, @NonNull Throwable t) {
                    showToast("Error updating member: " + t.getMessage());
                }
            });
        } catch (Exception e) {
            showToast("Error: " + e.getMessage());
        }
    }

    private void deleteMemberFromDb(String id) {
        if (SupabaseClient.getApiService() == null) {
            showToast("Supabase not configured.");
            return;
        }

        SupabaseClient.getApiService().deleteMember("eq." + id).enqueue(new Callback<String>() {
            @Override
            public void onResponse(@NonNull Call<String> call, @NonNull Response<String> response) {
                if (response.isSuccessful()) {
                    showToast("Member deleted!");
                    loadMembers();
                } else {
                    showToast("Failed to delete: " + response.code());
                }
            }

            @Override
            public void onFailure(@NonNull Call<String> call, @NonNull Throwable t) {
                showToast("Error deleting member: " + t.getMessage());
            }
        });
    }
}
