package com.champion.king.data.api.dto

/**
 * 版本資訊 DTO
 */
data class VersionInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val downloadUrl: String = "",
    val updateType: String = "optional",
    val updateInfo: UpdateInfoDto = UpdateInfoDto(),
    val updateHistory: List<UpdateHistoryItem> = emptyList()
)

/**
 * 更新資訊
 */
data class UpdateInfoDto(
    val title: String = "",
    val items: List<String> = emptyList()
)

/**
 * 歷史更新紀錄項目
 */
data class UpdateHistoryItem(
    val versionCode: Int = 0,
    val versionName: String = "",
    val updateInfo: UpdateInfoDto = UpdateInfoDto(),
    val deployedAt: Long = 0L
)