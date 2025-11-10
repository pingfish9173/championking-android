package com.champion.king.data.api.dto

import com.champion.king.model.User

data class LoginResponse(
    val message: String,
    val token: String,         // Firebase Custom Token
    val user: User,            // 使用者資料
    val needBinding: Boolean   // 🔹 新增：是否需要綁定裝置
)