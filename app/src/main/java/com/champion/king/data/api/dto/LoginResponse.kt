package com.champion.king.data.api.dto

import com.champion.king.model.User

data class LoginResponse(
    val message: String,
    val token: String,      // Firebase Custom Token
    val user: User          // 使用者資料
)