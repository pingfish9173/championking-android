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
import com.champion.king.security.PasswordUtils
import com.champion.king.util.ValidationRules
import com.champion.king.util.attachPasswordToggle
import com.champion.king.util.guardOnline
import com.champion.king.util.setThrottledClick
import com.champion.king.util.toast
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class UserEditFragment : BaseBindingFragment<FragmentUserEditBinding>() {

    private var userSessionProvider: UserSessionProvider? = null
    private val repo by lazy {
        AuthRepository(FirebaseDatabase.getInstance(AppConfig.DB_URL).reference)
    }

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

        // 載入用戶資料
        loadUserProfile()
    }

    private fun loadUserProfile() {
        val key = userSessionProvider?.getCurrentUserFirebaseKey()
        if (key.isNullOrEmpty()) {
            requireContext().toast(AppConfig.Msg.REQUIRE_LOGIN_LOAD)
            return
        }

        FirebaseDatabase.getInstance(AppConfig.DB_URL).reference.child("users").child(key)
            .get()
            .addOnSuccessListener { snap ->
                val account = snap.child("account").getValue(String::class.java) ?: ""
                val email = snap.child("email").getValue(String::class.java) ?: ""
                val phone = snap.child("phone").getValue(String::class.java) ?: ""

                val city = snap.child("city").getValue(String::class.java) ?: ""
                val district = snap.child("district").getValue(String::class.java) ?: ""

                originalAddress = "$city $district".trim()
                originalAuthCode = snap.child("devicePasswords").getValue(String::class.java) ?: "無"

                binding.textAccount.text = account
                binding.textEmail.text = email
                binding.textPhone.text = phone

                isAddressVisible = false
                isAuthCodeVisible = false

                updateAddressVisibility()
                updateAuthCodeVisibility()
            }
            .addOnFailureListener { e ->
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
            binding.textAuthorizationCode.text = if (originalAuthCode != "無" && originalAuthCode.isNotEmpty()) "••••••" else "無"
            binding.iconAuthCodeToggle.setImageResource(R.drawable.ic_visibility_off)
        }
    }

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
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                handleChangePassword(
                    currentPasswordInput.text.toString(),
                    newPasswordInput.text.toString(),
                    confirmPasswordInput.text.toString(),
                    dialog
                )
            }
        }

        dialog.show()
    }

    private fun handleChangePassword(
        currentPassword: String,
        newPassword: String,
        confirmPassword: String,
        dialog: AlertDialog
    ) = requireContext().guardOnline {
        val key = userSessionProvider?.getCurrentUserFirebaseKey()
        if (key.isNullOrEmpty()) {
            requireContext().toast(AppConfig.Msg.REQUIRE_LOGIN_SAVE)
            return@guardOnline
        }

        // 驗證輸入
        when {
            currentPassword.trim().isEmpty() -> {
                requireContext().toast("請輸入現在密碼")
                return@guardOnline
            }
            newPassword.trim().isEmpty() -> {
                requireContext().toast("請輸入新密碼")
                return@guardOnline
            }
            !ValidationRules.isValidPasswordLen(newPassword.trim()) -> {
                requireContext().toast(AppConfig.Msg.ERR_PASSWORD_LEN)
                return@guardOnline
            }
            newPassword.trim() != confirmPassword.trim() -> {
                requireContext().toast("新密碼與確認密碼不一致")
                return@guardOnline
            }
        }

        // 驗證現在密碼
        verifyCurrentPassword(key, currentPassword.trim()) { isValid, message ->
            if (isValid) {
                // 更新密碼
                updatePassword(key, newPassword.trim()) { success, msg ->
                    if (success) {
                        requireContext().toast("密碼變更成功")
                        dialog.dismiss()
                    } else {
                        requireContext().toast("密碼變更失敗：$msg")
                    }
                }
            } else {
                requireContext().toast("現在密碼錯誤：$message")
            }
        }
    }

    private fun verifyCurrentPassword(
        userKey: String,
        inputPassword: String,
        callback: (Boolean, String?) -> Unit
    ) {
        val database = FirebaseDatabase.getInstance(AppConfig.DB_URL).reference
        database.child("users").child(userKey)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val salt = snapshot.child("salt").getValue(String::class.java)
                    val storedHash = snapshot.child("passwordHash").getValue(String::class.java)

                    if (salt.isNullOrEmpty() || storedHash.isNullOrEmpty()) {
                        callback(false, "用戶資料不完整")
                        return
                    }

                    val inputHash = PasswordUtils.sha256Hex(salt, inputPassword)
                    val isValid = inputHash.equals(storedHash, ignoreCase = true)

                    callback(isValid, if (isValid) null else "密碼錯誤")
                }

                override fun onCancelled(error: DatabaseError) {
                    callback(false, "驗證失敗：${error.message}")
                }
            })
    }

    private fun updatePassword(
        userKey: String,
        newPassword: String,
        callback: (Boolean, String?) -> Unit
    ) {
        val database = FirebaseDatabase.getInstance(AppConfig.DB_URL).reference

        // 生成新的 salt 和 hash
        val salt = PasswordUtils.generateSaltBase64()
        val newPasswordHash = PasswordUtils.sha256Hex(salt, newPassword)

        val updates = hashMapOf<String, Any>(
            "salt" to salt,
            "passwordHash" to newPasswordHash
        )

        database.child("users").child(userKey)
            .updateChildren(updates)
            .addOnSuccessListener {
                callback(true, null)
            }
            .addOnFailureListener { e ->
                callback(false, e.message)
            }
    }
}