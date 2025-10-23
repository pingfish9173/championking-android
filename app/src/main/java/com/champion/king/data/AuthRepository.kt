package com.champion.king.data

import com.champion.king.core.config.AppConfig
import com.champion.king.model.User
import com.champion.king.security.PasswordUtils
import com.champion.king.util.TimeUtils
import com.google.firebase.database.*
import com.champion.king.data.api.ApiService
import com.champion.king.data.api.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.gson.Gson
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

class AuthRepository(
    private val root: DatabaseReference,
    private val apiService: ApiService = RetrofitClient.apiService
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private fun users() = root.child("users")
    private fun devicePasswords() = root.child("devicePasswords")

//    fun login(
//        account: String,
//        password: String,
//        onResult: (success: Boolean, user: User?, message: String?) -> Unit
//    ) {
//        users().child(account).addListenerForSingleValueEvent(object : ValueEventListener {
//            override fun onDataChange(snap: DataSnapshot) {
//                if (!snap.exists()) {
//                    onResult(false, null, AppConfig.Msg.LOGIN_FAIL); return
//                }
//                val salt = snap.child("salt").getValue(String::class.java)
//                val stored = snap.child("passwordHash").getValue(String::class.java)
//                if (salt.isNullOrEmpty() || stored.isNullOrEmpty()) {
//                    onResult(false, null, AppConfig.Msg.LOGIN_FAIL); return
//                }
//                val inputHash = PasswordUtils.sha256Hex(salt, password)
//                if (!inputHash.equals(stored, ignoreCase = true)) {
//                    onResult(false, null, AppConfig.Msg.LOGIN_FAIL); return
//                }
//                val user = snap.getValue(User::class.java) ?: User()
//                user.account = account
//                user.firebaseKey = snap.key
//                onResult(true, user, null)
//            }
//
//            override fun onCancelled(error: DatabaseError) {
//                onResult(false, null, dbErrorToHumanMessage(error))
//            }
//        })
//    }

    fun login(
        account: String,
        password: String,
        onResult: (success: Boolean, user: User?, message: String?) -> Unit
    ) {
        scope.launch {
            try {
                // 1. 建立 API 請求
                val request = com.champion.king.data.api.dto.LoginRequest(
                    account = account,
                    password = password
                )

                // 2. 呼叫登入 API
                val response = apiService.login(request)

                // 3. 處理回應
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!

                        // 🔹 使用 Custom Token 登入 Firebase Auth
                        try {
                            FirebaseAuth.getInstance()
                                .signInWithCustomToken(body.token)
                                .await()

                            // Firebase Auth 登入成功，回傳使用者資料
                            onResult(true, body.user, body.message)

                        } catch (authError: Exception) {
                            // Custom Token 登入失敗
                            onResult(false, null, "Firebase 認證失敗：${authError.message}")
                        }
                    } else {
                        val errorMsg = parseErrorMessage(response)
                        onResult(false, null, errorMsg)
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, null, "網路錯誤：${e.message ?: "未知錯誤"}")
                }
            }
        }
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

    fun registerUser(
        account: String,
        password: String,
        email: String,
        phone: String,
        city: String,
        district: String,
        deviceNum: String,
        referralCode: String? = null,  // 🔹 新增：推薦碼參數（選填）
        onResult: (success: Boolean, message: String?) -> Unit
    ) {
        scope.launch {
            try {
                // 1. 建立 API 請求
                val request = com.champion.king.data.api.dto.RegisterRequest(
                    account = account,
                    password = password,
                    city = city,
                    district = district,
                    phone = phone,
                    email = email,
                    devicePasswords = deviceNum,
                    referralCode = referralCode
                )

                // 2. 呼叫註冊 API
                val response = apiService.register(request)

                // 3. 處理回應
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body() != null) {
                        // API 成功
                        val body = response.body()!!
                        onResult(true, body.message)
                    } else {
                        // API 失敗，解析錯誤訊息
                        val errorMsg = parseErrorMessage(response)
                        onResult(false, errorMsg)
                    }
                }

            } catch (e: Exception) {
                // 網路或其他異常
                withContext(Dispatchers.Main) {
                    onResult(false, "網路錯誤：${e.message ?: "未知錯誤"}")
                }
            }
        }
    }

    /**
     * 解析 API 錯誤訊息
     */
    private fun parseErrorMessage(response: retrofit2.Response<*>): String {
        return try {
            val errorBody = response.errorBody()?.string()
            if (errorBody != null) {
                // 嘗試解析 JSON 錯誤訊息 {"error": "..."}
                val errorResponse = Gson().fromJson(
                    errorBody,
                    com.champion.king.data.api.dto.ErrorResponse::class.java
                )
                errorResponse.error
            } else {
                "註冊失敗，請稍後再試"
            }
        } catch (e: Exception) {
            "註冊失敗：${response.message()}"
        }
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