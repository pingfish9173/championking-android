package com.champion.king.data.api

import com.champion.king.data.api.dto.RegisterRequest
import com.champion.king.data.api.dto.RegisterResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
    
    /**
     * 註冊 API
     * 端點：https://register-qmvrvane7q-uc.a.run.app
     */
    @POST("/")  // 因為完整 URL 已經包含路徑，所以這裡用 "/"
    suspend fun register(@Body request: RegisterRequest): Response<RegisterResponse>
    
    // 未來可以在這裡加入登入 API 等其他端點
}