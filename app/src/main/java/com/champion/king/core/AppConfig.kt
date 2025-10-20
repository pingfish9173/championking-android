package com.champion.king.core.config

object AppConfig {
    // Firebase
    const val DB_URL =
        "https://sca3-69342-default-rtdb.asia-southeast1.firebasedatabase.app"

    object Prefs {
        // Login
        const val LOGIN_PREFS = "login_prefs"
        const val REMEMBER_ACCOUNT = "remember_account"
        const val REMEMBERED_ACCOUNT = "remembered_account"
    }

    object Msg {
        // 通用
        const val NO_INTERNET = "目前無網路，請檢查連線再試"
        const val LOAD_FAIL_PREFIX = "載入失敗："
        const val SAVE_SUCCESS = "已儲存用戶資訊。"
        const val SAVE_FAIL_PREFIX = "儲存失敗："
        const val REQUIRE_LOGIN_LOAD = "尚未登入，無法載入用戶資料。"
        const val REQUIRE_LOGIN_SAVE = "尚未登入，無法儲存用戶資料。"

        // 登入
        const val LOGIN_EMPTY = "請輸入帳號與密碼"
        const val LOGIN_FAIL = "帳號或密碼錯誤"

        // 註冊
        const val REGISTER_SUCCESS = "註冊成功，請登入"
        const val REGISTER_FAIL_PREFIX = "註冊失敗："
        const val DEVICE_NOT_FOUND = "授權碼不存在"
        const val DEVICE_NOT_AVAILABLE = "授權碼不可用"
        const val INPUT_DEVICE_REQUIRED = "請輸入授權碼"

        // 驗證訊息（與你現有規則一致）
        const val ERR_ACCOUNT_RULE = "6–20 碼，首字母為英文字，可含數字/底線/減號"
        const val ERR_PASSWORD_LEN = "密碼需 6–20 碼"
        const val ERR_EMAIL_FORMAT = "Email 格式不正確"
        const val ERR_PHONE_FORMAT = "手機需 09 開頭，共 10 碼"

        // 用戶編輯／忘記密碼
        const val RESET_SUCCESS = "密碼已重置，請重新登入，預設密碼為手機號碼"
        const val RESET_FAIL_PREFIX = "重置失敗："

        // 對話框相關
        const val FORGOT_PASSWORD_TITLE = "忘記密碼"
        const val BUTTON_CONFIRM = "確定"
        const val BUTTON_CANCEL = "取消"

        // 輸入提示
        const val HINT_ACCOUNT = "帳號"
        const val HINT_EMAIL = "Email"
        const val HINT_PHONE = "手機（09 開頭 10 碼）"

        // 錯誤訊息
        const val ERR_ACCOUNT_FORMAT = "帳號格式不符"

        // Settings 相關
        const val SETTINGS_SELECT_TYPE_REQUIRED = "請選擇刮刮卡類型"

    }
}
