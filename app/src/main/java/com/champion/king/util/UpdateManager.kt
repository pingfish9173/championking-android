package com.champion.king.util

import android.content.Context
import com.champion.king.BuildConfig
import com.champion.king.data.api.RetrofitClient
import com.champion.king.data.api.dto.VersionInfo

class UpdateManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("update_settings", Context.MODE_PRIVATE)
    private val apiService = RetrofitClient.apiService

    // 獲取自動檢查設定
    fun isAutoCheckEnabled(): Boolean {
        return prefs.getBoolean("auto_check", true)
    }

    // 儲存自動檢查設定
    fun setAutoCheck(enabled: Boolean) {
        prefs.edit().putBoolean("auto_check", enabled).apply()
    }

    // 獲取上次檢查時間
    fun getLastCheckTime(): Long {
        return prefs.getLong("last_check_time", 0L)
    }

    // 儲存檢查時間
    private fun saveCheckTime() {
        prefs.edit().putLong("last_check_time", System.currentTimeMillis()).apply()
    }

    // 檢查更新（核心方法）
    suspend fun checkUpdate(isManual: Boolean = false): UpdateResult {
        return try {
            // 記錄檢查時間
            saveCheckTime()

            // 取得目前版本號，傳給 API 以取得更新歷史
            val currentVersionCode = BuildConfig.VERSION_CODE

            // 呼叫 API（帶入目前版本號）
            val response = apiService.checkVersion(currentVersionCode)

            if (!response.isSuccessful || response.body() == null) {
                return UpdateResult.Error("無法取得版本資訊")
            }

            val versionInfo = response.body()!!

            // 比對版本號
            when {
                versionInfo.versionCode > currentVersionCode -> {
                    // 有新版本
                    val ignoredVersion = prefs.getInt("ignored_version", 0)

                    // 如果是自動檢查且使用者已選擇跳過此版本
                    if (!isManual && ignoredVersion == versionInfo.versionCode) {
                        UpdateResult.NoUpdate
                    } else {
                        UpdateResult.HasUpdate(versionInfo)
                    }
                }
                else -> UpdateResult.NoUpdate
            }

        } catch (e: Exception) {
            UpdateResult.Error(e.message ?: "網路錯誤")
        }
    }

    // 標記版本為已跳過
    fun ignoreVersion(versionCode: Int) {
        prefs.edit().putInt("ignored_version", versionCode).apply()
    }
}

// 更新結果封裝類
sealed class UpdateResult {
    object NoUpdate : UpdateResult()
    data class HasUpdate(val versionInfo: VersionInfo) : UpdateResult()
    data class Error(val message: String) : UpdateResult()
}