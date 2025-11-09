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
import android.util.Log

class AuthRepository(
    private val root: DatabaseReference,
    private val apiService: ApiService = RetrofitClient.apiService
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private fun users() = root.child("users")
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

    fun syncScratchTempToMain(userKey: String, onComplete: (() -> Unit)? = null) {
        val db = FirebaseDatabase.getInstance().reference
        val tempRef = db.child("users").child(userKey).child("scratchCardsTemp")

        tempRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    Log.d("AuthRepository", "沒有 scratchCardsTemp 紀錄可同步。")
                    onComplete?.invoke()
                    return
                }

                val updates = mutableMapOf<String, Any?>()

                for (child in snapshot.children) {
                    val cardId = child.child("cardId").getValue(String::class.java)
                    val cellNumber = child.child("cellNumber").getValue(Int::class.java)

                    if (!cardId.isNullOrEmpty() && cellNumber != null) {
                        updates["users/$userKey/scratchCards/$cardId/numberConfigurations/$cellNumber/scratched"] = true
                    }
                }

                // 寫入更新並清空 temp
                db.updateChildren(updates)
                    .addOnSuccessListener {
                        Log.d("AuthRepository", "✅ 已成功同步 ${updates.size} 筆紀錄到 scratchCards。")
                        tempRef.removeValue()
                        onComplete?.invoke()
                    }
                    .addOnFailureListener { e ->
                        Log.e("AuthRepository", "❌ 同步 scratchCardsTemp 失敗: ${e.message}")
                        onComplete?.invoke()
                    }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("AuthRepository", "讀取 scratchCardsTemp 失敗: ${error.message}")
                onComplete?.invoke()
            }
        })
    }
}