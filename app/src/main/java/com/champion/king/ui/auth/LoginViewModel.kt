package com.champion.king.ui.auth

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.champion.king.auth.FirebaseAuthHelper
import com.champion.king.core.config.AppConfig
import com.champion.king.data.AuthRepository
import com.champion.king.model.User
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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    fun login(account: String, password: String) {
        // 輸入驗證
        val validationError = validateLoginInput(account, password)
        if (validationError != null) {
            _loginResult.value = LoginResult.Error(validationError)
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true)

        viewModelScope.launch {
            try {
                executeLoginFlow(account, password)
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
     * 執行完整的登入流程
     */
    private suspend fun executeLoginFlow(account: String, password: String) {
        // 步驟 1: App Check 預檢（非強制）
        performAppCheckPreflight()

        // 步驟 2: 確保 Firebase 認證
        ensureFirebaseAuth()

        // 步驟 3: 執行實際登入
        val user = performActualLogin(account, password)
        _loginResult.value = LoginResult.Success(user)
    }

    /**
     * App Check 預檢 - 失敗只記錄，不阻擋
     */
    private suspend fun performAppCheckPreflight() {
        try {
            Firebase.appCheck.getAppCheckToken(/* forceRefresh = */ true).await()
            Log.d("LoginViewModel", "App Check preflight successful")
        } catch (e: Exception) {
            Log.d("LoginViewModel", "App Check preflight ignored: ${e.message}")
            // 不拋出異常，讓流程繼續
        }
    }

    /**
     * 確保 Firebase 認證狀態
     */
    private suspend fun ensureFirebaseAuth() {
        // 刷新 Auth token（強制）
        Firebase.auth.currentUser?.getIdToken(true)?.await()

        // 確保匿名認證
        return suspendCoroutine { continuation ->
            FirebaseAuthHelper.ensureAnonymous { success, error ->
                if (success) {
                    continuation.resume(Unit)
                } else {
                    continuation.resumeWithException(
                        Exception("Firebase 認證失敗：${error?.message ?: "未知錯誤"}")
                    )
                }
            }
        }
    }

    /**
     * 執行實際的使用者登入
     */
    private suspend fun performActualLogin(account: String, password: String): User {
        return suspendCoroutine { continuation ->
            repo.login(account, password) { success, user, message ->
                if (success && user != null) {
                    continuation.resume(user)
                } else {
                    continuation.resumeWithException(
                        Exception(message ?: AppConfig.Msg.LOGIN_FAIL)
                    )
                }
            }
        }
    }

    /**
     * 執行密碼重設
     */
    private suspend fun performPasswordReset(account: String, email: String, phone: String): ResetPasswordResult {
        return suspendCoroutine { continuation ->
            repo.resetPasswordToPhone(account, email, phone) { success, message ->
                if (success) {
                    continuation.resume(ResetPasswordResult.Success)
                } else {
                    continuation.resume(ResetPasswordResult.Error(message ?: "請稍後重試"))
                }
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