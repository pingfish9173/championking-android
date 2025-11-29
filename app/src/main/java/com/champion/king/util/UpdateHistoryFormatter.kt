package com.champion.king.util

import com.champion.king.data.api.dto.VersionInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 更新歷史格式化工具
 * 共用於 MainActivity 和 UserEditFragment
 */
object UpdateHistoryFormatter {

    /**
     * 格式化更新歷史內容
     */
    fun format(versionInfo: VersionInfo): String {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.TAIWAN)

        // 如果有更新歷史，顯示所有歷史紀錄
        if (versionInfo.updateHistory.isNotEmpty()) {
            versionInfo.updateHistory.forEach { historyItem ->
                // 日期和版本號
                val dateStr = if (historyItem.deployedAt > 0) {
                    dateFormat.format(Date(historyItem.deployedAt))
                } else {
                    ""
                }

                if (dateStr.isNotEmpty()) {
                    sb.append("$dateStr　")
                }
                sb.append("V${historyItem.versionName}\n")

                // 標題
                if (historyItem.updateInfo.title.isNotEmpty()) {
                    sb.append(historyItem.updateInfo.title)
                    sb.append("\n")
                }

                // 細項
                historyItem.updateInfo.items.forEach { item ->
                    sb.append("• $item\n")
                }

                sb.append("\n")
            }
        } else {
            // 如果沒有歷史，只顯示最新版本的更新資訊
            sb.append("V${versionInfo.versionName}\n")

            if (versionInfo.updateInfo.title.isNotEmpty()) {
                sb.append(versionInfo.updateInfo.title)
                sb.append("\n")
            }

            versionInfo.updateInfo.items.forEach { item ->
                sb.append("• $item\n")
            }
        }

        return sb.toString().trimEnd()
    }
}