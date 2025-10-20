package com.champion.king.data

import com.champion.king.core.config.AppConfig
import com.champion.king.model.User
import com.champion.king.security.PasswordUtils
import com.champion.king.util.TimeUtils
import com.google.firebase.database.*

class AuthRepository(private val root: DatabaseReference) {

    private fun users() = root.child("users")
    private fun devicePasswords() = root.child("devicePasswords")

    fun login(
        account: String,
        password: String,
        onResult: (success: Boolean, user: User?, message: String?) -> Unit
    ) {
        users().child(account).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (!snap.exists()) {
                    onResult(false, null, AppConfig.Msg.LOGIN_FAIL); return
                }
                val salt = snap.child("salt").getValue(String::class.java)
                val stored = snap.child("passwordHash").getValue(String::class.java)
                if (salt.isNullOrEmpty() || stored.isNullOrEmpty()) {
                    onResult(false, null, AppConfig.Msg.LOGIN_FAIL); return
                }
                val inputHash = PasswordUtils.sha256Hex(salt, password)
                if (!inputHash.equals(stored, ignoreCase = true)) {
                    onResult(false, null, AppConfig.Msg.LOGIN_FAIL); return
                }
                val user = snap.getValue(User::class.java) ?: User()
                user.account = account
                user.firebaseKey = snap.key
                onResult(true, user, null)
            }

            override fun onCancelled(error: DatabaseError) {
                onResult(false, null, dbErrorToHumanMessage(error))
            }
        })
    }

    fun resetPasswordToPhone(
        account: String, email: String, phone: String,
        onResult: (success: Boolean, message: String?) -> Unit
    ) {
        users().child(account).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (!snap.exists()) {
                    onResult(false, "帳號不存在"); return
                }
                val dbEmail = snap.child("email").getValue(String::class.java)
                val dbPhone = snap.child("phone").getValue(String::class.java)
                if (!email.equals(dbEmail, true) || phone != dbPhone) {
                    onResult(false, "驗證失敗：Email 或手機不符"); return
                }
                val newSalt = PasswordUtils.generateSaltBase64(16)
                val newHash = PasswordUtils.sha256Hex(newSalt, phone)
                snap.ref.updateChildren(mapOf("salt" to newSalt, "passwordHash" to newHash))
                    .addOnSuccessListener { onResult(true, null) }
                    .addOnFailureListener { e -> onResult(false, e.message) }
            }

            override fun onCancelled(error: DatabaseError) {
                onResult(false, dbErrorToHumanMessage(error))
            }
        })
    }

    /**
     * 註冊新用戶
     * 🔹 已更新：加入 accountStatus、lineId、remark 欄位
     */
    fun registerUser(
        account: String,
        password: String,
        email: String,
        phone: String,
        city: String,
        district: String,
        deviceNum: String,
        onResult: (success: Boolean, message: String?) -> Unit
    ) {
        devicePasswords().orderByChild("number").equalTo(deviceNum)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    if (!snap.exists()) {
                        onResult(false, AppConfig.Msg.DEVICE_NOT_FOUND); return
                    }

                    var matchedKey: String? = null
                    for (child in snap.children) {
                        val st = child.child("status").getValue(Int::class.java) ?: -1
                        if (st == 0) {
                            matchedKey = child.key
                            break
                        }
                    }
                    if (matchedKey == null) {
                        onResult(false, AppConfig.Msg.DEVICE_NOT_AVAILABLE); return
                    }

                    val salt = PasswordUtils.generateSaltBase64(16)
                    val hash = PasswordUtils.sha256Hex(salt, password)

                    val userData = hashMapOf<String, Any?>(
                        "account" to account,
                        "email" to email,
                        "phone" to phone,
                        "city" to city,
                        "district" to district,
                        "salt" to salt,
                        "passwordHash" to hash,
                        "devicePasswords" to deviceNum,

                        // 🔹 新增：帳號狀態、LineID、備註欄位（使用預設值）
                        "accountStatus" to "ACTIVE",  // 預設為開通
                        "lineId" to "",               // 預設為空字串
                        "remark" to "",               // 預設為空字串

                        // 初始化積分與刮刮卡數量
                        "point" to 0,
                        "scratchType_10" to 0,
                        "scratchType_20" to 0,
                        "scratchType_25" to 0,
                        "scratchType_30" to 0,
                        "scratchType_40" to 0,
                        "scratchType_50" to 0,
                        "scratchType_60" to 0,
                        "scratchType_80" to 0,
                        "scratchType_100" to 0,
                        "scratchType_120" to 0,
                        "scratchType_160" to 0,
                        "scratchType_200" to 0,
                        "scratchType_240" to 0
                    )

                    val updates = hashMapOf<String, Any?>(
                        "/users/$account" to userData,
                        "/devicePasswords/$matchedKey/status" to 1,
                        "/devicePasswords/$matchedKey/updatedAt" to ServerValue.TIMESTAMP,
                        "/devicePasswords/$matchedKey/updateTime" to TimeUtils.taipeiNowString()
                    )

                    root.updateChildren(updates)
                        .addOnSuccessListener { onResult(true, null) }
                        .addOnFailureListener { e -> onResult(false, e.message) }
                }

                override fun onCancelled(error: DatabaseError) {
                    onResult(false, dbErrorToHumanMessage(error))
                }
            })
    }

    private fun dbErrorToHumanMessage(error: com.google.firebase.database.DatabaseError): String {
        return when (error.code) {
            com.google.firebase.database.DatabaseError.PERMISSION_DENIED ->
                "權限不足或尚未登入。"  // 不再提示 App Check
            com.google.firebase.database.DatabaseError.NETWORK_ERROR ->
                "網路錯誤：請檢查連線。"

            else -> "資料庫錯誤（${error.code}）：${error.message}"
        }
    }
}