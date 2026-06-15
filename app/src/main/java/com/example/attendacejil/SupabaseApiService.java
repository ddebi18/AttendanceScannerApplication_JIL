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

    // Delete a member
    @retrofit2.http.DELETE("members")
    Call<String> deleteMember(@Query("id") String eqId);

    // Insert or Upsert attendance
    @POST("attendance?on_conflict=member_id,year,month")
    Call<String> addAttendanceBatch(@retrofit2.http.Header("Prefer") String prefer, @Body RequestBody attendanceArrayJson);

    // Fetch attendance by year and month
    @GET("attendance?select=*")
    Call<String> getAttendance(@Query("year") String eqYear, @Query("month") String eqMonth);
}
