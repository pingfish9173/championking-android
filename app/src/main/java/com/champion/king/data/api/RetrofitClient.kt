package com.champion.king.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import com.champion.king.BuildConfig

object RetrofitClient {

    // 註冊 API 的完整 URL
    private const val REGISTER_BASE_URL = "https://register-qmvrvane7q-uc.a.run.app"
    private val APP_SECRET = BuildConfig.APP_SECRET

    // 建立 OkHttp Client（加入日誌攔截器）
    private val okHttpClient: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        // 🔹 新增：App Auth Interceptor
        val appAuthInterceptor = Interceptor { chain ->
            val originalRequest = chain.request()
            val newRequest = originalRequest.newBuilder()
                .addHeader("X-App-Auth", APP_SECRET)  // 加入驗證 header
                .build()
            chain.proceed(newRequest)
        }

        OkHttpClient.Builder()
            .addInterceptor(appAuthInterceptor)      // 🔹 加在這裡
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // 建立 Retrofit 實例（用於註冊）
    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(REGISTER_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    // 提供 ApiService 實例
    val apiService: ApiService by lazy {
        retrofit.create(ApiService::class.java)
    }
}