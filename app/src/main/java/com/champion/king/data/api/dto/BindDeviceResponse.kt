package com.champion.king.data.api.dto

data class BindDeviceResponse(
    val message: String,
    val boundDeviceId: String,
    val bindingTime: String
)