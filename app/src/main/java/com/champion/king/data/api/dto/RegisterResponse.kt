package com.champion.king.data.api.dto

data class RegisterResponse(
    val message: String,
    val uid: String,
    val deviceKey: String
)