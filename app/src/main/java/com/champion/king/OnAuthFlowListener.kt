package com.champion.king

import com.champion.king.model.User

// 定義一個介面，用於 Fragment 與 Activity 之間的通訊
interface OnAuthFlowListener {
    fun onLoginSuccess(loggedInUser: User)
    fun onLoginFailed()
    fun onNavigateToRegister()
}
