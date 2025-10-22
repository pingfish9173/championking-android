package com.champion.king.data.api.dto

data class RegisterRequest(
    val account: String,
    val password: String,
    val city: String,
    val district: String,
    val phone: String,
    val email: String,
    val devicePasswords: String,
    val referralCode: String? = null  // 選填
)