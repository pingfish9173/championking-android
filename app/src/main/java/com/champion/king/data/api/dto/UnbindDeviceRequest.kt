package com.champion.king.data.api.dto

import com.google.gson.annotations.SerializedName

/**
 * 解除裝置綁定請求
 */
data class UnbindDeviceRequest(
    @SerializedName("uid")
    val uid: String,

    @SerializedName("requestSource")
    val requestSource: String = "USER"  // "USER" 或 "ADMIN"
)

/**
 * 解除裝置綁定回應
 */
data class UnbindDeviceResponse(
    @SerializedName("message")
    val message: String,

    @SerializedName("previousDeviceId")
    val previousDeviceId: String?,

    @SerializedName("unbindTime")
    val unbindTime: String
)