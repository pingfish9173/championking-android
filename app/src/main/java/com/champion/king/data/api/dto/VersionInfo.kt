package com.champion.king.data.api.dto

data class VersionInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val downloadUrl: String = "",
    val updateType: String = "optional",
    val updateMessage: String = "",
    val maintenanceMode: Boolean = false,
    val maintenanceMessage: String = ""
)