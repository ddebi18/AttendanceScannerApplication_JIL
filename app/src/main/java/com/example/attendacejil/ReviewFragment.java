package com.example.attendacejil;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
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
    private View btnPurgeDeleted;
    private com.google.android.material.floatingactionbutton.FloatingActionButton fabAddMember;
    private LinearLayout bannerFlagged;
    private TextView tvBannerText, tvAttHeader;
    private View emptyStateLayout;

    private final List<AttendanceRow> allRows = new ArrayList<>();
    private final List<AttendanceRow> rows = new ArrayList<>();
    private String currentQuery = "";
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
            for (AttendanceRow r : allRows) {
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("id", r.id);
                obj.put("last_name", r.lastName);
                obj.put("last_name_conf", r.lastNameConf);
                obj.put("first_name", r.firstName);
                obj.put("first_name_conf", r.firstNameConf);
                obj.put("db_id", r.db_id);
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

    public void saveState() {
        onSaveInstanceState(new Bundle());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_review, container, false);

        recyclerView = v.findViewById(R.id.reviewRecyclerView);
        fabAddMember = v.findViewById(R.id.fabAddMember);
        btnPurgeDeleted = v.findViewById(R.id.btnPurgeDeleted);
        bannerFlagged = v.findViewById(R.id.bannerFlagged);
        tvBannerText = v.findViewById(R.id.tvBannerText);
        tvAttHeader = v.findViewById(R.id.tvAttHeader);
        emptyStateLayout = v.findViewById(R.id.emptyStateLayout);

        setupRecyclerView(v);
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
        allRows.clear();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);

                String id         = obj.optString("id", java.util.UUID.randomUUID().toString());
                String lastName   = obj.optString("last_name",  "");
                int    lastConf   = obj.optInt   ("last_name_conf",  0);
                String firstName  = obj.optString("first_name", "");
                int    firstConf  = obj.optInt   ("first_name_conf", 0);
                String db_id      = obj.optString("db_id", null);
                String network    = obj.optString("network",    "");
                int    netConf    = obj.optInt   ("network_conf",    0);
                boolean flagged   = obj.optBoolean("flagged",   false);

                boolean[] att = new boolean[10];
                // Ignored backend attendance to force manual check:
                // JSONArray attArr = obj.optJSONArray("attendance");
                // if (attArr != null) {
                //     for (int j = 0; j < Math.min(attArr.length(), 10); j++) {
                //         att[j] = attArr.optBoolean(j, false);
                //     }
                // }

                AttendanceRow row = new AttendanceRow(
                        id,
                        lastName, lastConf,
                        firstName, firstConf,
                        network, netConf,
                        att, flagged);
                row.db_id = db_id;
                allRows.add(row);
            }
            filterRows("");
        } catch (Exception e) {
            // Error parsing
        }
    }
    
    private void parseRowsJsonWithState(String json) {
        allRows.clear();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                
                String id         = obj.optString("id", java.util.UUID.randomUUID().toString());
                String lastName   = obj.optString("last_name",  "");
                int    lastConf   = obj.optInt   ("last_name_conf",  0);
                String firstName  = obj.optString("first_name", "");
                int    firstConf  = obj.optInt   ("first_name_conf", 0);
                String db_id      = obj.optString("db_id", null);
                String network    = obj.optString("network",    "");
                int    netConf    = obj.optInt   ("network_conf",    0);
                boolean flagged   = obj.optBoolean("flagged",   false);
                
                boolean[] att = new boolean[10];
                // Ignored backend attendance to force manual check:
                // JSONArray attArr = obj.optJSONArray("attendance");
                // if (attArr != null) {
                //     for (int j = 0; j < Math.min(attArr.length(), 10); j++) {
                //         att[j] = attArr.optBoolean(j, false);
                //     }
                // }
                
                AttendanceRow row = new AttendanceRow(
                        id,
                        lastName, lastConf,
                        firstName, firstConf,
                        network, netConf,
                        att, flagged);
                row.db_id = db_id;
                row.manuallyAdded = obj.optBoolean("manually_added", false);
                row.markedForDeletion = obj.optBoolean("marked_for_deletion", false);
                allRows.add(row);
            }
            filterRows("");
        } catch (Exception e) {
            // Error parsing
        }
    }

    private void setupRecyclerView(View v) {
        adapter = new ReviewAdapter(requireContext(), rows, this, selectedColumn());
        adapter.setOnRowEditedListener(new ReviewAdapter.OnRowEditedListener() {
            @Override
            public void onRowEditedGlobally(String id, String newLast, String newFirst, String newNet) {
                if (getActivity() instanceof BatchReviewActivity) {
                    ((BatchReviewActivity) getActivity()).onRowEditedGlobally(id, newLast, newFirst, newNet);
                }
                if (fragmentDataChangedListener != null) {
                    fragmentDataChangedListener.onFragmentDataChanged();
                }
            }

            @Override
            public void onRowAddedGlobally(String id, String lastName, String firstName, String network) {
                if (getActivity() instanceof BatchReviewActivity) {
                    ((BatchReviewActivity) getActivity()).onRowAddedGlobally(id, lastName, firstName, network);
                }
                if (fragmentDataChangedListener != null) {
                    fragmentDataChangedListener.onFragmentDataChanged();
                }
            }

            @Override
            public void onAttendanceToggledGlobally(String id, int colIdx, boolean isPresent) {
                if (getActivity() instanceof BatchReviewActivity) {
                    ((BatchReviewActivity) getActivity()).onAttendanceToggledGlobally(id, colIdx, isPresent);
                }
            }
        });
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);
        recyclerView.addOnItemTouchListener(new DragSelectTouchListener());

        AlphabetIndexScroller scroller = v.findViewById(R.id.alphabetScroller);
        if (scroller != null) {
            scroller.setRecyclerView(recyclerView, adapter);
        }

        androidx.appcompat.widget.SearchView searchView = v.findViewById(R.id.searchView);
        if (searchView != null) {
            searchView.setOnQueryTextListener(new androidx.appcompat.widget.SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    filterRows(query);
                    return true;
                }
                @Override
                public boolean onQueryTextChange(String newText) {
                    filterRows(newText);
                    return true;
                }
            });
        }

        if (tvAttHeader != null) tvAttHeader.setText(sessionLabel());
        updateEmptyState();
    }

    private void filterRows(String query) {
        currentQuery = query == null ? "" : query.trim().toLowerCase(java.util.Locale.getDefault());
        rows.clear();
        for (AttendanceRow r : allRows) {
            if (currentQuery.isEmpty() ||
                r.lastName.toLowerCase(java.util.Locale.getDefault()).contains(currentQuery) ||
                r.firstName.toLowerCase(java.util.Locale.getDefault()).contains(currentQuery)) {
                rows.add(r);
            }
        }
        java.util.Collections.sort(rows, (r1, r2) -> {
            int cmp = r1.lastName.compareToIgnoreCase(r2.lastName);
            if (cmp == 0) return r1.firstName.compareToIgnoreCase(r2.firstName);
            return cmp;
        });
        if (adapter != null) adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void setupButtons() {
        if (fabAddMember != null) {
            fabAddMember.setOnClickListener(v -> showAddMemberDialog());
        }

        btnPurgeDeleted.setOnClickListener(v -> {
            allRows.removeIf(r -> r.markedForDeletion);
            filterRows(currentQuery);
            refreshBanner();
        });
    }

    private void showAddMemberDialog() {
        android.app.Dialog dialog = new android.app.Dialog(requireContext());
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_add_member);
        dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        com.google.android.material.textfield.TextInputEditText etFirst = dialog.findViewById(R.id.etFirstName);
        com.google.android.material.textfield.TextInputEditText etLast = dialog.findViewById(R.id.etLastName);
        com.google.android.material.textfield.TextInputEditText etNet = dialog.findViewById(R.id.etNetwork);
        
        dialog.findViewById(R.id.btnCancel).setOnClickListener(view -> dialog.dismiss());
        dialog.findViewById(R.id.btnAdd).setOnClickListener(view -> {
            String first = etFirst.getText().toString().trim();
            String last = etLast.getText().toString().trim();
            String net = etNet.getText().toString().trim();
            if (first.isEmpty() || last.isEmpty() || net.isEmpty()) {
                android.widget.Toast.makeText(requireContext(), "Please fill in all fields", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            
            String newId = java.util.UUID.randomUUID().toString();
            AttendanceRow newRow = new AttendanceRow(newId, last, 100, first, 100, net, 100, new boolean[10], false);
            newRow.manuallyAdded = true;
            
            addRowGlobally(newRow);
            
            if (getActivity() instanceof BatchReviewActivity) {
                ((BatchReviewActivity) getActivity()).onRowAddedGlobally(newId, last, first, net);
            }
            dialog.dismiss();
        });
        dialog.show();
    }

    public void addRowGlobally(AttendanceRow row) {
        for (AttendanceRow r : allRows) {
            if (r.id != null && r.id.equals(row.id)) return;
        }
        allRows.add(row);
        filterRows(currentQuery);
        refreshBanner();
    }

    public void addRowGlobally(String id, String lastName, String firstName, String network) {
        // Skip if already exists
        for (AttendanceRow r : allRows) {
            if (r.id != null && r.id.equals(id)) return;
        }
        AttendanceRow newRow = new AttendanceRow(id, lastName, 100, firstName, 100, network, 100, new boolean[10], false);
        newRow.manuallyAdded = true;
        addRowGlobally(newRow);
    }

    private void updateEmptyState() {
        long active = allRows.stream().filter(r -> !r.markedForDeletion).count();
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

        for (AttendanceRow r : allRows) {
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

    public boolean canExport() {
        int flaggedCount = 0;
        boolean anyInvalid = false;

        for (AttendanceRow r : allRows) {
            if (r.markedForDeletion) continue;
            if (r.flagged) flaggedCount++;
            if (r.lastName.trim().isEmpty() || r.firstName.trim().isEmpty() || r.network.trim().isEmpty()) {
                anyInvalid = true;
            }
        }

        long activeRows = allRows.stream().filter(r -> !r.markedForDeletion).count();
        return activeRows == 0 || !anyInvalid;
    }

    public List<AttendanceRow> getRows() {
        return rows;
    }

    public void updateRowGlobally(String id, String newLastName, String newFirstName, String newNetwork) {
        for (int i = 0; i < allRows.size(); i++) {
            if (allRows.get(i).id != null && allRows.get(i).id.equals(id)) {
                allRows.get(i).lastName  = newLastName;
                allRows.get(i).firstName = newFirstName;
                allRows.get(i).network   = newNetwork;
                allRows.get(i).flagged   = false;
                
                filterRows(currentQuery);
                break;
            }
        }
    }

    public void updateAttendanceGlobally(String id, int colIdx, boolean isPresent) {
        if (allRows == null) return;
        for (int i = 0; i < allRows.size(); i++) {
            AttendanceRow r = allRows.get(i);
            if (r.id != null && r.id.equals(id)) {
                r.attendance[colIdx] = isPresent;
                if (colIdx == selectedColumn() && adapter != null) {
                    for (int j = 0; j < rows.size(); j++) {
                        if (rows.get(j).id.equals(id)) {
                            adapter.notifyItemChanged(j);
                            break;
                        }
                    }
                }
                saveState();
                break;
            }
        }
    }

    private class DragSelectTouchListener implements RecyclerView.OnItemTouchListener {
        private boolean isActive = false;
        private boolean isChecking = true;
        private int lastToggledPos = -1;

        private int dpToPx(int dp) {
            return (int) (dp * getResources().getDisplayMetrics().density);
        }

        @Override
        public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                View child = rv.findChildViewUnder(e.getX(), e.getY());
                if (child != null) {
                    float x = e.getX();
                    int pos = rv.getChildAdapterPosition(child);
                    if (pos != RecyclerView.NO_POSITION) {
                        // Find the visible checkbox to determine the exact dynamic touch area
                        View cb = child.findViewById(R.id.cbAtt0);
                        if (cb.getVisibility() != View.VISIBLE) cb = child.findViewById(R.id.cbAtt1);
                        if (cb.getVisibility() != View.VISIBLE) cb = child.findViewById(R.id.cbAtt2);
                        if (cb.getVisibility() != View.VISIBLE) cb = child.findViewById(R.id.cbAtt3);
                        if (cb.getVisibility() != View.VISIBLE) cb = child.findViewById(R.id.cbAtt4);
                        if (cb.getVisibility() != View.VISIBLE) cb = child.findViewById(R.id.cbAtt5);
                        if (cb.getVisibility() != View.VISIBLE) cb = child.findViewById(R.id.cbAtt6);
                        if (cb.getVisibility() != View.VISIBLE) cb = child.findViewById(R.id.cbAtt7);
                        if (cb.getVisibility() != View.VISIBLE) cb = child.findViewById(R.id.cbAtt8);
                        if (cb.getVisibility() != View.VISIBLE) cb = child.findViewById(R.id.cbAtt9);

                        // getLeft() is relative to the parent LinearLayout (child)
                        if (cb != null && cb.getVisibility() == View.VISIBLE && x >= cb.getLeft() && x <= cb.getRight()) {
                            isActive = true;
                            AttendanceRow row = rows.get(pos);
                            // Toggle based on the first item clicked
                            isChecking = !row.attendance[selectedColumn()];
                            toggleRow(pos);
                            lastToggledPos = pos;
                            return true; // Steal the touch events to handle drag
                        }
                    }
                }
            }
            return false;
        }

        @Override
        public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
            if (!isActive) return;

            if (e.getAction() == MotionEvent.ACTION_MOVE) {
                View child = rv.findChildViewUnder(e.getX(), e.getY());
                if (child != null) {
                    int pos = rv.getChildAdapterPosition(child);
                    if (pos != RecyclerView.NO_POSITION && pos != lastToggledPos) {
                        AttendanceRow row = rows.get(pos);
                        if (row.attendance[selectedColumn()] != isChecking) {
                            toggleRow(pos);
                        }
                        lastToggledPos = pos;
                    }
                }
                // Auto-scroll if near edges
                if (e.getY() > rv.getHeight() - dpToPx(50)) {
                    rv.scrollBy(0, dpToPx(15));
                } else if (e.getY() < dpToPx(50)) {
                    rv.scrollBy(0, -dpToPx(15));
                }
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                isActive = false;
                lastToggledPos = -1;
            }
        }

        @Override
        public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {}

        private void toggleRow(int pos) {
            if (pos >= 0 && pos < rows.size()) {
                AttendanceRow row = rows.get(pos);
                if (row.markedForDeletion) return;
                row.attendance[selectedColumn()] = isChecking;
                adapter.notifyItemChanged(pos);
                onDataChanged();
                if (getActivity() instanceof BatchReviewActivity) {
                    ((BatchReviewActivity) getActivity()).onAttendanceToggledGlobally(row.id, selectedColumn(), isChecking);
                }
                saveState();
            }
        }
    }
}
