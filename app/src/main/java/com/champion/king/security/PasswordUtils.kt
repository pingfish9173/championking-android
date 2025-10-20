package com.champion.king.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 密碼工具：產生 salt（Base64）＋ SHA-256(salt||password) → hex
 * 與你現有 Register/Login 的用法對齊：
 *   - generateSaltBase64(16)
 *   - sha256Hex(saltBase64, password)
 *   - verify(saltBase64, password, expectedHex)
 */
object PasswordUtils {
    private val secureRandom = SecureRandom()

    /** 產生 Base64 編碼的隨機 salt（長度為 [byteLength] 位元組） */
    @JvmStatic
    fun generateSaltBase64(byteLength: Int = 16): String {
        val buf = ByteArray(byteLength)
        secureRandom.nextBytes(buf)
        return Base64.encodeToString(buf, Base64.NO_WRAP)
    }

    /** 以 SHA-256 計算 hex 字串：sha256( saltBytes || passwordUtf8 ) */
    @JvmStatic
    fun sha256Hex(saltBase64: String, password: String): String {
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        val hash = md.digest(password.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    /** 驗證：使用 salt+password 計算的 hex 是否等於 expected（忽略大小寫） */
    @JvmStatic
    fun verify(saltBase64: String, password: String, expectedHex: String): Boolean {
        val actual = sha256Hex(saltBase64, password)
        return actual.equals(expectedHex, ignoreCase = true)
    }
}
