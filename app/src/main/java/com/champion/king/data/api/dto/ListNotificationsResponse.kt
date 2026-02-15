package com.champion.king.data.api.dto

data class ListNotificationsResponse(
    val success: Boolean,
    val userKey: String,
    val category: String,
    val limit: Int,
    val cursor: Long?,
    val count: Int,
    val nextCursor: Long?,
    val messages: List<NotificationMessageDto>
)