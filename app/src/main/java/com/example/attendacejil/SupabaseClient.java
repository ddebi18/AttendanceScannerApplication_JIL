package com.example.attendacejil;

import android.content.Context;
import android.content.SharedPreferences;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import retrofit2.Retrofit;
import retrofit2.converter.scalars.ScalarsConverterFactory;

public class SupabaseClient {

    private static SupabaseApiService apiService;

    public static void init(Context context) {
        String url = "https://nhhngjcymdpagfgorkai.supabase.co/rest/v1/";
        String key = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im5oaG5namN5bWRwYWdmZ29ya2FpIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODE0NTkxMjksImV4cCI6MjA5NzAzNTEyOX0.8cFCOHDmY8t-Hg8AddezwTd3f4SysZg4SwuZ72JpYM4";

        if (url.equals("YOUR_SUPABASE_URL_HERE") || key.equals("YOUR_SUPABASE_ANON_KEY_HERE")) {
            apiService = null;
            return;
        }

        // Ensure URL ends with /rest/v1/
        if (!url.endsWith("/")) url += "/";
        if (!url.endsWith("rest/v1/")) url += "rest/v1/";

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request request = chain.request().newBuilder()
                            .addHeader("apikey", key)
                            .addHeader("Authorization", "Bearer " + key)
                            .addHeader("Content-Type", "application/json")
                            .build();
                    return chain.proceed(request);
                })
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(url)
                .client(client)
                .addConverterFactory(ScalarsConverterFactory.create())
                .build();

        apiService = retrofit.create(SupabaseApiService.class);
    }

    public static SupabaseApiService getApiService() {
        return apiService;
    }
}
