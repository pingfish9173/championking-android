package com.champion.king.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    // 註冊 API 的完整 URL
    private const val REGISTER_BASE_URL = "https://register-qmvrvane7q-uc.a.run.app"

    // 建立 OkHttp Client（加入日誌攔截器）
    private val okHttpClient: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY  // 顯示完整的請求和回應內容
        }

        OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)  // 連線超時 30 秒
            .readTimeout(30, TimeUnit.SECONDS)     // 讀取超時 30 秒
            .writeTimeout(30, TimeUnit.SECONDS)    // 寫入超時 30 秒
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