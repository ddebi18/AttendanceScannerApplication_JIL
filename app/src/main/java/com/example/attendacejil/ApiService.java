package com.example.attendacejil;

import java.util.List;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;

/**
 * Retrofit API interface for the Flask attendance backend.
 *
 * POST /scan   — single image → structured JSON (rows + confidence + flagging)
 * POST /export — reviewed JSON rows → CSV file
 * POST /upload — legacy: images → simple 3-col CSV (kept for compat)
 */
public interface ApiService {

    /**
     * Stage 1→3: Upload ONE image for preprocessing + OCR + checkmark detection.
     * Returns ScanResponse JSON.
     */
    @Multipart
    @POST("scan")
    Call<ResponseBody> scanImage(
            @Part MultipartBody.Part image,
            @Part("sunday_num")  RequestBody sundayNum,
            @Part("service_num") RequestBody serviceNum
    );

    /**
     * Stage 5: Export reviewed rows as CSV.
     * Body is raw JSON: { sunday_num, service_num, month, year, rows[] }
     */
    @POST("export")
    Call<ResponseBody> exportCsv(@Body RequestBody body);

    /**
     * Legacy endpoint — multi-image batch → 3-col CSV directly.
     */
    @Multipart
    @POST("upload")
    Call<ResponseBody> uploadImages(
            @Part List<MultipartBody.Part> images,
            @Part("sunday_num")  RequestBody sundayNum,
            @Part("service_num") RequestBody serviceNum
    );

    /**
     * Enhance a document image (CamScanner-style processing).
     * Returns the enhanced JPEG as raw bytes.
     */
    @Multipart
    @POST("enhance")
    Call<ResponseBody> enhanceImage(@Part MultipartBody.Part image);
}
