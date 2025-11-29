package com.champion.king

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.widget.doOnTextChanged
import com.champion.king.core.config.AppConfig
import com.champion.king.core.ui.BaseBindingFragment
import com.champion.king.data.AuthRepository
import com.champion.king.databinding.FragmentUserEditBinding
import com.champion.king.util.ValidationRules
import com.champion.king.util.attachPasswordToggle
import com.champion.king.util.setThrottledClick
import com.champion.king.util.toast
import com.google.firebase.database.FirebaseDatabase
import androidx.lifecycle.lifecycleScope
import com.champion.king.util.ApkDownloader
import kotlinx.coroutines.launch
import com.champion.king.util.UpdateManager
import com.champion.king.util.UpdateResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import com.google.firebase.auth.FirebaseAuth
import android.widget.TextView
import com.champion.king.util.UpdateHistoryFormatter

class UserEditFragment : BaseBindingFragment<FragmentUserEditBinding>() {

    private var userSessionProvider: UserSessionProvider? = null
    private val repo by lazy {
        AuthRepository(FirebaseDatabase.getInstance(AppConfig.DB_URL).reference)
    }

    // 更新管理器
    private val updateManager by lazy { UpdateManager(requireContext()) }

    // HTTP 客戶端（用於 API 呼叫）
    private val httpClient by lazy { OkHttpClient() }

    // 儲存原始資料
    private var originalAddress: String = ""
    private var originalAuthCode: String = ""

    // 顯示狀態
    private var isAddressVisible: Boolean = false
    private var isAuthCodeVisible: Boolean = false

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is UserSessionProvider) userSessionProvider = context
        else throw RuntimeException("$context must implement UserSessionProvider")
    }

    override fun onDetach() {
        super.onDetach()
        userSessionProvider = null
    }

    override fun createBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentUserEditBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 設定變更密碼按鈕點擊事件
        binding.buttonChangePassword.setThrottledClick {
            showChangePasswordDialog()
        }

        // 🔹 設定解除裝置綁定按鈕點擊事件
        binding.buttonUnbindDevice.setThrottledClick {
            showUnbindDeviceDialog()
        }

        // 設定地址眼睛開關點擊事件
        binding.iconAddressToggle.setOnClickListener {
            Log.d("UserEditFragment", "Address toggle clicked, current state: $isAddressVisible")
            isAddressVisible = !isAddressVisible
            updateAddressVisibility()
        }

        // 設定授權碼眼睛開關點擊事件
        binding.iconAuthCodeToggle.setOnClickListener {
            Log.d("UserEditFragment", "AuthCode toggle clicked, current state: $isAuthCodeVisible")
            isAuthCodeVisible = !isAuthCodeVisible
            updateAuthCodeVisibility()
        }

        // 初始化版本資訊
        initVersionInfo()

        // 設定檢查更新按鈕
        binding.buttonCheckUpdate.setThrottledClick {
            checkForUpdates(isManual = true)
        }

        // 設定自動檢查開關
        binding.checkboxAutoCheck.isChecked = updateManager.isAutoCheckEnabled()
        binding.checkboxAutoCheck.setOnCheckedChangeListener { _, isChecked ->
            updateManager.setAutoCheck(isChecked)
        }

        // 載入用戶資料
        loadUserProfile()
    }

    private fun loadUserProfile() {
        val key = userSessionProvider?.getCurrentUserFirebaseKey()
        if (key.isNullOrEmpty()) {
            requireContext().toast(AppConfig.Msg.REQUIRE_LOGIN_LOAD)
            return
        }

        FirebaseDatabase.getInstance(AppConfig.DB_URL)
            .reference.child("users").child(key)
            .get()
            .addOnSuccessListener { snap ->

                // 🔒【絕對安全檢查：view 已被銷毀 → 不更新 UI】
                if (!isAdded || view == null) {
                    Log.w("UserEditFragment", "View destroyed — skip UI update")
                    return@addOnSuccessListener
                }

                val account = snap.child("account").getValue(String::class.java) ?: ""
                val email = snap.child("email").getValue(String::class.java) ?: ""
                val phone = snap.child("phone").getValue(String::class.java) ?: ""

                val city = snap.child("city").getValue(String::class.java) ?: ""
                val district = snap.child("district").getValue(String::class.java) ?: ""

                originalAddress = "$city $district".trim()
                originalAuthCode =
                    snap.child("devicePasswords").getValue(String::class.java) ?: "無"

                // 🔒 再補一道保險
                if (!isAdded || view == null) return@addOnSuccessListener

                binding.textAccount.text = account
                binding.textEmail.text = email
                binding.textPhone.text = phone

                isAddressVisible = false
                isAuthCodeVisible = false

                updateAddressVisibility()
                updateAuthCodeVisibility()
            }
            .addOnFailureListener { e ->

                if (!isAdded || view == null) {
                    Log.w("UserEditFragment", "View destroyed — skip error UI update")
                    return@addOnFailureListener
                }

                requireContext().toast(AppConfig.Msg.LOAD_FAIL_PREFIX + e.message)
            }
    }

    /**
     * 更新地址顯示狀態
     */
    private fun updateAddressVisibility() {
        if (isAddressVisible) {
            // 顯示真實地址（同一行，用空格隔開）
            binding.textAddress.text = originalAddress
            binding.iconAddressToggle.setImageResource(R.drawable.ic_visibility)
        } else {
            // 隱藏時顯示遮罩
            binding.textAddress.text = "•••• ••••"
            binding.iconAddressToggle.setImageResource(R.drawable.ic_visibility_off)
        }
    }

    /**
     * 更新授權碼顯示狀態
     */
    private fun updateAuthCodeVisibility() {
        Log.d("UserEditFragment", "updateAuthCodeVisibility called, visible: $isAuthCodeVisible")
        if (isAuthCodeVisible) {
            // 顯示真實授權碼
            binding.textAuthorizationCode.text = originalAuthCode
            binding.iconAuthCodeToggle.setImageResource(R.drawable.ic_visibility)
        } else {
            // 顯示屏蔽文字
            binding.textAuthorizationCode.text =
                if (originalAuthCode != "無" && originalAuthCode.isNotEmpty()) "••••••" else "無"
            binding.iconAuthCodeToggle.setImageResource(R.drawable.ic_visibility_off)
        }
    }

    // ==================== 🔹 解除裝置綁定功能 ====================

    /**
     * 顯示解除裝置綁定確認對話框
     */
    private fun showUnbindDeviceDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("解除裝置綁定")
            .setMessage("解除裝置綁定後，此帳號將允許其他裝置登入，\n是否確認解除裝置綁定？")
            .setPositiveButton("確定") { dialog, _ ->
                dialog.dismiss()
                performUnbindDevice()
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    /**
     * 執行解除裝置綁定
     */
    private fun performUnbindDevice() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrEmpty()) {
            requireContext().toast("無法取得用戶資訊")
            return
        }

        // 顯示載入提示
        val loadingDialog = AlertDialog.Builder(requireContext())
            .setTitle("處理中")
            .setMessage("正在解除裝置綁定...")
            .setCancelable(false)
            .create()
        loadingDialog.show()

        // 呼叫 Repository 執行解除綁定
        repo.unbindDevice(
            uid = uid,
            onResult = { success, message ->
                loadingDialog.dismiss()

                if (success) {
                    requireContext().toast(message ?: "裝置綁定已解除")

                    // 可選：詢問用戶是否要登出
                    AlertDialog.Builder(requireContext())
                        .setTitle("解除綁定成功")
                        .setMessage("裝置綁定已解除。建議重新登入以確保帳號安全。是否現在登出?")
                        .setPositiveButton("立即登出") { _, _ ->
                            performLogout()
                        }
                        .setNegativeButton("稍後") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .create()
                        .show()
                } else {
                    requireContext().toast(message ?: "解除綁定失敗")
                }
            }
        )
    }

    /**
     * 執行登出
     */
    private fun performLogout() {
        // 清除 Firebase Auth
        FirebaseAuth.getInstance().signOut()

        // 清除 Session
        userSessionProvider?.setCurrentUserFirebaseKey(null)
        userSessionProvider?.updateLoginStatus(false)
        userSessionProvider?.setCurrentlyDisplayedScratchCardOrder(null)

        // 返回登入頁面或關閉當前 Activity
        requireActivity().finish()
        // 如果需要導航到特定的登入頁面，可以使用：
        // startActivity(Intent(requireContext(), LoginActivity::class.java))
    }

    // ==================== 變更密碼功能 ====================

    private fun showChangePasswordDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_change_password, null)

        val currentPasswordInput = dialogView.findViewById<EditText>(R.id.input_current_password)
        val newPasswordInput = dialogView.findViewById<EditText>(R.id.input_new_password)
        val confirmPasswordInput = dialogView.findViewById<EditText>(R.id.input_confirm_password)

        // 為密碼輸入框添加顯示/隱藏密碼功能
        currentPasswordInput.attachPasswordToggle(
            R.drawable.ic_visibility, R.drawable.ic_visibility_off
        )
        newPasswordInput.attachPasswordToggle(
            R.drawable.ic_visibility, R.drawable.ic_visibility_off
        )
        confirmPasswordInput.attachPasswordToggle(
            R.drawable.ic_visibility, R.drawable.ic_visibility_off
        )

        // 添加即時驗證
        newPasswordInput.doOnTextChanged { text, _, _, _ ->
            val pwd = text?.toString().orEmpty().trim()
            newPasswordInput.error =
                if (pwd.isEmpty() || ValidationRules.isValidPasswordLen(pwd)) null
                else AppConfig.Msg.ERR_PASSWORD_LEN
        }

        confirmPasswordInput.doOnTextChanged { text, _, _, _ ->
            val pwd = text?.toString().orEmpty().trim()
            val newPwd = newPasswordInput.text.toString().trim()
            confirmPasswordInput.error =
                if (pwd.isEmpty() || pwd == newPwd) null
                else "密碼不一致"
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("變更密碼")
            .setView(dialogView)
            .setPositiveButton("確定", null)
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val currentPassword = currentPasswordInput.text.toString().trim()
                val newPassword = newPasswordInput.text.toString().trim()
                val confirmPassword = confirmPasswordInput.text.toString().trim()

                // 驗證輸入
                when {
                    currentPassword.isEmpty() -> {
                        requireContext().toast("請輸入當前密碼")
                    }
                    newPassword.isEmpty() -> {
                        requireContext().toast("請輸入新密碼")
                    }
                    !ValidationRules.isValidPasswordLen(newPassword) -> {
                        requireContext().toast(AppConfig.Msg.ERR_PASSWORD_LEN)
                    }
                    newPassword != confirmPassword -> {
                        requireContext().toast("新密碼與確認密碼不一致")
                    }
                    else -> {
                        // 驗證通過，執行密碼變更
                        performPasswordChange(
                            dialog = dialog,
                            currentPassword = currentPassword,
                            newPassword = newPassword
                        )
                    }
                }
            }
        }

        dialog.show()
    }

    private fun performPasswordChange(
        dialog: AlertDialog,
        currentPassword: String,
        newPassword: String
    ) {
        val account = binding.textAccount.text.toString()

        if (account.isEmpty()) {
            requireContext().toast("無法取得帳號資訊")
            return
        }

        changePasswordViaApi(
            account = account,
            currentPassword = currentPassword.trim(),
            newPassword = newPassword.trim(),
            onSuccess = {
                requireContext().toast("密碼變更成功")
                dialog.dismiss()
            },
            onError = { errorMsg ->
                requireContext().toast(errorMsg)
            }
        )
    }

    /**
     * 透過 API 變更密碼
     */
    private fun changePasswordViaApi(
        account: String,
        currentPassword: String,
        newPassword: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        lifecycleScope.launch {
            try {
                val jsonObject = JSONObject().apply {
                    put("account", account)
                    put("currentPassword", currentPassword)
                    put("newPassword", newPassword)
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = jsonObject.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url("https://changepassword-qmvrvane7q-de.a.run.app")
                    .addHeader("X-App-Auth", BuildConfig.APP_SECRET)
                    .post(requestBody)
                    .build()

                httpClient.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        requireActivity().runOnUiThread {
                            onError("網路錯誤：${e.message}")
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            val responseBody = it.body?.string()
                            requireActivity().runOnUiThread {
                                if (it.isSuccessful) {
                                    onSuccess()
                                } else {
                                    try {
                                        val errorJson = JSONObject(responseBody ?: "{}")
                                        val errorMsg = errorJson.optString("error", "密碼變更失敗")
                                        onError(errorMsg)
                                    } catch (e: Exception) {
                                        onError("密碼變更失敗")
                                    }
                                }
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    onError("發生錯誤：${e.message}")
                }
            }
        }
    }

    // ==================== 版本更新相關方法 ====================

    /**
     * 初始化版本資訊顯示
     */
    private fun initVersionInfo() {
        // 顯示當前版本
        val versionName = BuildConfig.VERSION_NAME
        binding.textCurrentVersion.text = versionName

        // 顯示上次檢查時間
        updateLastCheckTime()
    }

    /**
     * 更新上次檢查時間顯示
     */
    private fun updateLastCheckTime() {
        val lastCheckTime = updateManager.getLastCheckTime()
        binding.textLastCheckTime.text = if (lastCheckTime > 0) {
            formatLastCheckTime(lastCheckTime)
        } else {
            "尚未檢查"
        }
    }

    /**
     * 格式化時間顯示
     */
    private fun formatLastCheckTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    /**
     * 檢查更新
     */
    private fun checkForUpdates(isManual: Boolean) {
        lifecycleScope.launch {
            try {
                if (isManual) {
                    requireContext().toast("正在檢查更新...")
                }

                when (val result = updateManager.checkUpdate(isManual)) {
                    is UpdateResult.NoUpdate -> {
                        updateLastCheckTime()
                        if (isManual) {
                            requireContext().toast("已是最新版本")
                        }
                    }

                    is UpdateResult.HasUpdate -> {
                        updateLastCheckTime()
                        showUpdateDialog(result.versionInfo)
                    }

                    is UpdateResult.Error -> {
                        updateLastCheckTime()
                        if (isManual) {
                            requireContext().toast("檢查失敗：${result.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                if (isManual) {
                    requireContext().toast("檢查更新時發生錯誤")
                }
            }
        }
    }

    /**
     * 顯示更新對話框（包含更新歷史）
     */
    private fun showUpdateDialog(versionInfo: com.champion.king.data.api.dto.VersionInfo) {
        // 取得目前版本資訊
        val currentVersionName = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (e: Exception) {
            "未知版本"
        }

        // 建立自訂 Dialog 佈局
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_update, null)

        val tvCurrentVersion = dialogView.findViewById<TextView>(R.id.tv_current_version)
        val tvLatestVersion = dialogView.findViewById<TextView>(R.id.tv_latest_version)
        val tvUpdateContent = dialogView.findViewById<TextView>(R.id.tv_update_content)

        // 設定版本資訊
        tvCurrentVersion.text = "目前版本：$currentVersionName"
        tvLatestVersion.text = "最新版本：${versionInfo.versionName}"

        // 格式化更新內容（使用共用工具類）
        val updateContent = UpdateHistoryFormatter.format(versionInfo)
        tvUpdateContent.text = updateContent

        // 建立 Dialog
        val builder = AlertDialog.Builder(requireContext())
            .setTitle("發現新版本")
            .setView(dialogView)
            .setPositiveButton("立即更新") { dialog, _ ->
                dialog.dismiss()
                startDownloadAndInstall(versionInfo.downloadUrl)
            }

        // 根據更新類型決定是否可取消
        if (versionInfo.updateType != "force") {
            builder.setNegativeButton("稍後提醒") { dialog, _ ->
                dialog.dismiss()
            }
            builder.setNeutralButton("跳過此版本") { _, _ ->
                updateManager.ignoreVersion(versionInfo.versionCode)
                requireContext().toast("已跳過此版本")
            }
            builder.setCancelable(true)
        } else {
            // 強制更新不可取消
            builder.setCancelable(false)
        }

        builder.create().show()
    }

    /**
     * 開始下載並安裝 APK
     */
    private fun startDownloadAndInstall(downloadUrl: String) {
        val progressDialog = android.app.ProgressDialog(requireContext()).apply {
            setTitle("正在下載更新")
            setMessage("下載進度：0%")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        val downloader = ApkDownloader(requireContext())

        downloader.downloadApk(
            downloadUrl = downloadUrl,
            onProgress = { progress ->
                requireActivity().runOnUiThread {
                    progressDialog.progress = progress
                    progressDialog.setMessage("下載進度：$progress%")
                }
            },
            onComplete = { success, message ->
                requireActivity().runOnUiThread {
                    progressDialog.dismiss()

                    if (success) {
                        requireContext().toast("下載完成，準備安裝")
                    } else {
                        requireContext().toast(message)
                    }
                }
            }
        )
    }
}