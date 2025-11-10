package com.champion.king.data.api.dto

data class LoginRequest(
    val account: String,
    val password: String,
    val deviceId: String
)