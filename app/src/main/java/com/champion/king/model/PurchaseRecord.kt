package com.champion.king.model

/**
 * 購買紀錄資料模型
 */
data class PurchaseRecord(
    val recordId: String = "",              // 紀錄ID（自動生成）
    val userKey: String = "",               // 玩家Firebase Key
    val username: String = "",              // 玩家帳號名稱（選填，方便查看）
    val purchaseTime: Long = 0L,            // 購買時間戳記
    val totalPoints: Int = 0,               // 總花費點數
    val items: Map<String, PurchaseItem> = emptyMap()  // 購買項目詳細
)

/**
 * 購買項目詳細資料
 */
data class PurchaseItem(
    val productName: String = "",           // 商品名稱
    val purchasedQuantity: Int = 0,         // 購買數量
    val bonusQuantity: Int = 0,             // 贈送數量
    val pricePerUnit: Int = 0,              // 單價
    val subtotal: Int = 0                   // 小計（購買數量 × 單價）
)