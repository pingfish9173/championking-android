package com.champion.king.model

// 定義分割版面中的「子版」資料模型
data class Board(
    var id: String = "", // 子版代號，例如 "A", "B", "C", "D"
    var order: Int = 0, // 在母版中的顯示順序
    var specialPrize: String? = null, // 該子版獨立的特獎
    var grandPrize: String? = null, // 該子版獨立的大獎
    var clawsCount: Int? = null, // 新增：夾出數量
    var giveawayCount: Int? = null, // 新增：贈送刮數
    var pitchType: String = "scratch", // 新增：規則類型（scratch=夾出贈送, shopping=消費贈送）
    var numberConfigurations: List<NumberConfiguration>? = null // 該子版獨立的數字配置 (例如 1-20)
)