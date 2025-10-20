package com.champion.king.util

import android.util.Patterns
import java.util.Locale

object ValidationRules {
    // 帳號：6–20 碼，首字母英文；僅允許 英文/數字/底線/減號
    private val ACCOUNT_REGEX = Regex("^[A-Za-z][A-Za-z0-9_-]{5,19}$")

    // 手機：09xxxxxxxx（10 碼）
    private val PHONE_REGEX = Regex("^09\\d{8}$")

    /** 帳號是否符合規則 */
    fun isValidAccount(account: String): Boolean =
        ACCOUNT_REGEX.matches(account.trim())

    /** 密碼長度是否介於 6..20 */
    fun isValidPasswordLen(password: String): Boolean =
        password.length in 6..20

    /** Email 格式是否正確（不可為空） */
    fun isValidEmail(email: String): Boolean =
        email.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(email).matches()

    /** 手機格式是否正確（09 開頭 10 碼） */
    fun isValidPhone(phone: String): Boolean =
        PHONE_REGEX.matches(phone.trim())

    /** 規一化授權碼：去頭尾空白 + 轉大寫 */
    fun normalizeDeviceNumber(input: String): String =
        input.trim().uppercase(Locale.ROOT)

    // ※ 如果你不想在輸入時做「授權碼格式」即時驗證，就不要提供 isValidDeviceNumberFormat() 在 UI 使用。
    //   若要在送出前擋住無效字元，可再補一個 Regex("^[A-Z0-9]{8}$") 的檢查，僅在送出時使用即可。
}
