package com.example.attendacejil;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ReviewFragment extends Fragment implements ReviewAdapter.OnDataChangedListener {

    private static final String ARG_JSON_PATH = "rows_json_path";
    private static final String ARG_SUNDAY = "sunday_num";
    private static final String ARG_SERVICE = "service_num";
    private static final String ARG_FLAGGED = "flagged_count";

    private RecyclerView recyclerView;
    private ReviewAdapter adapter;
    private Button btnAddRow, btnPurgeDeleted;
    private LinearLayout bannerFlagged;
    private TextView tvBannerText, tvAttHeader;
    private View emptyStateLayout;

    private final List<AttendanceRow> rows = new ArrayList<>();
    private int sundayNum = 1;
    private int serviceNum = 1;

    // Optional interface to tell Activity that data changed (so it can re-check Export button)
    public interface OnFragmentDataChangedListener {
        void onFragmentDataChanged();
    }
    private OnFragmentDataChangedListener fragmentDataChangedListener;

    public static ReviewFragment newInstance(String json, int sunday, int service, int flaggedCount) {
        ReviewFragment fragment = new ReviewFragment();
        Bundle args = new Bundle();
        args.putString(ARG_JSON_PATH, json);
        args.putInt(ARG_SUNDAY, sunday);
        args.putInt(ARG_SERVICE, service);
        args.putInt(ARG_FLAGGED, flaggedCount);
        fragment.setArguments(args);
        return fragment;
    }

    public void setOnFragmentDataChangedListener(OnFragmentDataChangedListener listener) {
        this.fragmentDataChangedListener = listener;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            sundayNum = getArguments().getInt(ARG_SUNDAY);
            serviceNum = getArguments().getInt(ARG_SERVICE);
        }
        
        if (savedInstanceState != null && savedInstanceState.containsKey("saved_rows_file")) {
            // Restore from saved state file (process death recovery)
            String path = savedInstanceState.getString("saved_rows_file");
            if (path != null) {
                parseRowsJsonWithState(readStringFromFile(path));
            }
        } else if (getArguments() != null) {
            String path = getArguments().getString(ARG_JSON_PATH);
            if (path != null) {
                parseRowsJson(readStringFromFile(path));
            }
        }
    }

    private String readStringFromFile(String path) {
        try {
            java.io.File f = new java.io.File(path);
            if (!f.exists()) return "[]";
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return "[]";
        }
    }
    
    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (AttendanceRow r : rows) {
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("last_name", r.lastName);
                obj.put("last_name_conf", r.lastNameConf);
                obj.put("first_name", r.firstName);
                obj.put("first_name_conf", r.firstNameConf);
                obj.put("network", r.network);
                obj.put("network_conf", r.networkConf);
                obj.put("flagged", r.flagged);
                obj.put("manually_added", r.manuallyAdded);
                obj.put("marked_for_deletion", r.markedForDeletion);
                org.json.JSONArray attArr = new org.json.JSONArray();
                for (boolean b : r.attendance) attArr.put(b);
                obj.put("attendance", attArr);
                arr.put(obj);
            }
            java.io.File tmp = new java.io.File(requireContext().getCacheDir(), "frag_saved_" + sundayNum + "_" + serviceNum + ".json");
            java.io.FileWriter fw = new java.io.FileWriter(tmp);
            fw.write(arr.toString());
            fw.close();
            outState.putString("saved_rows_file", tmp.getAbsolutePath());
        } catch (Exception e) {
            // Ignore
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_review, container, false);

        recyclerView = v.findViewById(R.id.reviewRecyclerView);
        btnAddRow = v.findViewById(R.id.btnAddRow);
        btnPurgeDeleted = v.findViewById(R.id.btnPurgeDeleted);
        bannerFlagged = v.findViewById(R.id.bannerFlagged);
        tvBannerText = v.findViewById(R.id.tvBannerText);
        tvAttHeader = v.findViewById(R.id.tvAttHeader);
        emptyStateLayout = v.findViewById(R.id.emptyStateLayout);

        setupRecyclerView();
        setupButtons();
        refreshBanner();

        return v;
    }

    private int selectedColumn() {
        return (sundayNum - 1) * 2 + (serviceNum - 1);
    }

    private String sessionLabel() {
        String svc = serviceNum == 1 ? "1ST" : "2ND";
        return "WK" + sundayNum + " " + svc;
    }

    private void parseRowsJson(String json) {
        rows.clear();
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
            // Error parsing
        }
    }
    
    private void parseRowsJsonWithState(String json) {
        rows.clear();
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
                
                AttendanceRow row = new AttendanceRow(
                        lastName, lastConf,
                        firstName, firstConf,
                        network, netConf,
                        att, flagged);
                row.manuallyAdded = obj.optBoolean("manually_added", false);
                row.markedForDeletion = obj.optBoolean("marked_for_deletion", false);
                rows.add(row);
            }
        } catch (Exception e) {
            // Error parsing
        }
    }

    private void setupRecyclerView() {
        adapter = new ReviewAdapter(requireContext(), rows, this, selectedColumn());
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        if (tvAttHeader != null) tvAttHeader.setText(sessionLabel());
        updateEmptyState();
    }

    private void setupButtons() {
        btnAddRow.setOnClickListener(v -> {
            rows.add(new AttendanceRow());
            adapter.notifyItemInserted(rows.size() - 1);
            recyclerView.scrollToPosition(rows.size() - 1);
            updateEmptyState();
            refreshBanner();
        });

        btnPurgeDeleted.setOnClickListener(v -> {
            rows.removeIf(r -> r.markedForDeletion);
            adapter.notifyDataSetChanged();
            updateEmptyState();
            refreshBanner();
        });
    }

    private void updateEmptyState() {
        long active = rows.stream().filter(r -> !r.markedForDeletion).count();
        emptyStateLayout.setVisibility(active == 0 ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onDataChanged() {
        updateEmptyState();
        refreshBanner();
        if (fragmentDataChangedListener != null) {
            fragmentDataChangedListener.onFragmentDataChanged();
        }
    }

    private void refreshBanner() {
        int flaggedCount = 0;
        boolean hasDeleted = false;

        for (AttendanceRow r : rows) {
            if (r.markedForDeletion) { hasDeleted = true; continue; }
            if (r.flagged) flaggedCount++;
        }

        bannerFlagged.setVisibility(View.VISIBLE);
        if (flaggedCount > 0) {
            tvBannerText.setText("⚠ " + flaggedCount + " name(s) highlighted for review (yellow)");
        } else {
            tvBannerText.setText("Tap ✓ on each person who attended");
        }

        btnPurgeDeleted.setVisibility(hasDeleted ? View.VISIBLE : View.GONE);
    }

    // Used by Activity to check if this tab is ready for export
    public boolean canExport() {
        int flaggedCount = 0;
        boolean anyInvalid = false;

        for (AttendanceRow r : rows) {
            if (r.markedForDeletion) continue;
            if (r.flagged) flaggedCount++;
            if (r.lastName.trim().isEmpty() || r.firstName.trim().isEmpty() || r.network.trim().isEmpty()) {
                anyInvalid = true;
            }
        }

        long activeRows = rows.stream().filter(r -> !r.markedForDeletion).count();
        // Flagged rows are allowed — they are just highlighted yellow for visual review
        return activeRows == 0 || !anyInvalid;
    }

    // Return current rows to export
    public List<AttendanceRow> getRows() {
        return rows;
    }
}
