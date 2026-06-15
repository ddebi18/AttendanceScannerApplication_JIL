package com.example.attendacejil;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class BulkAddReviewActivity extends AppCompatActivity {

    public static final String EXTRA_JSON_DATA = "extra_json_data";

    private RecyclerView rvBulkMembers;
    private ReviewAdapter adapter;
    private final List<AttendanceRow> rows = new ArrayList<>();
    private Button btnSaveToDb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bulk_add);

        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        rvBulkMembers = findViewById(R.id.rvBulkMembers);
        rvBulkMembers.setLayoutManager(new LinearLayoutManager(this));

        btnSaveToDb = findViewById(R.id.btnSaveToDb);
        btnSaveToDb.setOnClickListener(v -> saveToDatabase());

        String json = getIntent().getStringExtra(EXTRA_JSON_DATA);
        if (json != null) {
            parseRowsJson(json);
        }

        // Pass -1 to hide attendance columns
        adapter = new ReviewAdapter(this, rows, () -> {}, -1);
        rvBulkMembers.setAdapter(adapter);
    }

    private void parseRowsJson(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                
                String lastName   = AttendanceRow.normalize(obj.optString("last_name",  "").trim());
                String firstName  = AttendanceRow.normalize(obj.optString("first_name", "").trim());
                String network    = obj.optString("network",    "").trim();
                
                // Skip completely empty rows
                if (lastName.isEmpty() && firstName.isEmpty() && network.isEmpty()) {
                    continue;
                }
                
                AttendanceRow row = new AttendanceRow(
                        UUID.randomUUID().toString(),
                        lastName, 100,
                        firstName, 100,
                        network, 100,
                        new boolean[10], false);
                rows.add(row);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Failed to parse data.", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveToDatabase() {
        btnSaveToDb.setEnabled(false);
        btnSaveToDb.setText("Saving...");
        
        List<AttendanceRow> validRows = new ArrayList<>();
        for (AttendanceRow r : rows) {
            if (r.markedForDeletion) continue;
            if (r.lastName.trim().isEmpty() || r.firstName.trim().isEmpty()) {
                Toast.makeText(this, "First or Last Name cannot be empty.", Toast.LENGTH_SHORT).show();
                btnSaveToDb.setEnabled(true);
                btnSaveToDb.setText("Confirm & Save to DB");
                return;
            }
            validRows.add(r);
        }

        if (validRows.isEmpty()) {
            Toast.makeText(this, "No members to add.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        SupabaseClient.init(this);
        SupabaseApiService api = SupabaseClient.getApiService();
        if (api == null) {
            Toast.makeText(this, "Database not configured.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        int[] successCount = {0};
        int[] failCount = {0};

        for (AttendanceRow r : validRows) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("first_name", r.firstName);
                obj.put("last_name", r.lastName);
                obj.put("network_name", r.network);

                okhttp3.RequestBody body = okhttp3.RequestBody.create(
                        obj.toString(), okhttp3.MediaType.parse("application/json")
                );

                api.addMember("return=representation", body).enqueue(new Callback<String>() {
                    @Override
                    public void onResponse(Call<String> call, Response<String> response) {
                        if (response.isSuccessful()) {
                            successCount[0]++;
                        } else {
                            failCount[0]++;
                        }
                        checkDone(successCount[0], failCount[0], validRows.size());
                    }

                    @Override
                    public void onFailure(Call<String> call, Throwable t) {
                        failCount[0]++;
                        checkDone(successCount[0], failCount[0], validRows.size());
                    }
                });
            } catch (Exception e) {
                failCount[0]++;
                checkDone(successCount[0], failCount[0], validRows.size());
            }
        }
    }

    private void checkDone(int success, int fail, int total) {
        if (success + fail == total) {
            runOnUiThread(() -> {
                if (fail > 0) {
                    Toast.makeText(this, "Saved " + success + " members. " + fail + " failed.", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Successfully saved " + success + " members!", Toast.LENGTH_SHORT).show();
                }
                finish();
            });
        }
    }
}
