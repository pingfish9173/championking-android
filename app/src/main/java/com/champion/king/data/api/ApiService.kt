package com.champion.king.data.api

import com.champion.king.data.api.dto.RegisterRequest
import com.champion.king.data.api.dto.RegisterResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import com.champion.king.data.api.dto.LoginRequest
import com.champion.king.data.api.dto.LoginResponse
import com.champion.king.data.api.dto.VersionInfo
import retrofit2.http.GET

interface ApiService {

    @POST("https://asia-east1-sca3-69342.cloudfunctions.net/register")
    suspend fun register(@Body request: RegisterRequest): Response<RegisterResponse>

    @POST("https://asia-east1-sca3-69342.cloudfunctions.net/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @GET("https://checkversion-qmvrvane7q-de.a.run.app/")
    suspend fun checkVersion(): Response<VersionInfo>
}