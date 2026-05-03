package com.champion.king

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.core.widget.doOnTextChanged
import com.champion.king.core.config.AppConfig
import com.champion.king.core.ui.BaseBindingFragment
import com.champion.king.data.AuthRepository
import com.champion.king.databinding.FragmentUserEditBinding
import com.champion.king.util.ValidationRules
import com.champion.king.util.attachPasswordToggle
import com.champion.king.util.setThrottledClick
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
import com.champion.king.util.ToastManager
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
            showToast(AppConfig.Msg.REQUIRE_LOGIN_LOAD)
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

                // ✅ 消費模式（POINT / RENTAL）
                val billingMode = snap.child("billingMode").getValue(String::class.java) ?: "POINT"
                val isRental = (billingMode == "RENTAL")
                val billingModeText = if (isRental) "租賃制" else "點數制"

                // ✅ 租賃資訊（用來顯示 ℹ️ 內容）
                val rentalStartAt = snap.child("rentalStartAt").getValue(Long::class.java)
                val rentalDays = snap.child("rentalDays").getValue(Int::class.java) ?: 0

                val isAdEnabled = snap.child("isAdEnabled").getValue(Boolean::class.java) ?: false
                val idleAdMinutes = snap.child("idleAdMinutes").getValue(Int::class.java) ?: 15
                val unlockAdPassword = snap.child("unlockAdPassword").getValue(String::class.java)

                // 💡 更新廣告 UI (改用 switchAdEnabled)
                binding.switchAdEnabled.setOnCheckedChangeListener(null) // 避免觸發 listener
                binding.switchAdEnabled.isChecked = isAdEnabled
                updateAdInfoUI(isAdEnabled, idleAdMinutes, unlockAdPassword)

                // 設定點擊事件 (改用 switchAdEnabled)
                binding.switchAdEnabled.setOnClickListener {
                    if (binding.switchAdEnabled.isChecked) {
                        // 打開開關時，強制傳入預設值 (15分鐘, 無密碼)
                        showAdSettingsDialog(15, null)
                    } else {
                        // 關閉開關時，停用廣告
                        updateAdSettingsToFirebase(false, 15, null)
                    }
                }

                originalAddress = "$city $district".trim()
                originalAuthCode = snap.child("devicePasswords").getValue(String::class.java) ?: "無"

                // 🔒 再補一道保險
                if (!isAdded || view == null) return@addOnSuccessListener

                binding.textAccount.text = account
                binding.textEmail.text = email
                binding.textPhone.text = phone

                // ✅ 你指定的顯示格式：左邊固定「消費模式：」 + 右邊也要顯示「消費模式：租賃制」
                // 因為你現在的 UI 已經有左邊固定 label，所以這裡照你要求「右邊顯示消費模式：租賃制」
                binding.textBillingMode.text = billingModeText

                // ✅ 租賃制 / 點數制 都顯示 ℹ️，點了依模式顯示不同內容
                binding.iconBillingModeInfo.visibility = View.VISIBLE
                binding.iconBillingModeInfo.setOnClickListener {
                    if (isRental) {
                        showRentalInfoDialog(rentalStartAt, rentalDays)
                    } else {
                        showPointTotalSpentDialog(account) // ✅ 點數制：顯示累計消費金額
                    }
                }

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

                showToast(AppConfig.Msg.LOAD_FAIL_PREFIX + e.message)
            }
    }

    private fun showAdSettingsDialog(currentMinutes: Int, currentPassword: String?) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_ad_settings, null)

        val editMinutes = dialogView.findViewById<EditText>(R.id.edit_idle_minutes)
        // 💡 關鍵魔術：把密碼的「圓點/星號」遮蔽效果強制關閉！
        editMinutes.transformationMethod = null
        val radioGroup = dialogView.findViewById<RadioGroup>(R.id.radio_group_password)
        val radioUsePassword = dialogView.findViewById<RadioButton>(R.id.radio_use_password)
        val editPassword = dialogView.findViewById<EditText>(R.id.edit_ad_password)

        // 載入預設值
        editMinutes.setText(currentMinutes.toString())
        editMinutes.setSelection(editMinutes.text.length)
        if (!currentPassword.isNullOrEmpty()) {
            radioUsePassword.isChecked = true
            editPassword.isEnabled = true
            editPassword.setText(currentPassword)
        }

        // 監聽 RadioButton 切換來開啟/關閉密碼輸入框
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            editPassword.isEnabled = (checkedId == R.id.radio_use_password)
            if (checkedId == R.id.radio_no_password) {
                editPassword.text.clear()
                editPassword.error = null
            }
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("廣告設定")
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("儲存", null) // 稍後覆寫以防止驗證失敗時自動關閉
            .setNegativeButton("取消") { d, _ ->
                // 取消的話，把 CheckBox 退回原本狀態
                binding.switchAdEnabled.isChecked = false
                d.dismiss()
            }
            .create()

        // 💡 加上這行魔法：強制隱藏對話框開啟時自動彈出的軟體鍵盤
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        dialog.setOnShowListener {
            val button = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                // 💡 1. 取得字串並去除前後空白
                val minutesStr = editMinutes.text.toString().trim()

                // 💡 2. 檢查是否留空
                if (minutesStr.isEmpty()) {
                    editMinutes.error = "時間不能留空"
                    return@setOnClickListener
                }

                // 💡 3. 轉為數字並檢查範圍 (3 ~ 99999)
                val minutes = minutesStr.toIntOrNull()
                // 💡 將原本的 minutes < 1 改成 minutes < 3
                if (minutes == null || minutes < 3 || minutes > 99999) {
                    // 💡 同時把錯誤提示的文字更新
                    editMinutes.error = "請輸入 3 到 99999 的數字"
                    return@setOnClickListener
                }

                // --- 下面保留你原本的密碼驗證邏輯 ---
                var finalPassword: String? = null
                if (radioUsePassword.isChecked) {
                    val pwd = editPassword.text.toString()
                    // 驗證：只能是數字，且長度 4~20
                    if (pwd.length !in 4..20 || !pwd.all { it.isDigit() }) {
                        editPassword.error = "密碼必須為4~20碼純數字"
                        return@setOnClickListener
                    }
                    finalPassword = pwd
                }

                // 驗證通過，寫入資料庫
                updateAdSettingsToFirebase(true, minutes, finalPassword)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun updateAdSettingsToFirebase(enabled: Boolean, minutes: Int, password: String?) {
        val uid = userSessionProvider?.getCurrentUserFirebaseKey() ?: return

        // 💡 根據 enabled 狀態動態建立要更新的 Map
        val updates = mutableMapOf<String, Any?>()
        updates["isAdEnabled"] = enabled

        if (enabled) {
            // 啟用時：正常寫入分鐘數與密碼
            updates["idleAdMinutes"] = minutes
            updates["unlockAdPassword"] = password
        } else {
            // 停用時：將這兩個欄位設為 null，Firebase 就會自動將它們刪除
            updates["idleAdMinutes"] = null
            updates["unlockAdPassword"] = null
        }

        FirebaseDatabase.getInstance(AppConfig.DB_URL).reference
            .child("users").child(uid)
            .updateChildren(updates)
            .addOnSuccessListener {
                updateAdInfoUI(enabled, minutes, password)

                if (enabled) {
                    showToast("廣告設定已儲存")
                } else {
                    showToast("廣告已停用")
                }

                // 同步更新 MainActivity 的 currentUser 物件
                val mainActivity = activity as? MainActivity
                mainActivity?.let { activity ->
                    val user = activity.getCurrentUser()
                    user?.isAdEnabled = enabled
                    if (enabled) {
                        user?.idleAdMinutes = minutes
                        user?.unlockAdPassword = password
                    } else {
                        // 停用時，一併清空記憶體中的暫存資料，避免重置計時器時出錯
                        user?.idleAdMinutes = 15 // 給回預設值
                        user?.unlockAdPassword = null
                    }
                    activity.resetIdleTimer() // 強制重置計時器，讓新設定立刻生效
                }
            }
            .addOnFailureListener { e ->
                showToast("廣告設定更新失敗：${e.message}")
                binding.switchAdEnabled.isChecked = !enabled // 失敗時退回狀態
            }
    }

    private fun updateAdInfoUI(enabled: Boolean, minutes: Int, password: String?) {
        if (enabled) {
            binding.textAdSettingsInfo.visibility = View.VISIBLE
            val pwdDisplay = if (password.isNullOrEmpty()) "不使用密碼" else password
            // 修改了這裡的字串格式
            binding.textAdSettingsInfo.text = "閒置 ${minutes} 分鐘啟用廣告\n解除廣告密碼：$pwdDisplay"

            // 可以選擇在這裡設定字體大小，或是直接在 xml 裡面設定
            binding.textAdSettingsInfo.textSize = 12f
        } else {
            binding.textAdSettingsInfo.visibility = View.GONE
        }
    }

    private fun showRentalInfoDialog(rentalStartAt: Long?, rentalDays: Int) {
        val startAt = rentalStartAt ?: 0L
        if (startAt <= 0L || rentalDays <= 0) {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("租賃資訊")
                .setMessage("租賃資料不足，請確認起算時間與天數是否正確。")
                .setPositiveButton("關閉", null)
                .show()
            return
        }

        val dayMillis = 24L * 60L * 60L * 1000L
        val expiryAt = startAt + rentalDays.toLong() * dayMillis
        val now = System.currentTimeMillis()
        val remainingMs = (expiryAt - now).coerceAtLeast(0L)

        val remainingDays = remainingMs / dayMillis
        val remainingHours = (remainingMs % dayMillis) / (60L * 60L * 1000L)
        val remainingMinutes = (remainingMs % (60L * 60L * 1000L)) / (60L * 1000L)

        val locale = java.util.Locale.TAIWAN
        val fmt = java.text.SimpleDateFormat("yyyy年M月d日HH時mm分", locale)

        val startText = fmt.format(java.util.Date(startAt))
        val expiryText = fmt.format(java.util.Date(expiryAt))
        val remainingText = "${remainingDays}日${remainingHours}時${remainingMinutes}分"

        // ✅ 組合完整訊息文字
        val fullText =
            "起算時間：$startText\n" +
                    "到期時間：$expiryText\n" +
                    "剩餘時間：$remainingText"

        // ✅ 使用 SpannableString 只標紅「剩餘時間的數值」
        val spannable = android.text.SpannableString(fullText)

        val target = "剩餘時間：$remainingText"
        val startIndex = fullText.indexOf(target)
        if (startIndex >= 0) {
            val valueStart = startIndex + "剩餘時間：".length
            val valueEnd = valueStart + remainingText.length

            spannable.setSpan(
                android.text.style.ForegroundColorSpan(
                    androidx.core.content.ContextCompat.getColor(
                        requireContext(),
                        android.R.color.holo_red_dark
                    )
                ),
                valueStart,
                valueEnd,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("租賃資訊")
            .setMessage(spannable) // ✅ 注意這裡改成 Spannable
            .setPositiveButton("關閉", null)
            .show()
    }

    /**
     * 點數制：顯示累計消費金額（改用後端 API：getTotalSpentByAccount）
     */
    private fun showPointTotalSpentDialog(account: String) {
        if (account.isBlank()) {
            showToast("無法取得帳號資訊")
            return
        }

        // 先顯示載入中
        val loadingDialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("累計消費金額")
            .setMessage("查詢中...")
            .setCancelable(false)
            .create()

        loadingDialog.show()

        try {
            val jsonObject = JSONObject().apply {
                put("account", account)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonObject.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("https://gettotalspentbyaccount-qmvrvane7q-de.a.run.app")
                .addHeader("X-App-Auth", BuildConfig.APP_SECRET)
                .post(requestBody)
                .build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("UserEditFragment", "getTotalSpentByAccount failed", e)
                    activity?.runOnUiThread {
                        if (!isAdded || view == null) return@runOnUiThread
                        if (loadingDialog.isShowing) loadingDialog.dismiss()
                        showToast("查詢失敗：${e.message}")
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()

                        activity?.runOnUiThread {
                            if (!isAdded || view == null) return@runOnUiThread
                            if (loadingDialog.isShowing) loadingDialog.dismiss()

                            if (!resp.isSuccessful) {
                                // 盡量把後端回的 error 印出來
                                val errMsg = try {
                                    JSONObject(bodyStr).optString("error", "查詢失敗")
                                } catch (_: Exception) {
                                    "查詢失敗"
                                }
                                showToast(errMsg)
                                return@runOnUiThread
                            }

                            try {
                                val json = JSONObject(bodyStr)
                                val total = json.optLong("totalMoneyAdded", 0L)

                                val formatted = java.text.NumberFormat
                                    .getNumberInstance(java.util.Locale.TAIWAN)
                                    .format(total)

                                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                    .setTitle("累計消費金額")
                                    .setMessage("NT$ $formatted")
                                    .setPositiveButton("關閉", null)
                                    .show()

                            } catch (e: Exception) {
                                Log.e("UserEditFragment", "parse totalMoneyAdded failed", e)
                                showToast("回傳解析失敗")
                            }
                        }
                    }
                }
            })

        } catch (e: Exception) {
            Log.e("UserEditFragment", "build request failed", e)
            if (loadingDialog.isShowing) loadingDialog.dismiss()
            showToast("發生錯誤：${e.message}")
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
            showToast("無法取得用戶資訊")
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
                    showToast(message ?: "裝置綁定已解除")

                    // 🔥🔥 不再跳出任何視窗，直接強制登出
                    performLogout()
                } else {
                    showToast(message ?: "解除綁定失敗")
                }
            }
        )
    }

    /**
     * 執行登出
     */
    private fun performLogout() {
        (activity as? MainActivity)?.performLogout()
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
            ToastManager.setHostWindow(dialog.window)
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val currentPassword = currentPasswordInput.text.toString().trim()
                val newPassword = newPasswordInput.text.toString().trim()
                val confirmPassword = confirmPasswordInput.text.toString().trim()

                // 驗證輸入
                when {
                    currentPassword.isEmpty() -> {
                        showToast("請輸入當前密碼")
                    }
                    newPassword.isEmpty() -> {
                        showToast("請輸入新密碼")
                    }
                    !ValidationRules.isValidPasswordLen(newPassword) -> {
                        showToast(AppConfig.Msg.ERR_PASSWORD_LEN)
                    }
                    newPassword != confirmPassword -> {
                        showToast("新密碼與確認密碼不一致")
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

        dialog.setOnDismissListener {
            ToastManager.clearHostWindow()
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
            showToast("無法取得帳號資訊")
            return
        }

        changePasswordViaApi(
            account = account,
            currentPassword = currentPassword.trim(),
            newPassword = newPassword.trim(),
            onSuccess = {
                showToast("密碼變更成功")
                dialog.dismiss()
            },
            onError = { errorMsg ->
                showToast(errorMsg)
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
                        activity?.runOnUiThread {
                            onError("網路錯誤：${e.message}")
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            val responseBody = it.body?.string()
                            activity?.runOnUiThread {
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
                activity?.runOnUiThread {
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
        binding.textCurrentVersion.text = "v${versionName}"

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
                    showToast("正在檢查更新...")
                }

                when (val result = updateManager.checkUpdate(isManual)) {
                    is UpdateResult.NoUpdate -> {
                        updateLastCheckTime()
                        if (isManual) {
                            showToast("已是最新版本")
                        }
                    }

                    is UpdateResult.HasUpdate -> {
                        updateLastCheckTime()
                        showUpdateDialog(result.versionInfo)
                    }

                    is UpdateResult.Error -> {
                        updateLastCheckTime()
                        if (isManual) {
                            showToast("檢查失敗：${result.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                if (isManual) {
                    showToast("檢查更新時發生錯誤")
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
                showToast("已跳過此版本")
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
                activity?.runOnUiThread {
                    progressDialog.progress = progress
                    progressDialog.setMessage("下載進度：$progress%")
                }
            },
            onComplete = { success, message ->
                activity?.runOnUiThread {
                    progressDialog.dismiss()

                    if (success) {
                        showToast("下載完成，準備安裝")
                    } else {
                        showToast(message)
                    }
                }
            }
        )
    }

    private fun showToast(message: String) {
        activity?.let {
            ToastManager.show(it, message)
        }
    }
}