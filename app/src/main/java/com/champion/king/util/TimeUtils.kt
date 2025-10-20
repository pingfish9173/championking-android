package com.champion.king.util

import com.google.firebase.database.ServerValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TimeUtils {
    private val tzTaipei: TimeZone = TimeZone.getTimeZone("Asia/Taipei")
    private val sdfTaipei = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.TAIWAN).apply {
        timeZone = tzTaipei
    }

    /** 取得台北時間的「yyyy-MM-dd HH:mm:ss」字串（以本機時間為基準） */
    @JvmStatic
    fun taipeiNowString(): String = sdfTaipei.format(Date())

    /** 將毫秒時間轉成台北時間字串 */
    @JvmStatic
    fun formatTaipei(ms: Long): String = sdfTaipei.format(Date(ms))

    /**
     * 常用的 RTDB 更新欄位：伺服器 timestamp + 台北時間字串。
     * 用法：ref.update( TimeUtils.serverTimestampsWithTaipei() )
     */
    @JvmStatic
    fun serverTimestampsWithTaipei(): Map<String, Any> = mapOf(
        "updatedAt" to ServerValue.TIMESTAMP,
        "updateTime" to taipeiNowString()
    )
}
