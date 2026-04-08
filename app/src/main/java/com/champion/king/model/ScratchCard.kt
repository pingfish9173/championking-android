package com.champion.king.model // 確保與您的應用程式套件名稱一致

// 定義單張刮刮卡的資料模型
data class ScratchCard(
    var serialNumber: String? = null, // 刮刮卡的唯一序號
    var numberConfigurations: List<NumberConfiguration>? = null, // 刮刮卡上的數字配置列表
    var scratchesType: Int = 0, // 刮數，指的是這個刮刮卡總共有幾個格子
    var order: Int = 0, // 在用戶刮刮卡列表中的顯示順序
    var specialPrize: String? = null, // 特獎
    var grandPrize: String? = null, // 大獎
    var inUsed: Boolean = false, // 刮刮卡是否為玩家頁面正在刮的板
    var clawsCount: Int? = null, // 新增：夾出數量
    var giveawayCount: Int? = null, // 新增：贈送刮數
    var pitchType: String = "scratch", // 新增：規則類型（scratch=夾出贈送, shopping=消費贈送）
    var isAnswerShowed: Boolean = false,

    // ====== 👇 以下為新增的分割版面專用欄位 ======
    // 分割模式標記 (例如 "20x4", "30x6")。若為 null 則代表是舊版單一版面
    var splitMode: String? = null,
    // 子版資料集合，Key 為子版代號 (如 "A", "B")，Value 為該子版的詳細資料
    var boards: Map<String, Board>? = null
)