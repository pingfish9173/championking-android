package com.champion.king.model

import com.google.gson.annotations.SerializedName

// 用戶資料模型
data class User(
    var account: String? = null,
    var email: String? = null,
    var phone: String? = null,
    var city: String? = null,
    var district: String? = null,
    var accountStatus: String = "ACTIVE",
    var lineId: String = "",
    var remark: String = "",
    var point: Int = 0,
    var scratchCards: Map<String, ScratchCard>? = null,
    var scratchType_10: Int = 0,
    var scratchType_20: Int = 0,
    var scratchType_25: Int = 0,
    var scratchType_30: Int = 0,
    var scratchType_40: Int = 0,
    var scratchType_50: Int = 0,
    var scratchType_60: Int = 0,
    var scratchType_80: Int = 0,
    var scratchType_100: Int = 0,
    var scratchType_120: Int = 0,
    var scratchType_160: Int = 0,
    var scratchType_200: Int = 0,
    var scratchType_240: Int = 0,
    @SerializedName("uid")
    var firebaseKey: String? = null,
    var switchScratchCardPassword: String? = null,
    var salt: String? = null,
    var passwordHash: String? = null,
    var devicePasswords: String? = null
)