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
import com.champion.king.util.ToastManager
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
                        activity?.let {
                            ToastManager.show(it, result.message)
                        }
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
                        activity?.let {
                            ToastManager.show(it, AppConfig.Msg.RESET_SUCCESS)
                        }
                        dismissForgotPasswordDialog()
                        viewModel.clearResults()
                    }
                    is ResetPasswordResult.Error -> {
                        activity?.let {
                            ToastManager.show(it, "${AppConfig.Msg.RESET_FAIL_PREFIX}${result.message}")
                        }
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
        1.您的帳號尚未進行裝置綁定，請閱讀「免責聲明」後，點擊「確認綁定此裝置」。
        2.綁定此裝置後，將不允許此帳號用其他裝置登入，藉此提高帳號的安全性，避免有心人士用其他裝置登入。
        3.如有解除裝置綁定需求（更換平板、平板遺失或損壞），可至用戶編輯介面設定，或聯繫小編。
    """.trimIndent()

        // 免責聲明內容
        val disclaimerText = """
冠軍王電子刮板｜免責聲明

為保障使用者權益，並維護「冠軍王電子刮板」平台（以下簡稱「本平台」）之正常運作，請使用者在使用本平台提供之服務前，詳閱以下免責聲明。當使用者開始使用本平台，即視為已閱讀、了解並同意遵守本免責聲明之全部內容。

一、服務使用風險
使用者明白並同意，於本平台進行刮卡、遊戲或相關操作時，可能因網路環境、裝置狀況、系統更新、不可抗力等因素造成延遲、錯誤、中斷或資料遺失，本平台不負任何賠償責任。
本平台遊戲內容之結果為系統隨機生成，並無任何人工操控、保證中獎、特別待遇或其他不當行為。

二、帳號安全與裝置綁定
本平台採用裝置綁定與驗證機制以保障使用者安全。
使用者應妥善保管帳號、密碼及綁定裝置，因個人疏忽導致之損害，本平台不負賠償責任。

三、點數、道具及虛擬物品
所有點數與虛擬物品均無現金價值，亦不可兌換為現金或其他資產。
如因誤操作、第三方惡意行為或系統問題造成虛擬物品遺失，本平台將依紀錄協助查詢，但不保證補發。

四、內容正確性與資訊更新
本平台展示之圖片、商品資訊、活動內容僅供參考，本平台得隨時修改或移除相關資訊。
因資訊錯誤或變更導致之損失，本平台不負責任。

五、設備、使用環境與外在因素
使用者應確保運行本平台之裝置處於正常且合適之環境，例如：
- 避免陽光直射裝置
- 避免高溫、潮濕、灰塵、強震動、強磁場等極端環境
- 避免電量不足、裝置老化或散熱不良等狀況
若因不當使用環境而影響本平台運行，本平台不負責任。
本平台之功能可能因不同裝置規格、效能或使用者自行安裝之第三方軟體造成差異，本平台不保證於各型號裝置皆能完全正常運作。

六、第三方服務
對於本平台連結之外部網站、金流或其他第三方服務，其內容與安全性皆由第三方負責，本平台不負責任。

七、系統維護與服務中止
本平台可能因維護、更新、故障或不可抗力而暫停服務。
因上述原因造成的資料遺漏或使用不便，本平台不負任何賠償責任。

八、法律責任限制
除法律強制規定外，本平台對使用者因使用服務而產生之任何直接或間接損害，概不負責。

九、本聲明之修改
本平台得隨時修訂本免責聲明並公告於平台，使用者於公告後繼續使用即視為同意修訂內容。
        """.trimIndent()

        // 創建自定義對話框布局
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        // 主要訊息
        val messageTextView = android.widget.TextView(requireContext()).apply {
            text = message
            textSize = 16f
            setTextColor(android.graphics.Color.parseColor("#333333"))
        }
        container.addView(messageTextView)

        // 免責聲明展開按鈕
        val disclaimerToggleButton = android.widget.TextView(requireContext()).apply {
            text = "免責聲明 ▼"
            textSize = 16f
            setTextColor(android.graphics.Color.parseColor("#1976D2"))
            setPadding(0, 32, 0, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(disclaimerToggleButton)

        // 免責聲明內容容器（初始隱藏）
        val disclaimerContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        // 免責聲明文字區域（ScrollView）
        val scrollView = android.widget.ScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                250
            )
        }

        val disclaimerTextView = android.widget.TextView(requireContext()).apply {
            text = disclaimerText
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#666666"))
            setPadding(16, 16, 16, 16)
            setBackgroundColor(android.graphics.Color.parseColor("#F5F5F5"))
        }
        scrollView.addView(disclaimerTextView)
        disclaimerContainer.addView(scrollView)

        // 倒數計時文字
        val countdownTextView = android.widget.TextView(requireContext()).apply {
            text = "閱讀中...5"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#FF6B6B"))
            setPadding(0, 8, 0, 8)
            gravity = android.view.Gravity.CENTER
        }
        disclaimerContainer.addView(countdownTextView)

        // 確認閱讀 Checkbox（初始隱藏）
        val confirmCheckbox = android.widget.CheckBox(requireContext()).apply {
            text = "我已完成閱讀免責聲明"
            textSize = 16f
            visibility = View.GONE
            setPadding(0, 0, 0, 0)
        }
        disclaimerContainer.addView(confirmCheckbox)

        container.addView(disclaimerContainer)

        // 創建對話框
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("裝置綁定")
            .setView(container)
            .setPositiveButton("確認綁定此裝置", null) // 先設為 null，稍後手動設置
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .create()

        // 倒數計時器變數
        var countdownJob: kotlinx.coroutines.Job? = null

        // 展開/收合免責聲明
        disclaimerToggleButton.setOnClickListener {
            if (disclaimerContainer.visibility == View.GONE) {
                // 展開
                disclaimerContainer.visibility = View.VISIBLE
                disclaimerToggleButton.text = "免責聲明 ▲"

                // 開始 5 秒倒數
                countdownJob?.cancel()
                countdownJob = viewLifecycleOwner.lifecycleScope.launch {
                    for (i in 5 downTo 1) {
                        countdownTextView.text = "閱讀中...$i"
                        kotlinx.coroutines.delay(1000)
                    }
                    // 倒數結束，顯示 checkbox
                    countdownTextView.visibility = View.GONE
                    confirmCheckbox.visibility = View.VISIBLE
                }
            } else {
                // 收合
                disclaimerContainer.visibility = View.GONE
                disclaimerToggleButton.text = "免責聲明 ▼"
                countdownJob?.cancel()
                countdownTextView.visibility = View.VISIBLE
                countdownTextView.text = "閱讀中...5"
                confirmCheckbox.visibility = View.GONE
                confirmCheckbox.isChecked = false
            }
        }

        dialog.setOnShowListener {
            ToastManager.setHostWindow(dialog.window)
            val positiveButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            val negativeButton = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)

            // 一開始不能按「確認」
            positiveButton.isEnabled = false

            // 監聽 checkbox 狀態變化
            confirmCheckbox.setOnCheckedChangeListener { _, isChecked ->
                positiveButton.isEnabled = isChecked
            }

            // 設置確認綁定按鈕點擊事件
            positiveButton.setOnClickListener {
                dialog.dismiss()
                countdownJob?.cancel() // 取消倒數計時
                performDeviceBinding(user, deviceInfo)
            }

            negativeButton.setOnClickListener {
                countdownJob?.cancel()
                dialog.dismiss()
                // 你如果想加額外動作（例如回到登入畫面）也可以在這裡加入
            }
        }

        dialog.setOnDismissListener {
            ToastManager.clearHostWindow()
        }

        dialog.show()

        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
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
                activity?.let {
                    ToastManager.show(it, message ?: "裝置綁定成功")
                }
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