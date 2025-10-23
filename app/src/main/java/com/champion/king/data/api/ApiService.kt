package com.champion.king.data.api

import com.champion.king.data.api.dto.RegisterRequest
import com.champion.king.data.api.dto.RegisterResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import com.champion.king.data.api.dto.LoginRequest
import com.champion.king.data.api.dto.LoginResponse

interface ApiService {

    @POST("/")
    suspend fun register(@Body request: RegisterRequest): Response<RegisterResponse>

    @POST("https://login-qmvrvane7q-uc.a.run.app/")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>
}