package com.champion.king.data.api.dto

data class BindDeviceRequest(
    val uid: String,
    val deviceId: String,
    val deviceInfo: DeviceInfo
)

data class DeviceInfo(
    val deviceModel: String,
    val deviceBrand: String,
    val androidVersion: String
)