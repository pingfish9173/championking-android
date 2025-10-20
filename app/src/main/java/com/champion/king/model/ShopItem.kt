package com.champion.king.model

import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
data class ShopItem(
    val order: Int = 0,
    val price: Int = 0,
    val productName: String? = null
)
