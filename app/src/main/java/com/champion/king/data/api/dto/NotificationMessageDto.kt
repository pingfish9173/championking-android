package com.champion.king.data.api.dto

data class NotificationMessageDto(
    val messageId: String,
    val title: String?, // 可能是 null (因為 PROMO 預設沒有)
    val body: String?,  // 可能是 null
    val createdAt: Long,
    val readAt: Long?,
    val category: String,
    val type: String?,    // 🌟 新增這個
    val promoId: String?  // 🌟 新增這個
)