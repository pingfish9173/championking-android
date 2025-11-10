package com.champion.king.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID

/**
 * 裝置資訊工具類
 * 用於獲取裝置的唯一 ID 和相關資訊
 */
object DeviceInfoUtil {

    /**
     * 獲取裝置唯一 ID
     * 優先使用 Android ID，如果無法獲取則使用 UUID
     */
    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        return try {
            // 嘗試獲取 Android ID
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )

            // 如果 Android ID 有效，使用它
            if (!androidId.isNullOrEmpty() && androidId != "9774d56d682e549c") {
                androidId
            } else {
                // 否則生成並儲存一個 UUID
                getOrCreateUUID(context)
            }
        } catch (e: Exception) {
            // 如果出錯，使用 UUID
            getOrCreateUUID(context)
        }
    }

    /**
     * 獲取裝置型號
     * 例如：Pixel 6, SM-G991B
     */
    fun getDeviceModel(): String {
        return Build.MODEL ?: "Unknown"
    }

    /**
     * 獲取裝置品牌
     * 例如：Google, Samsung
     */
    fun getDeviceBrand(): String {
        return Build.BRAND?.capitalize() ?: "Unknown"
    }

    /**
     * 獲取 Android 版本
     * 例如：13, 12, 11
     */
    fun getAndroidVersion(): String {
        return Build.VERSION.RELEASE ?: "Unknown"
    }

    /**
     * 獲取完整的裝置資訊
     */
    data class DeviceInfo(
        val deviceId: String,
        val deviceModel: String,
        val deviceBrand: String,
        val androidVersion: String
    )

    /**
     * 一次性獲取所有裝置資訊
     */
    fun getDeviceInfo(context: Context): DeviceInfo {
        return DeviceInfo(
            deviceId = getDeviceId(context),
            deviceModel = getDeviceModel(),
            deviceBrand = getDeviceBrand(),
            androidVersion = getAndroidVersion()
        )
    }

    // ==================== 私有方法 ====================

    private const val PREFS_NAME = "device_info_prefs"
    private const val KEY_DEVICE_UUID = "device_uuid"

    /**
     * 獲取或建立 UUID
     * 如果已經存在則讀取，否則建立新的並儲存
     */
    private fun getOrCreateUUID(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 嘗試讀取已儲存的 UUID
        var uuid = prefs.getString(KEY_DEVICE_UUID, null)

        // 如果不存在，建立新的 UUID
        if (uuid.isNullOrEmpty()) {
            uuid = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_UUID, uuid).apply()
        }

        return uuid
    }
}