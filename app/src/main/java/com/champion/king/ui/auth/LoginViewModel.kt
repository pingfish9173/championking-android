package com.champion.king.ui.auth

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.champion.king.BuildConfig
import com.champion.king.auth.FirebaseAuthHelper
import com.champion.king.core.config.AppConfig
import com.champion.king.data.AuthRepository
import com.champion.king.model.User
import com.champion.king.util.DeviceInfoUtil
import com.champion.king.util.ValidationRules
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.auth.auth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LoginViewModel : ViewModel() {

    private val repo by lazy {
        AuthRepository(FirebaseDatabase.getInstance(AppConfig.DB_URL).reference)
    }

    // UI 狀態管理
    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    // 登入結果
    private val _loginResult = MutableStateFlow<LoginResult?>(null)
    val loginResult: StateFlow<LoginResult?> = _loginResult.asStateFlow()

    // 密碼重設結果
    private val _resetPasswordResult = MutableStateFlow<ResetPasswordResult?>(null)
    val resetPasswordResult: StateFlow<ResetPasswordResult?> = _resetPasswordResult.asStateFlow()

    /**
     * 執行登入
     */
    fun login(account: String, password: String, context: Context) {
        // 輸入驗證
        val validationError = validateLoginInput(account, password)
        if (validationError != null) {
            _loginResult.value = LoginResult.Error(validationError)
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true)

        viewModelScope.launch {
            try {
                val result = performActualLogin(account, password, context)
                _loginResult.value = result
            } catch (ce: CancellationException) {
                Log.d("LoginViewModel", "Login cancelled")
            } catch (e: Exception) {
                Log.e("LoginViewModel", "Login failed", e)
                _loginResult.value = LoginResult.Error("登入失敗：${e.message ?: "請稍後再試"}")
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    /**
     * 🔹 新增：手動執行裝置綁定
     */
    fun performDeviceBinding(
        user: User,
        deviceInfo: DeviceInfoUtil.DeviceInfo,
        onResult: (success: Boolean, message: String?) -> Unit
    ) {
        viewModelScope.launch {
            try {
                suspendCoroutine<Unit> { continuation ->
                    repo.bindDevice(
                        uid = user.firebaseKey ?: "",
                        deviceId = deviceInfo.deviceId,
                        deviceModel = deviceInfo.deviceModel,
                        deviceBrand = deviceInfo.deviceBrand,
                        androidVersion = deviceInfo.androidVersion
                    ) { success, message ->
                        if (success) {
                            Log.d("LoginViewModel", "✅ 裝置綁定成功")
                            onResult(true, message)
                            continuation.resume(Unit)
                        } else {
                            Log.e("LoginViewModel", "❌ 裝置綁定失敗：$message")
                            onResult(false, message)
                            continuation.resume(Unit)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("LoginViewModel", "裝置綁定發生錯誤", e)
                onResult(false, "綁定失敗：${e.message}")
            }
        }
    }

    /**
     * 重設密碼
     */
    fun resetPassword(account: String, email: String, phone: String) {
        // 輸入驗證
        val validationError = validateResetPasswordInput(account, email, phone)
        if (validationError != null) {
            _resetPasswordResult.value = ResetPasswordResult.ValidationError(validationError)
            return
        }

        _uiState.value = _uiState.value.copy(isResettingPassword = true)

        viewModelScope.launch {
            try {
                val result = performPasswordReset(account, email, phone)
                _resetPasswordResult.value = result
            } catch (e: Exception) {
                _resetPasswordResult.value = ResetPasswordResult.Error("重設失敗：${e.message ?: "請稍後重試"}")
            } finally {
                _uiState.value = _uiState.value.copy(isResettingPassword = false)
            }
        }
    }

    /**
     * 清除結果狀態
     */
    fun clearResults() {
        _loginResult.value = null
        _resetPasswordResult.value = null
    }

    /**
     * 登出（可選，為未來擴展預留）
     */
    fun logout() {
        // 這裡可以加入登出邏輯
        // 例如：清除 Firebase Auth、清除用戶數據等
        clearResults()
    }

    // ==================== 私有方法 ====================

    /**
     * 驗證登入輸入
     */
    private fun validateLoginInput(account: String, password: String): String? {
        return when {
            account.trim().isEmpty() || password.trim().isEmpty() -> AppConfig.Msg.LOGIN_EMPTY
            else -> null
        }
    }

    /**
     * 驗證重設密碼輸入
     */
    private fun validateResetPasswordInput(account: String, email: String, phone: String): InputValidationError? {
        return when {
            !ValidationRules.isValidAccount(account) -> InputValidationError.ACCOUNT
            !ValidationRules.isValidEmail(email) -> InputValidationError.EMAIL
            !ValidationRules.isValidPhone(phone) -> InputValidationError.PHONE
            else -> null
        }
    }

    /**
     * 🔹 修改：執行實際的使用者登入（不自動綁定）
     */
    private suspend fun performActualLogin(account: String, password: String, context: Context): LoginResult {
        return suspendCoroutine { continuation ->
            // 獲取裝置資訊
            val deviceInfo = DeviceInfoUtil.getDeviceInfo(context)

            repo.login(account, password, deviceInfo.deviceId) { success, user, message, needBinding ->
                if (success && user != null) {
                    // 🔹 檢查是否需要綁定裝置
                    if (needBinding == true) {
                        Log.d("LoginViewModel", "需要綁定裝置，返回 NeedBinding 狀態")
                        // 🔹 返回需要綁定的狀態，讓 Fragment 顯示確認對話框
                        continuation.resume(LoginResult.NeedBinding(user, deviceInfo))
                    } else {
                        // 不需要綁定或已經綁定，直接返回
                        Log.d("LoginViewModel", "裝置已綁定或不需綁定")
                        continuation.resume(LoginResult.Success(user))
                    }
                } else {
                    continuation.resume(
                        LoginResult.Error(message ?: AppConfig.Msg.LOGIN_FAIL)
                    )
                }
            }
        }
    }

    /**
     * 執行密碼重設
     */
    private suspend fun performPasswordReset(account: String, email: String, phone: String): ResetPasswordResult {
        return try {
            val ok = callResetPasswordApi(account, email, phone)
            if (ok) ResetPasswordResult.Success
            else ResetPasswordResult.Error("重設失敗，請稍後再試")
        } catch (e: Exception) {
            ResetPasswordResult.Error("重設失敗：${e.message}")
        }
    }

    private suspend fun callResetPasswordApi(account: String, email: String, phone: String): Boolean {
        return withContext(Dispatchers.IO) {
            val url = "https://resetpassword-qmvrvane7q-de.a.run.app"
            val payload = """{"account":"$account","email":"$email","phone":"$phone"}"""

            val client = okhttp3.OkHttpClient()
            val requestBody = payload.toRequestBody("application/json".toMediaType())

            val request = okhttp3.Request.Builder()
                .url(url)
                .addHeader("X-App-Auth", BuildConfig.APP_SECRET)
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val code = response.code
                    val bodyStr = response.body?.string()

                    if (!response.isSuccessful) {
                        throw Exception("伺服器回傳錯誤代碼 $code")
                    }

                    if (bodyStr.isNullOrEmpty()) {
                        throw Exception("伺服器未回傳內容")
                    }

                    bodyStr.contains("\"success\":true", ignoreCase = true)
                }
            } catch (e: Exception) {
                throw Exception("網路請求失敗：${e.message}")
            }
        }
    }
}

// ==================== 資料類別（定義在檔案最外層）====================

/**
 * UI 狀態
 */
data class LoginUiState(
    val isLoading: Boolean = false,
    val isResettingPassword: Boolean = false
)

/**
 * 登入結果
 */
sealed class LoginResult {
    data class Success(val user: User) : LoginResult()
    data class NeedBinding(val user: User, val deviceInfo: DeviceInfoUtil.DeviceInfo) : LoginResult()  // 🔹 新增：需要綁定裝置
    data class Error(val message: String) : LoginResult()
}

/**
 * 密碼重設結果
 */
sealed class ResetPasswordResult {
    object Success : ResetPasswordResult()
    data class Error(val message: String) : ResetPasswordResult()
    data class ValidationError(val error: InputValidationError) : ResetPasswordResult()
}

/**
 * 驗證錯誤類型
 */
enum class InputValidationError {
    ACCOUNT,
    EMAIL,
    PHONE
}