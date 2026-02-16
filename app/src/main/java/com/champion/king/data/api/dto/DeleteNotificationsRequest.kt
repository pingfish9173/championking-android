package com.champion.king.data.api.dto

data class DeleteNotificationsRequest(
    val userKey: String,
    val messageIds: List<String>
)