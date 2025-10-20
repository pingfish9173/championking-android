package com.champion.king.util

import android.content.Context
import android.content.SharedPreferences
import com.champion.king.core.config.AppConfig

/**
 * SharedPreferences 管理器
 * 統一管理應用的偏好設定
 */
class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        AppConfig.Prefs.LOGIN_PREFS,
        Context.MODE_PRIVATE
    )

    // ==================== 記憶帳號相關 ====================

    /**
     * 是否記憶帳號
     */
    var isRememberAccount: Boolean
        get() = prefs.getBoolean(AppConfig.Prefs.REMEMBER_ACCOUNT, false)
        set(value) = prefs.edit().putBoolean(AppConfig.Prefs.REMEMBER_ACCOUNT, value).apply()

    /**
     * 儲存的帳號
     */
    var rememberedAccount: String?
        get() = prefs.getString(AppConfig.Prefs.REMEMBERED_ACCOUNT, null)
        set(value) {
            if (value != null) {
                prefs.edit().putString(AppConfig.Prefs.REMEMBERED_ACCOUNT, value).apply()
            } else {
                prefs.edit().remove(AppConfig.Prefs.REMEMBERED_ACCOUNT).apply()
            }
        }

    /**
     * 更新記憶帳號設定
     * @param remember 是否記憶
     * @param account 要記憶的帳號（當 remember 為 true 時）
     */
    fun updateRememberAccount(remember: Boolean, account: String? = null) {
        prefs.edit().apply {
            putBoolean(AppConfig.Prefs.REMEMBER_ACCOUNT, remember)
            if (remember && !account.isNullOrBlank()) {
                putString(AppConfig.Prefs.REMEMBERED_ACCOUNT, account.trim())
            } else {
                remove(AppConfig.Prefs.REMEMBERED_ACCOUNT)
            }
            apply()
        }
    }

    /**
     * 清除記憶的帳號
     */
    fun clearRememberedAccount() {
        prefs.edit()
            .remove(AppConfig.Prefs.REMEMBERED_ACCOUNT)
            .putBoolean(AppConfig.Prefs.REMEMBER_ACCOUNT, false)
            .apply()
    }

    // ==================== 其他偏好設定 ====================

    /**
     * 清除所有登入相關的偏好設定
     */
    fun clearLoginPreferences() {
        prefs.edit().clear().apply()
    }

    /**
     * 獲取偏好設定的鍵值對（用於除錯）
     */
    fun getAllPreferences(): Map<String, *> = prefs.all

    // ==================== 擴展功能 ====================

    /**
     * 通用的字串偏好設定存取
     */
    fun getString(key: String, defaultValue: String? = null): String? {
        return prefs.getString(key, defaultValue)
    }

    fun putString(key: String, value: String?) {
        if (value != null) {
            prefs.edit().putString(key, value).apply()
        } else {
            prefs.edit().remove(key).apply()
        }
    }

    /**
     * 通用的布林偏好設定存取
     */
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    /**
     * 通用的整數偏好設定存取
     */
    fun getInt(key: String, defaultValue: Int = 0): Int {
        return prefs.getInt(key, defaultValue)
    }

    fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    /**
     * 通用的長整數偏好設定存取
     */
    fun getLong(key: String, defaultValue: Long = 0L): Long {
        return prefs.getLong(key, defaultValue)
    }

    fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }
}

/**
 * 擴展函式：為 Context 添加 preferencesManager 屬性
 */
fun Context.preferencesManager(): PreferencesManager = PreferencesManager(this)

/**
 * 單例版本的 PreferencesManager（可選）
 */
object GlobalPreferencesManager {
    @Volatile
    private var INSTANCE: PreferencesManager? = null

    fun getInstance(context: Context): PreferencesManager {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: PreferencesManager(context.applicationContext).also { INSTANCE = it }
        }
    }
}