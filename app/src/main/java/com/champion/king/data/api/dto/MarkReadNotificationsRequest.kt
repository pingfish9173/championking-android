package com.champion.king.data.api.dto

data class MarkReadNotificationsRequest(
    val userKey: String,
    val messageIds: List<String>
)