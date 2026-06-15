package com.example.attendacejil;

import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;

public interface SupabaseApiService {

    @GET("members?select=*")
    Call<String> getMembers();

    @GET("networks?select=*")
    Call<String> getNetworks();

    // Insert a new member and return the inserted row
    @POST("members")
    Call<String> addMember(@retrofit2.http.Header("Prefer") String prefer, @Body RequestBody memberJson);
    
    // Update an existing member
    @retrofit2.http.PATCH("members")
    Call<String> updateMember(@Query("id") String eqId, @Body RequestBody memberJson);

    // Insert or Upsert attendance
    @POST("attendance")
    Call<String> addAttendanceBatch(@retrofit2.http.Header("Prefer") String prefer, @Body RequestBody attendanceArrayJson);
}
