package com.champion.king

import androidx.fragment.app.Fragment

// 定義一個介面，用於 Fragment 與其宿主 Activity 之間的通訊
interface UserSessionProvider {
    fun getCurrentUserFirebaseKey(): String?
    // 修正：確保 setCurrentUserFirebaseKey 已定義
    fun setCurrentUserFirebaseKey(key: String?)
    fun navigateToFragment(fragment: Fragment) // 新增導航方法
    fun updateLoginStatus(isLoggedIn: Boolean, username: String? = null, points: Int? = null) // 更新登入狀態

    // 新增方法來設定和獲取目前顯示的刮刮卡順序
    fun setCurrentlyDisplayedScratchCardOrder(order: Int?)
    fun getCurrentlyDisplayedScratchCardOrder(): Int?

    // 新增方法：設定目前正在使用的刮刮卡 (更新 inUsed 狀態)
    fun setCurrentlyInUseScratchCard(userFirebaseKey: String, serialNumberToSetInUse: String?)
}
