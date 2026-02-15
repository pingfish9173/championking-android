package com.champion.king.data.api.dto

data class NotificationMessageDto(
    val messageId: String,
    val category: String,
    val type: String,
    val createdAt: Long,
    val readAt: Long?,
    val title: String,
    val body: String,
    val promoId: String?
)