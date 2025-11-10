package com.champion.king

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.champion.king.core.config.AppConfig
import com.champion.king.core.ui.BaseBindingFragment
import com.champion.king.databinding.FragmentLoginBinding
import com.champion.king.ui.auth.LoginViewModel
import com.champion.king.ui.auth.LoginResult
import com.champion.king.ui.auth.ResetPasswordResult
import com.champion.king.ui.auth.InputValidationError
import com.champion.king.util.DeviceInfoUtil
import com.champion.king.util.attachPasswordToggle
import com.champion.king.util.guardOnline
import com.champion.king.util.setThrottledClick
import com.champion.king.util.toast
import kotlinx.coroutines.launch

class LoginFragment : BaseBindingFragment<FragmentLoginBinding>() {

    companion object {
        // 測試用帳號密碼 (生產環境應移除)
        private const val TEST_ACCOUNT = "billy1"
        private const val TEST_PASSWORD = "123456"

        // UI 尺寸常數
        private const val DIALOG_PADDING_HORIZONTAL = 48
        private const val DIALOG_PADDING_VERTICAL_TOP = 24
        private const val DIALOG_PADDING_VERTICAL_BOTTOM = 8
    }

    private var authFlowListener: OnAuthFlowListener? = null
    private val viewModel: LoginViewModel by viewModels()

    private fun prefs() =
        requireContext().getSharedPreferences(AppConfig.Prefs.LOGIN_PREFS, Context.MODE_PRIVATE)

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is OnAuthFlowListener) authFlowListener = context
    }

    override fun onDetach() {
        super.onDetach()
        authFlowListener = null
    }

    override fun createBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentLoginBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViews()
        setupRememberAccount()
        observeViewModel()
    }

    private fun setupViews() {
        binding.editTextLoginPassword.attachPasswordToggle(
            R.drawable.ic_visibility, R.drawable.ic_visibility_off
        )

        binding.buttonLogin.setThrottledClick { performLogin() }
        binding.buttonRegisterFromLogin.setThrottledClick { authFlowListener?.onNavigateToRegister() }
        binding.textForgotPassword.setThrottledClick { showForgotPasswordDialog() }

    }

    private fun observeViewModel() {
        // 觀察 UI 狀態
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                binding.buttonLogin.isEnabled = !state.isLoading
                // 可以加入載入指示器
                // binding.progressBar.isVisible = state.isLoading
            }
        }

        // 觀察登入結果
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.loginResult.collect { result ->
                when (result) {
                    is LoginResult.Success -> {
                        // 🔹 檢查帳號狀態
                        val accountStatus = result.user.accountStatus

                        if (accountStatus != "ACTIVE") {
                            // 帳號未開通或已停用，顯示告警
                            val statusText = when (accountStatus) {
                                "SUSPENDED" -> "停用"
                                else -> accountStatus
                            }

                            android.app.AlertDialog.Builder(requireContext())
                                .setTitle("無法登入")
                                .setMessage("您的帳號狀態為${statusText}，無法登入，請聯繫小編進行帳號開通。")
                                .setPositiveButton("確定") { dialog, _ -> dialog.dismiss() }
                                .show()

                            viewModel.clearResults()
                            return@collect  // 中斷登入流程
                        }

                        // 帳號狀態正常，繼續登入
                        authFlowListener?.onLoginSuccess(result.user)
                        viewModel.clearResults()
                    }

                    // 🔹 新增：處理需要綁定裝置的情況
                    is LoginResult.NeedBinding -> {
                        // 檢查帳號狀態
                        val accountStatus = result.user.accountStatus

                        if (accountStatus != "ACTIVE") {
                            // 帳號未開通或已停用，顯示告警
                            val statusText = when (accountStatus) {
                                "SUSPENDED" -> "停用"
                                else -> accountStatus
                            }

                            android.app.AlertDialog.Builder(requireContext())
                                .setTitle("無法登入")
                                .setMessage("您的帳號狀態為${statusText}，無法登入，請聯繫小編進行帳號開通。")
                                .setPositiveButton("確定") { dialog, _ -> dialog.dismiss() }
                                .show()

                            viewModel.clearResults()
                            return@collect
                        }

                        // 顯示裝置綁定確認對話框
                        showDeviceBindingDialog(result.user, result.deviceInfo)
                        viewModel.clearResults()
                    }

                    is LoginResult.Error -> {
                        requireContext().toast(result.message)
                        viewModel.clearResults()
                    }
                    null -> { /* 忽略 */ }
                }
            }
        }

        // 觀察密碼重設結果
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.resetPasswordResult.collect { result ->
                when (result) {
                    is ResetPasswordResult.Success -> {
                        requireContext().toast(AppConfig.Msg.RESET_SUCCESS)
                        dismissForgotPasswordDialog()
                        viewModel.clearResults()
                    }
                    is ResetPasswordResult.Error -> {
                        requireContext().toast("${AppConfig.Msg.RESET_FAIL_PREFIX}${result.message}")
                        viewModel.clearResults()
                    }
                    is ResetPasswordResult.ValidationError -> {
                        handleValidationError(result.error)
                        viewModel.clearResults()
                    }
                    null -> { /* 忽略 */ }
                }
            }
        }
    }

    // ==================== 🔹 裝置綁定確認對話框 ====================

    /**
     * 顯示裝置綁定確認對話框（僅允許確認綁定）
     */
    private fun showDeviceBindingDialog(
        user: com.champion.king.model.User,
        deviceInfo: DeviceInfoUtil.DeviceInfo
    ) {
        val message = """
        您的帳號尚未進行裝置綁定，點擊「確認綁定此裝置」後，將不允許此帳號用其他裝置登入，藉此提高帳號的安全性，避免有心人士用其他平板登入。
        如有解除裝置綁定需求(更換平板、平板遺失或損壞)，可至用戶編輯介面設定，或聯繫小編。
    """.trimIndent()

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("裝置綁定")
            .setMessage(message)
            .setPositiveButton("確認綁定此裝置") { dialog, _ ->
                dialog.dismiss()
                performDeviceBinding(user, deviceInfo)
            }
            .setCancelable(false) // 禁止外部或返回鍵關閉
            .show()
    }

    /**
     * 執行裝置綁定
     */
    private fun performDeviceBinding(user: com.champion.king.model.User, deviceInfo: DeviceInfoUtil.DeviceInfo) {
        // 顯示載入提示
        val loadingDialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("處理中")
            .setMessage("正在綁定裝置...")
            .setCancelable(false)
            .create()
        loadingDialog.show()

        // 執行綁定
        viewModel.performDeviceBinding(user, deviceInfo) { success, message ->
            loadingDialog.dismiss()

            if (success) {
                requireContext().toast(message ?: "裝置綁定成功")
                // 綁定成功，完成登入
                authFlowListener?.onLoginSuccess(user)
            } else {
                // 綁定失敗，詢問是否繼續登入
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle("綁定失敗")
                    .setMessage("裝置綁定失敗：${message ?: "未知錯誤"}\n\n是否仍要繼續登入？")
                    .setPositiveButton("繼續登入") { dialog, _ ->
                        dialog.dismiss()
                        authFlowListener?.onLoginSuccess(user)
                    }
                    .setNegativeButton("取消") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
            }
        }
    }

    // ==================== 記憶帳號功能 ====================

    private fun setupRememberAccount() {
        // 初始化記憶帳號狀態
        val remembered = prefs().getBoolean(AppConfig.Prefs.REMEMBER_ACCOUNT, false)
        binding.checkboxRememberAccount.isChecked = remembered
        if (remembered) {
            val saved = prefs().getString(AppConfig.Prefs.REMEMBERED_ACCOUNT, "") ?: ""
            if (saved.isNotEmpty()) {
                binding.editTextLoginAccount.setText(saved)
            }
        }

        // 設定記憶帳號勾選變化監聽器
        binding.checkboxRememberAccount.setOnCheckedChangeListener { _, isChecked ->
            val editor = prefs().edit().putBoolean(AppConfig.Prefs.REMEMBER_ACCOUNT, isChecked)
            if (isChecked) {
                val currentAccount = binding.editTextLoginAccount.text.toString().trim()
                editor.putString(AppConfig.Prefs.REMEMBERED_ACCOUNT, currentAccount)
            } else {
                editor.remove(AppConfig.Prefs.REMEMBERED_ACCOUNT)
            }
            editor.apply()
        }

        // 設定帳號輸入框焦點變化監聽器
        binding.editTextLoginAccount.setOnFocusChangeListener { _, _ ->
            if (binding.checkboxRememberAccount.isChecked) {
                val currentAccount = binding.editTextLoginAccount.text.toString().trim()
                prefs().edit()
                    .putString(AppConfig.Prefs.REMEMBERED_ACCOUNT, currentAccount)
                    .apply()
            }
        }
    }

    private fun performLogin() = requireContext().guardOnline {
        val account = binding.editTextLoginAccount.text.toString().trim()
        val password = binding.editTextLoginPassword.text.toString().trim()

        // 委託給 ViewModel 處理
        viewModel.login(account, password, requireContext())
    }

    // ==================== 忘記密碼對話框 ====================

    private var forgotPasswordDialog: android.app.AlertDialog? = null
    private lateinit var dialogAccount: EditText
    private lateinit var dialogEmail: EditText
    private lateinit var dialogPhone: EditText

    private fun showForgotPasswordDialog() {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                DIALOG_PADDING_HORIZONTAL,
                DIALOG_PADDING_VERTICAL_TOP,
                DIALOG_PADDING_HORIZONTAL,
                DIALOG_PADDING_VERTICAL_BOTTOM
            )
        }

        dialogAccount = EditText(requireContext()).apply {
            hint = AppConfig.Msg.HINT_ACCOUNT
        }
        dialogEmail = EditText(requireContext()).apply {
            hint = AppConfig.Msg.HINT_EMAIL
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        dialogPhone = EditText(requireContext()).apply {
            hint = AppConfig.Msg.HINT_PHONE
            inputType = InputType.TYPE_CLASS_PHONE
        }

        container.addView(dialogAccount)
        container.addView(dialogEmail)
        container.addView(dialogPhone)

        forgotPasswordDialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle(AppConfig.Msg.FORGOT_PASSWORD_TITLE)
            .setView(container)
            .setPositiveButton(AppConfig.Msg.BUTTON_CONFIRM, null)
            .setNegativeButton(AppConfig.Msg.BUTTON_CANCEL, null)
            .create()

        forgotPasswordDialog?.setOnShowListener {
            forgotPasswordDialog?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val account = dialogAccount.text.toString().trim()
                val email = dialogEmail.text.toString().trim()
                val phone = dialogPhone.text.toString().trim()

                // 委託給 ViewModel 處理
                viewModel.resetPassword(account, email, phone)
            }
        }
        forgotPasswordDialog?.show()
    }

    private fun dismissForgotPasswordDialog() {
        forgotPasswordDialog?.dismiss()
        forgotPasswordDialog = null
    }

    private fun handleValidationError(error: InputValidationError) {
        when (error) {
            InputValidationError.ACCOUNT -> dialogAccount.error = AppConfig.Msg.ERR_ACCOUNT_FORMAT
            InputValidationError.EMAIL -> dialogEmail.error = AppConfig.Msg.ERR_EMAIL_FORMAT
            InputValidationError.PHONE -> dialogPhone.error = AppConfig.Msg.ERR_PHONE_FORMAT
        }
    }
}