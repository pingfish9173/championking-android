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
        deviceId: String,
        onResult: (success: Boolean, user: User?, message: String?, needBinding: Boolean?) -> Unit
    ) {
        scope.launch {
            try {
                val request = com.champion.king.data.api.dto.LoginRequest(
                    account = account,
                    password = password,
                    deviceId = deviceId
                )

                val response = apiService.login(request)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!

                        try {
                            FirebaseAuth.getInstance()
                                .signInWithCustomToken(body.token)
                                .await()

                            onResult(true, body.user, body.message, body.needBinding)

                        } catch (authError: Exception) {
                            onResult(false, null, "Firebase 認證失敗：${authError.message}", null)
                        }
                    } else {
                        val errorMsg = parseErrorMessage(response)
                        onResult(false, null, errorMsg, null)
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, null, "網路錯誤：${e.message ?: "未知錯誤"}", null)
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
        referralCode: String? = null,
        onResult: (success: Boolean, message: String?) -> Unit
    ) {
        scope.launch {
            try {
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

                val response = apiService.register(request)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        onResult(true, body.message)
                    } else {
                        val errorMsg = parseErrorMessage(response)
                        onResult(false, errorMsg)
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "網路錯誤：${e.message ?: "未知錯誤"}")
                }
            }
        }
    }

    /**
     * 綁定裝置
     */
    fun bindDevice(
        uid: String,
        deviceId: String,
        deviceModel: String,
        deviceBrand: String,
        androidVersion: String,
        onResult: (success: Boolean, message: String?) -> Unit
    ) {
        scope.launch {
            try {
                val request = com.champion.king.data.api.dto.BindDeviceRequest(
                    uid = uid,
                    deviceId = deviceId,
                    deviceInfo = com.champion.king.data.api.dto.DeviceInfo(
                        deviceModel = deviceModel,
                        deviceBrand = deviceBrand,
                        androidVersion = androidVersion
                    )
                )

                val response = apiService.bindDevice(request)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        Log.d("AuthRepository", "✅ 裝置綁定成功：${body.message}")
                        onResult(true, body.message)
                    } else {
                        val errorMsg = parseErrorMessage(response)
                        Log.e("AuthRepository", "❌ 裝置綁定失敗：$errorMsg")
                        onResult(false, errorMsg)
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val errorMsg = "綁定裝置時發生錯誤：${e.message ?: "未知錯誤"}"
                    Log.e("AuthRepository", "❌ $errorMsg")
                    onResult(false, errorMsg)
                }
            }
        }
    }

    /**
     * 🔹 新增：解除裝置綁定
     */
    fun unbindDevice(
        uid: String,
        onResult: (success: Boolean, message: String?) -> Unit
    ) {
        scope.launch {
            try {
                val request = com.champion.king.data.api.dto.UnbindDeviceRequest(
                    uid = uid,
                    requestSource = "USER"
                )

                val response = apiService.unbindDevice(request)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        Log.d("AuthRepository", "✅ 裝置解除綁定成功：${body.message}")
                        onResult(true, body.message)
                    } else {
                        val errorMsg = parseErrorMessage(response)
                        Log.e("AuthRepository", "❌ 裝置解除綁定失敗：$errorMsg")
                        onResult(false, errorMsg)
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val errorMsg = "解除裝置綁定時發生錯誤：${e.message ?: "未知錯誤"}"
                    Log.e("AuthRepository", "❌ $errorMsg")
                    onResult(false, errorMsg)
                }
            }
        }
    }

    private fun parseErrorMessage(response: retrofit2.Response<*>): String {
        return try {
            val errorBody = response.errorBody()?.string()
            if (errorBody != null) {
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