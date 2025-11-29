package com.champion.king.model

/**
 * 部署紀錄資料模型
 */
data class DeployHistory(
    val versionCode: Int = 0,
    val versionName: String = "",
    val updateInfo: UpdateInfo = UpdateInfo(),
    val deployedAt: Long = 0L,
    val apkSize: String = "",
    val downloadUrl: String = "",
    val gitCommit: String = "",
    val gitBranch: String = ""
)

/**
 * 更新資訊
 */
data class UpdateInfo(
    val title: String = "",
    val items: List<String> = emptyList()
)