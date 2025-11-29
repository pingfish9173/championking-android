package com.champion.king.data.api

import com.champion.king.data.api.dto.BindDeviceRequest
import com.champion.king.data.api.dto.BindDeviceResponse
import com.champion.king.data.api.dto.RegisterRequest
import com.champion.king.data.api.dto.RegisterResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import com.champion.king.data.api.dto.LoginRequest
import com.champion.king.data.api.dto.LoginResponse
import com.champion.king.data.api.dto.UnbindDeviceRequest
import com.champion.king.data.api.dto.UnbindDeviceResponse
import com.champion.king.data.api.dto.VersionInfo
import retrofit2.http.GET
import retrofit2.http.Query

interface ApiService {

    @POST("https://asia-east1-sca3-69342.cloudfunctions.net/register")
    suspend fun register(@Body request: RegisterRequest): Response<RegisterResponse>

    @POST("https://asia-east1-sca3-69342.cloudfunctions.net/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @GET("https://checkversion-qmvrvane7q-de.a.run.app/")
    suspend fun checkVersion(@Query("currentVersionCode") currentVersionCode: Int): Response<VersionInfo>
    @POST("https://binddevice-qmvrvane7q-de.a.run.app")
    suspend fun bindDevice(@Body request: BindDeviceRequest): Response<BindDeviceResponse>

    @POST("https://unbinddevice-qmvrvane7q-de.a.run.app")
    suspend fun unbindDevice(@Body request: UnbindDeviceRequest): Response<UnbindDeviceResponse>
}