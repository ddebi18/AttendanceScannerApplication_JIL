package com.example.attendacejil;

import android.content.Context;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class DbSyncHelper {

    public interface SyncCallback {
        void onSuccess();
        void onFailure(String error);
    }

    public static void syncRowsToDb(Context context, int year, int month, List<AttendanceRow> rows, SyncCallback callback) {
        if (SupabaseClient.getApiService() == null) {
            callback.onSuccess(); // Skip if DB not configured
            return;
        }

        List<AttendanceRow> validRows = new java.util.ArrayList<>();
        for (AttendanceRow r : rows) {
            if (!r.markedForDeletion) {
                validRows.add(r);
            }
        }

        if (validRows.isEmpty()) {
            callback.onSuccess();
            return;
        }

        AtomicInteger pendingTasks = new AtomicInteger(validRows.size());
        boolean[] hasError = {false};

        for (AttendanceRow row : validRows) {
            if (hasError[0]) break;

            if (row.db_id == null || row.db_id.isEmpty()) {
                // New member -> Create them first
                createMemberAndSync(year, month, row, pendingTasks, hasError, callback);
            } else {
                // Existing member -> Update their name just in case it changed, then sync attendance
                updateMemberAndSync(year, month, row, pendingTasks, hasError, callback);
            }
        }
    }

    private static void createMemberAndSync(int year, int month, AttendanceRow row, AtomicInteger pendingTasks, boolean[] hasError, SyncCallback callback) {
        try {
            JSONObject member = new JSONObject();
            member.put("first_name", row.firstName);
            member.put("last_name", row.lastName);
            member.put("network_name", row.network);

            RequestBody body = RequestBody.create(MediaType.parse("application/json"), member.toString());
            SupabaseClient.getApiService().addMember("return=representation", body).enqueue(new Callback<String>() {
                @Override
                public void onResponse(@NonNull Call<String> call, @NonNull Response<String> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        try {
                            JSONArray arr = new JSONArray(response.body());
                            if (arr.length() > 0) {
                                row.db_id = arr.getJSONObject(0).getString("id");
                                syncAttendance(year, month, row, pendingTasks, hasError, callback);
                            } else {
                                handleFailure("Empty response when creating member", pendingTasks, hasError, callback);
                            }
                        } catch (Exception e) {
                            handleFailure("Parse error when creating member", pendingTasks, hasError, callback);
                        }
                    } else {
                        handleFailure("Failed to create member: " + response.code(), pendingTasks, hasError, callback);
                    }
                }

                @Override
                public void onFailure(@NonNull Call<String> call, @NonNull Throwable t) {
                    handleFailure("Network error creating member", pendingTasks, hasError, callback);
                }
            });
        } catch (Exception e) {
            handleFailure("JSON error creating member", pendingTasks, hasError, callback);
        }
    }

    private static void updateMemberAndSync(int year, int month, AttendanceRow row, AtomicInteger pendingTasks, boolean[] hasError, SyncCallback callback) {
        try {
            JSONObject member = new JSONObject();
            member.put("first_name", row.firstName);
            member.put("last_name", row.lastName);
            member.put("network_name", row.network);

            RequestBody body = RequestBody.create(MediaType.parse("application/json"), member.toString());
            SupabaseClient.getApiService().updateMember("eq." + row.db_id, body).enqueue(new Callback<String>() {
                @Override
                public void onResponse(@NonNull Call<String> call, @NonNull Response<String> response) {
                    if (response.isSuccessful()) {
                        syncAttendance(year, month, row, pendingTasks, hasError, callback);
                    } else {
                        handleFailure("Failed to update member: " + response.code(), pendingTasks, hasError, callback);
                    }
                }

                @Override
                public void onFailure(@NonNull Call<String> call, @NonNull Throwable t) {
                    handleFailure("Network error updating member", pendingTasks, hasError, callback);
                }
            });
        } catch (Exception e) {
            handleFailure("JSON error updating member", pendingTasks, hasError, callback);
        }
    }

    private static void syncAttendance(int year, int month, AttendanceRow row, AtomicInteger pendingTasks, boolean[] hasError, SyncCallback callback) {
        try {
            JSONObject att = new JSONObject();
            att.put("member_id", row.db_id);
            att.put("year", year);
            att.put("month", month);
            
            // Map the 10 checkboxes to wk1-5 and service
            // 0: WK1 SVC1, 1: WK1 SVC2
            // 2: WK2 SVC1, 3: WK2 SVC2
            // etc...
            // If any service in the week is true, we mark the week as true.
            // (Assuming wk1-wk5 just means they attended that week).
            // Actually, the schema has wk1, wk2, wk3, wk4, wk5, service (boolean).
            // Let's map it based on the boolean array.
            att.put("wk1", row.attendance[0] || row.attendance[1]);
            att.put("wk2", row.attendance[2] || row.attendance[3]);
            att.put("wk3", row.attendance[4] || row.attendance[5]);
            att.put("wk4", row.attendance[6] || row.attendance[7]);
            att.put("wk5", row.attendance[8] || row.attendance[9]);
            
            // For 'service', if they attended SVC1 (evens: 0,2,4) or SVC2 (odds: 1,3,5)?
            // We can just leave 'service' as false for now, or true if they attended ANY.
            att.put("service", false); // Placeholder as schema might change

            // We must wrap it in an array for Supabase bulk insert/upsert
            JSONArray payload = new JSONArray();
            payload.put(att);

            RequestBody body = RequestBody.create(MediaType.parse("application/json"), payload.toString());
            // Upsert: resolution=merge-duplicates requires a unique constraint on (member_id, year, month)
            SupabaseClient.getApiService().addAttendanceBatch("resolution=merge-duplicates", body).enqueue(new Callback<String>() {
                @Override
                public void onResponse(@NonNull Call<String> call, @NonNull Response<String> response) {
                    if (response.isSuccessful()) {
                        checkCompletion(pendingTasks, hasError, callback);
                    } else {
                        handleFailure("Failed to save attendance: " + response.code(), pendingTasks, hasError, callback);
                    }
                }

                @Override
                public void onFailure(@NonNull Call<String> call, @NonNull Throwable t) {
                    handleFailure("Network error saving attendance", pendingTasks, hasError, callback);
                }
            });
        } catch (Exception e) {
            handleFailure("JSON error saving attendance", pendingTasks, hasError, callback);
        }
    }

    private static void handleFailure(String error, AtomicInteger pendingTasks, boolean[] hasError, SyncCallback callback) {
        if (!hasError[0]) {
            hasError[0] = true;
            callback.onFailure(error);
        }
    }

    private static void checkCompletion(AtomicInteger pendingTasks, boolean[] hasError, SyncCallback callback) {
        if (!hasError[0] && pendingTasks.decrementAndGet() == 0) {
            callback.onSuccess();
        }
    }
}
