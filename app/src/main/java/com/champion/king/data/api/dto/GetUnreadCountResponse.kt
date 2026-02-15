package com.champion.king.data.api.dto

data class GetUnreadCountResponse(
    val success: Boolean,
    val userKey: String,
    val category: String,
    val unread: Int
)