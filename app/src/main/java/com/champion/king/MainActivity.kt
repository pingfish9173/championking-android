package com.champion.king

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.champion.king.model.ScratchCard
import com.champion.king.model.User
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*
import androidx.lifecycle.lifecycleScope
import com.champion.king.util.ApkDownloader
import kotlinx.coroutines.launch
import com.champion.king.util.UpdateManager
import com.champion.king.util.UpdateResult
import com.champion.king.data.AuthRepository
import com.champion.king.util.ToastManager
import com.champion.king.util.UpdateHistoryFormatter
import com.google.firebase.auth.FirebaseAuth
import androidx.activity.OnBackPressedCallback

class MainActivity : AppCompatActivity(), OnAuthFlowListener, UserSessionProvider {

    // ====== UI Mode ======
    private enum class Mode { MASTER, PLAYER }

    private var mode: Mode = Mode.MASTER

    // ====== Master views ======
    private lateinit var currentTimeTextViewMaster: TextView
    private lateinit var userNamePointsTextViewMaster: TextView
    private lateinit var configButtonMaster: ImageView
    private lateinit var logoutButtonMaster: TextView
    private lateinit var bagButtonMaster: ImageView
    private lateinit var shopButtonMaster: ImageView
    private lateinit var userButtonMaster: ImageView
    private lateinit var buttonScratchCardPasswordMaster: Button
    private lateinit var buttonMasterPlayerSwitchMaster: Button
    private lateinit var watermarkOverlayContainerMaster: FrameLayout
    private lateinit var masterModeButtonsContainerMaster: LinearLayout
    private lateinit var userInfoContainerMaster: FrameLayout
    private lateinit var prizeInfoContainerMaster: LinearLayout
    private lateinit var fragmentContainerMaster: FrameLayout
    private lateinit var specialPrizeTextViewMaster: TextView
    private lateinit var grandPrizeTextViewMaster: LinearLayout

    // ====== Player views (nullable because not always in this layout) ======
    private var currentTimeTextViewPlayer: TextView? = null
    private var prizeInfoTextViewPlayer: TextView? = null // 保留欄位（若後續需要）
    private var giveawayCountTextViewPlayer: TextView? = null
    private var buttonNextVersionPlayer: Button? = null
    private var buttonKeyLoginPlayer: ImageView? = null
    private var fragmentContainerPlayer: FrameLayout? = null
    private var watermarkOverlayContainerPlayer: FrameLayout? = null
    private var specialPrizeTextViewPlayer: TextView? = null
    private var grandPrizeTextViewPlayer: LinearLayout? = null

    // ====== Time updater ======
    private val handler = Handler(Looper.getMainLooper())

    // === 廣告閒置顯示機制 ===
    private var lastInteractionTime: Long = System.currentTimeMillis()
    private val idleTimeoutMillis = 15 * 60 * 1000L // 15分鐘
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { showAdPoster() }

    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            updateCurrentTime()
            handler.postDelayed(this, 1000)
        }
    }
    private lateinit var taiwanSdf: SimpleDateFormat

    // ====== Data / Session ======
    private lateinit var database: DatabaseReference
    private var currentUser: User? = null
    private var currentlyDisplayedScratchCardOrder: Int? = null
    private lateinit var versionInfoTextViewMaster: TextView
    // 更新管理器
    private val updateManager by lazy { UpdateManager(this) }

    // ====== Update Auto Check Trigger/Throttle ======
    // 避免「更新對話框」在短時間內重複彈出造成卡住體驗
    private var isUpdateDialogShowing: Boolean = false

    // 你希望取消 5 分鐘節流；但完全不節流很容易在「多個觸發點連續命中」時重複彈窗。
    // 這裡改成 1 分鐘（60_000ms）。如果你真的想完全取消，可改為 0L。
    private val updateCheckThrottleMs: Long = 60_000L

    private fun triggerAutoUpdateCheck(reason: String, force: Boolean = false) {
        if (!updateManager.isAutoCheckEnabled()) return
        if (isUpdateDialogShowing) {
            Log.d(TAG, "Update check skipped: dialog already showing. reason=$reason")
            return
        }

        val now = System.currentTimeMillis()
        val last = updateManager.getLastCheckTime()
        val diff = now - last

        if (!force && updateCheckThrottleMs > 0 && diff < updateCheckThrottleMs) {
            Log.d(TAG, "Update check throttled (${diff}ms < ${updateCheckThrottleMs}ms). reason=$reason")
            return
        }

        Log.d(TAG, "Trigger update check. reason=$reason")
        checkUpdateInBackground()
    }


    // === Force Logout 相關 ===
    private var forceLogoutListener: ValueEventListener? = null
    private var forceLogoutRef: DatabaseReference? = null

    private val authRepository by lazy {
        AuthRepository(FirebaseDatabase.getInstance(DB_URL).reference)
    }

    // 如果你已有 AppConfig 可置換此常數，避免重複字串
    private val DB_URL =
        "https://sca3-69342-default-rtdb.asia-southeast1.firebasedatabase.app"

    // ====== Lifecycle ======
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ⛔ 全面禁用返回鍵（相容舊版 androidx）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d("MainActivity", "Back key disabled")
                // 什麼都不做，直接吃掉 Back 鍵
            }
        })

        // 🎨 立即切換回正常主題（移除啟動海報背景）
        setTheme(R.style.Theme_A3)

        // 🔹 避免螢幕自動休眠或關閉
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // 時間格式（台北時區）
        taiwanSdf = try {
            SimpleDateFormat("yyyy年M月d日\nHH:mm:ss", Locale.TAIWAN).apply {
                timeZone = TimeZone.getTimeZone("Asia/Taipei")
            }
        } catch (e: Exception) {
            Log.e(TAG, "初始化 SimpleDateFormat 失敗: ${e.message}", e)
            SimpleDateFormat("yyyy年M月d日\nHH:mm:ss", Locale.getDefault())
        }

        database = FirebaseDatabase.getInstance(DB_URL).reference

        // 初始顯示「台主」佈局 + Login
        render(Mode.MASTER)
        if (savedInstanceState == null) {
            loadFragment(LoginFragment(), containerIdFor(Mode.MASTER))
        }
        updateCurrentTime()
        enableImmersiveMode()
        resetIdleTimer() // 啟動閒置監測計時
        checkUpdateOnStart()
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateTimeRunnable)
        // ✅ 不再在 onResume 做更新檢查，改由 3 個明確事件觸發（開啟未登入 / 登入成功 / 玩家切回台主成功）
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateTimeRunnable)
        idleHandler.removeCallbacks(idleRunnable) // 停止閒置檢查
    }

    // ====== Rendering ======
    private fun render(target: Mode) {
        mode = target
        when (target) {
            Mode.MASTER -> {
                setContentView(R.layout.activity_main)
                initMasterViews()
                updateCurrentTime()
                updateVersionInfo()
                updateWatermarkDisplay(currentUser != null)
                if (currentUser != null) {
                    updateUserInfoDisplay(currentUser!!)
                    currentUser!!.firebaseKey?.let { fetchAndDisplayPrizeInfo(it, isMaster = true) }
                } else {
                    userNamePointsTextViewMaster.text = "請登入/註冊"
                    updatePrizeInfo(
                        specialPrizeTextViewMaster,
                        grandPrizeTextViewMaster,
                        null, null
                    )
                }
                unlockAppFromScreen()
            }

            Mode.PLAYER -> {
                setContentView(R.layout.player_main)
                initPlayerViews()
                updateCurrentTime()
                enableImmersiveMode()
                currentUser?.firebaseKey?.let { key ->
                    fetchAndDisplayPrizeInfo(key, isMaster = false)
                    fetchAndDisplayClawsGiveawayInfo(key, giveawayCountTextViewPlayer)
                } ?: run {
                    // 未登入時清空顯示
                    specialPrizeTextViewPlayer?.let {
                        updatePrizeInfo(it, grandPrizeTextViewPlayer ?: it, null, null)
                    }
                    updateClawsGiveawayInfo("scratch",0, 0, giveawayCountTextViewPlayer)
                }
                // 切玩家頁面即載入顯示頁
                loadFragment(ScratchCardPlayerFragment(), containerIdFor(Mode.PLAYER))
                ToastManager.show(this, "已切換至玩家頁面")
                Log.d(TAG, "已切換至玩家頁面。")
                lockAppToScreen()
            }
        }
    }

    private fun initMasterViews() {
        currentTimeTextViewMaster = findViewById(R.id.current_time_text_view_master)
        userNamePointsTextViewMaster = findViewById(R.id.user_name_points_text_view_master)
        configButtonMaster = findViewById(R.id.config_button_master)
        logoutButtonMaster = findViewById(R.id.logout_button_master)
        bagButtonMaster = findViewById(R.id.bag_button_master)
        shopButtonMaster = findViewById(R.id.shop_button_master)
        userButtonMaster = findViewById(R.id.user_button_master)
        specialPrizeTextViewMaster = findViewById(R.id.special_prize_text_view_master)
        grandPrizeTextViewMaster = findViewById(R.id.grand_prize_text_view_master)
        buttonScratchCardPasswordMaster = findViewById(R.id.button_scratch_card_password_master)
        buttonMasterPlayerSwitchMaster = findViewById(R.id.button_master_player_switch_master)
        watermarkOverlayContainerMaster = findViewById(R.id.watermark_overlay_container_master)
        masterModeButtonsContainerMaster = findViewById(R.id.master_mode_buttons_container_master)
        userInfoContainerMaster = findViewById(R.id.user_info_container_master)
        prizeInfoContainerMaster = findViewById(R.id.prize_info_container_master)
        fragmentContainerMaster = findViewById(R.id.fragment_container_master)
        versionInfoTextViewMaster = findViewById(R.id.version_info_text_view_master)

        // 台主頁面的 Home 按鈕 - 回到首頁
        findViewById<ImageView>(R.id.home_button_master).setOnClickListener {
            Log.d(TAG, "台主頁面 Home button clicked - 回到首頁")
            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            if (currentUser != null) {
                loadFragment(ScratchCardDisplayFragment(), containerIdFor(Mode.MASTER))
            } else {
                loadFragment(LoginFragment(), containerIdFor(Mode.MASTER))
            }
        }

        // 登出
        logoutButtonMaster.setOnClickListener {
            Log.d(TAG, "登出按鈕被點擊！")
            showLogoutConfirmationDialog()
        }

        val protectedClick = View.OnClickListener { v ->
            if (!ensureLoggedIn()) return@OnClickListener
            when (v.id) {
                R.id.bag_button_master -> {
                    Log.d(TAG, "Bag button clicked!")
                    clearRemainingScratchesDisplayOnMaster()  // 🧹 清空剩餘刮數
                    loadFragment(BackpackFragment(), containerIdFor(Mode.MASTER))
                }
                R.id.shop_button_master -> {
                    Log.d(TAG, "Shop button clicked!")
                    clearRemainingScratchesDisplayOnMaster()  // 🧹 清空剩餘刮數
                    loadFragment(ShopFragment(), containerIdFor(Mode.MASTER))
                }
                R.id.user_button_master -> {
                    Log.d(TAG, "User button clicked!")
                    clearRemainingScratchesDisplayOnMaster()  // 🧹 清空剩餘刮數
                    loadFragment(UserEditFragment(), containerIdFor(Mode.MASTER))
                }
                R.id.config_button_master -> {
                    loadFragment(SettingsFragment(), containerIdFor(Mode.MASTER))
                }
            }
        }
        bagButtonMaster.setOnClickListener(protectedClick)
        shopButtonMaster.setOnClickListener(protectedClick)
        userButtonMaster.setOnClickListener(protectedClick)
        configButtonMaster.setOnClickListener(protectedClick)

        // 遊戲協議按鈕
        findViewById<ImageView>(R.id.pad_button_master).setOnClickListener {
            Log.d(TAG, "Pad button clicked! 載入遊戲協議頁面")
            clearRemainingScratchesDisplayOnMaster()  // 🧹 清空剩餘刮數
            loadFragment(AboutTabletFragment(), containerIdFor(Mode.MASTER))
        }

        // 換版密碼
        buttonScratchCardPasswordMaster.setOnClickListener {
            if (!ensureLoggedIn()) return@setOnClickListener
            showPasswordInputDialog()
        }
        buttonScratchCardPasswordMaster.isEnabled = currentUser != null

        // 切換玩家頁面
        buttonMasterPlayerSwitchMaster.setOnClickListener {
            if (!ensureLoggedIn()) return@setOnClickListener
            showPlayerModeConfirmationDialog()
        }

        // 顯示區塊
        logoutButtonMaster.visibility = if (currentUser == null) View.GONE else View.VISIBLE
        masterModeButtonsContainerMaster.visibility = View.VISIBLE
        userInfoContainerMaster.visibility = View.VISIBLE
        prizeInfoContainerMaster.visibility = View.VISIBLE

        updateVersionInfo()
    }

    private fun updateVersionInfo() {
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = packageInfo.versionName

            // 確保格式為 V1.0.1
            val versionText = "V$versionName"

            versionInfoTextViewMaster.text = versionText

            Log.d(TAG, "版本資訊已更新: $versionText")
        } catch (e: Exception) {
            Log.e(TAG, "取得版本資訊失敗: ${e.message}", e)
            versionInfoTextViewMaster.text = "V1.0.0"
        }
    }

    private fun showPlayerModeConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("切換至玩家頁面")
            .setMessage("確定要切換至玩家頁面嗎？")
            .setPositiveButton("確定") { dialog, _ ->
                render(Mode.PLAYER)
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun initPlayerViews() {
        currentTimeTextViewPlayer = findViewById(R.id.current_time_text_view_player)
        specialPrizeTextViewPlayer = findViewById(R.id.special_prize_text_view_player)
        grandPrizeTextViewPlayer = findViewById(R.id.grand_prize_text_view_player)
        giveawayCountTextViewPlayer = findViewById(R.id.giveaway_count_text_view_player)
        buttonNextVersionPlayer = findViewById(R.id.button_next_version_player)
        fragmentContainerPlayer = findViewById(R.id.main_content_container_player)
        watermarkOverlayContainerPlayer = findViewById(R.id.watermark_overlay_container_player)

        // 玩家頁面的 Home 按鈕 - 需要輸入帳號密碼才能回到台主頁面
        findViewById<ImageView>(R.id.home_button_player).setOnClickListener {
            Log.d(TAG, "玩家頁面 Home button clicked - 顯示登入對話框")
            showPlayerToMasterLoginDialog()
        }

        buttonNextVersionPlayer?.setOnClickListener { showNextVersionPasswordInputDialog() }
    }

    private fun containerIdFor(target: Mode): Int =
        if (target == Mode.MASTER) R.id.fragment_container_master else R.id.main_content_container_player

    private fun containerIdForCurrent(): Int = containerIdFor(mode)

    // ====== Time / Watermark ======
    private fun updateCurrentTime() {
        val text = try {
            taiwanSdf.format(Date())
        } catch (e: Exception) {
            Log.e(TAG, "更新時間顯示失敗: ${e.message}", e)
            "時間載入錯誤"
        }

        when (mode) {
            Mode.MASTER -> if (::currentTimeTextViewMaster.isInitialized) {
                currentTimeTextViewMaster.text = text
            }

            Mode.PLAYER -> currentTimeTextViewPlayer?.text = text
        }
    }

    private fun updateWatermarkDisplay(isLoggedIn: Boolean) {
        val watermarkContainer =
            if (mode == Mode.MASTER) watermarkOverlayContainerMaster else watermarkOverlayContainerPlayer

        watermarkContainer?.removeAllViews()
        if (!isLoggedIn) {
            watermarkContainer?.visibility = View.GONE
            return
        }

        val message = "台主頁面，請記得切換玩家頁面"
        val positions = listOf(
            Gravity.TOP or Gravity.START,
            Gravity.TOP or Gravity.END,
            Gravity.CENTER,
            Gravity.BOTTOM or Gravity.START,
            Gravity.BOTTOM or Gravity.END
        )

        val rotationAngle = -15f
        val textColor = ContextCompat.getColor(this, R.color.light_grey_watermark)
        val textSizeSp = 36f
        val textAlpha = 0.3f

        positions.forEach { gravity ->
            val tv = TextView(this).apply {
                text = message
                setTextColor(textColor)
                textSize = textSizeSp
                rotation = rotationAngle
                alpha = textAlpha
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    this.gravity = gravity
                    when (gravity) {
                        Gravity.TOP or Gravity.START -> setMargins(40, 50, 30, 30)
                        Gravity.TOP or Gravity.END -> setMargins(30, 50, 40, 30)
                        Gravity.BOTTOM or Gravity.START -> setMargins(40, 30, 30, 50)
                        Gravity.BOTTOM or Gravity.END -> setMargins(30, 30, 40, 50)
                        else -> setMargins(20, 20, 20, 20)
                    }
                }
            }
            watermarkContainer?.addView(tv)
        }
        watermarkContainer?.visibility = View.VISIBLE
    }

    /**
     * 進入「商城 / 背包 / 用戶資訊 / 更新紀錄」這 4 個頁面時，
     * 要清空並隱藏台主左下角的「剩餘刮數 / 刮數版型」顯示。
     */
    private fun clearRemainingScratchesDisplayOnMaster() {
        val tv = findViewById<TextView?>(R.id.remaining_scratches_text_view)
        tv?.apply {
            text = ""
            visibility = View.GONE
        }
    }

    // ====== Fragment nav ======
    private fun loadFragment(fragment: Fragment, containerId: Int = containerIdForCurrent()) {
        supportFragmentManager.beginTransaction()
            .replace(containerId, fragment)
            .addToBackStack(null)
            .commitAllowingStateLoss()
    }

    // ====== OnAuthFlowListener ======
    override fun onLoginSuccess(loggedInUser: User) {
        currentUser = loggedInUser
        Log.d(TAG, "登入成功，右上角資訊已更新為: ${loggedInUser.account}")
        render(Mode.MASTER)

        setupForceLogoutWatcher()

        // 登入成功後，執行防弊檢查
        Log.d(TAG, "【登入成功】執行防弊檢查")
        performScratchTempSync()

        // 現在會載入和玩家頁面一致但無互動的刮卡顯示
        loadFragment(ScratchCardDisplayFragment(), containerIdFor(Mode.MASTER))

        // 觸發條件 2：登入成功後檢查更新
        triggerAutoUpdateCheck(reason = "login_success")
        ToastManager.show(this, "歡迎回來，${loggedInUser.account}！")
    }

    /**
     * 將 scratchCardsTemp 中的紀錄同步到正式 scratchCards
     * 並清空 scratchCardsTemp
     */
    private fun performScratchTempSync() {
        val userKey = currentUser?.firebaseKey ?: return
        Log.d(TAG, "【同步 scratchCardsTemp】開始同步用戶 $userKey 的暫存刮卡紀錄")

        val userRef = database.child("users").child(userKey)
        val tempRef = userRef.child("scratchCardsTemp")

        tempRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    Log.d(TAG, "【同步 scratchCardsTemp】沒有暫存紀錄，略過。")
                    return
                }

                val updates = mutableListOf<Pair<String, Int>>()
                for (child in snapshot.children) {
                    val cardId = child.child("cardId").getValue(String::class.java)
                    val cellNumber = child.child("cellNumber").getValue(Int::class.java)
                    if (cardId != null && cellNumber != null) {
                        updates.add(cardId to cellNumber)
                    }
                }

                if (updates.isEmpty()) {
                    Log.d(TAG, "【同步 scratchCardsTemp】沒有有效的紀錄可同步。")
                    return
                }

                Log.d(TAG, "【同步 scratchCardsTemp】共 ${updates.size} 筆要更新")

                for ((cardId, cellNumber) in updates) {
                    val targetRef = userRef.child("scratchCards").child(cardId).child("numberConfigurations")
                    targetRef.addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(configSnapshot: DataSnapshot) {
                            for ((index, config) in configSnapshot.children.withIndex()) {
                                val id = config.child("id").getValue(Int::class.java)
                                if (id == cellNumber) {
                                    targetRef.child(index.toString()).child("scratched").setValue(true)
                                    Log.d(TAG, "【同步 scratchCardsTemp】已補寫 scratched=true: 卡=$cardId, 格=$cellNumber")
                                    break
                                }
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.e(TAG, "【同步 scratchCardsTemp】讀取格子失敗: ${error.message}")
                        }
                    })
                }

                // 全部同步後清空暫存表
                tempRef.removeValue()
                    .addOnSuccessListener { Log.d(TAG, "【同步 scratchCardsTemp】已清空暫存紀錄") }
                    .addOnFailureListener { e -> Log.e(TAG, "【同步 scratchCardsTemp】清空失敗: ${e.message}") }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "【同步 scratchCardsTemp】讀取失敗: ${error.message}", error.toException())
            }
        })
    }

    override fun onLoginFailed() {
        currentUser = null
        render(Mode.MASTER)
        Log.d(TAG, "登入失敗，右上角資訊已重置。")
    }

    override fun onNavigateToRegister() {
        Log.d(TAG, "導航到註冊頁面")
        loadFragment(RegisterFragment(), containerIdFor(Mode.MASTER))
    }

    // ====== UserSessionProvider ======
    override fun getCurrentUserFirebaseKey(): String? = currentUser?.firebaseKey

    override fun setCurrentUserFirebaseKey(key: String?) {
        currentUser?.firebaseKey = key
        Log.d(TAG, "setCurrentUserFirebaseKey: 用戶 Firebase Key 已設定為 $key")
    }

    override fun navigateToFragment(fragment: Fragment) {
        loadFragment(fragment, containerIdForCurrent())
    }

    override fun setCurrentlyDisplayedScratchCardOrder(order: Int?) {
        currentlyDisplayedScratchCardOrder = order
        Log.d(TAG, "目前顯示的刮刮卡順序已設定為: $order")
    }

    override fun getCurrentlyDisplayedScratchCardOrder(): Int? = currentlyDisplayedScratchCardOrder

    override fun updateLoginStatus(isLoggedIn: Boolean, username: String?, points: Int?) {
        if (isLoggedIn && username != null && points != null) {
            Log.d(TAG, "updateLoginStatus: 用戶 $username 已登入，點數 $points")
        } else {
            Log.d(TAG, "updateLoginStatus: 用戶已登出")
        }
    }

    // ====== Prize & Giveaway ======
    private fun updatePrizeInfo(
        specialPrizeView: View,
        grandPrizeView: View,
        specialPrize: String?,
        grandPrizes: String?
    ) {
        // 特獎：一定是 TextView
        if (specialPrizeView is TextView) {
            specialPrizeView.text = if (!specialPrize.isNullOrEmpty()) specialPrize else "無"
        }

        // 大獎：可能是 TextView 或 LinearLayout
        if (grandPrizeView is TextView) {
            grandPrizeView.text = if (!grandPrizes.isNullOrEmpty()) grandPrizes else "無"
        } else if (grandPrizeView is LinearLayout) {
            displayGrandPrizes(grandPrizeView, grandPrizes)
        }
    }


    private fun TextView.setPrizeText(prize: String?, color: Int, backgroundRes: Int? = null) {
        text = prize ?: "無"
        setTextColor(color)
        backgroundRes?.let { setBackgroundResource(it) }
    }

    private fun updatePrizeInfoSeparate(
        specialPrize: String?,
        grandPrize: String?,
        isMaster: Boolean
    ) {
        val (specialPrizeTv, grandPrizeTv) = if (isMaster) {
            specialPrizeTextViewMaster to grandPrizeTextViewMaster
        } else {
            specialPrizeTextViewPlayer to grandPrizeTextViewPlayer
        }

        // === 特獎 ===
        specialPrizeTv?.apply {
            val noPrize = specialPrize.isNullOrBlank() || specialPrize == "無"

            if (noPrize) {
                // ✅ 顯示純文字「無」：不要圓框
                text = "無"
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.black))
                background = null
                setBackgroundColor(android.graphics.Color.TRANSPARENT)

                // 取消固定圓形大小，讓它像一般文字
                val lp = layoutParams
                lp?.width = LinearLayout.LayoutParams.WRAP_CONTENT
                lp?.height = LinearLayout.LayoutParams.WRAP_CONTENT
                layoutParams = lp
                setPadding(0, 0, 0, 0)
            } else {
                // ✅ 黃底白字（用程式直接畫「填滿」避免 drawable 只有框）
                text = specialPrize
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))

                val gold = ContextCompat.getColor(this@MainActivity, R.color.scratch_card_gold)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(gold)           // 填滿
                    setStroke(3, gold)       // 邊框（同色）
                }

                // 恢復圓形大小 52dp
                val sizePx = (52 * resources.displayMetrics.density).toInt()
                val lp = layoutParams
                lp?.width = sizePx
                lp?.height = sizePx
                layoutParams = lp
            }
        }

        // === 大獎（master 通常是 LinearLayout 容器）===
        if (grandPrizeTv is LinearLayout) {
            displayGrandPrizes(grandPrizeTv, grandPrize)
        }
    }

    private fun displayGrandPrizes(grandPrizeContainer: LinearLayout, grandPrizeStr: String?) {
        grandPrizeContainer.removeAllViews()

        val noPrize = grandPrizeStr.isNullOrBlank() || grandPrizeStr == "無"
        if (noPrize) {
            // ✅ 顯示純文字「無」：不要圓框
            val tv = TextView(this).apply {
                text = "無"
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.black))
                textSize = 20f
            }
            grandPrizeContainer.addView(tv)
            return
        }

        // 最多 16 個大獎，4 個一排
        val allNumbers = grandPrizeStr.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .take(16)

        val chunked = allNumbers.chunked(4)

        val green = ContextCompat.getColor(this, R.color.scratch_card_green)
        val whiteText = ContextCompat.getColor(this, android.R.color.white)

        val sizePx = (31 * resources.displayMetrics.density).toInt()
        val marginPx = (3 * resources.displayMetrics.density).toInt()

        for (rowNumbers in chunked) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
            }

            for (num in rowNumbers) {
                val numView = TextView(this).apply {
                    text = num.toString()
                    textSize = 12f
                    setTextColor(whiteText)
                    gravity = Gravity.CENTER

                    // ✅ 綠底填滿 + 白字
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(green)        // 填滿
                        setStroke(3, green)    // 邊框（同色）
                    }

                    val params = LinearLayout.LayoutParams(sizePx, sizePx)
                    params.setMargins(marginPx, marginPx, marginPx, marginPx)
                    layoutParams = params
                }

                rowLayout.addView(numView)
            }

            val rowParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            rowParams.setMargins(0, marginPx, 0, 0)
            rowLayout.layoutParams = rowParams

            grandPrizeContainer.addView(rowLayout)
        }
    }

    private fun fetchAndDisplayPrizeInfo(userFirebaseKey: String, isMaster: Boolean) {
        database.child("users").child(userFirebaseKey).child("scratchCards")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    var specialPrize: String? = null
                    var grandPrize: String? = null
                    for (child in snapshot.children) {
                        val card = child.getValue(ScratchCard::class.java)
                        if (card != null && card.inUsed) {
                            specialPrize = card.specialPrize
                            grandPrize = card.grandPrize
                            break
                        }
                    }
                    updatePrizeInfoSeparate(specialPrize, grandPrize, isMaster)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "載入獎項資訊失敗：${error.message}")

                    // ❗❗ 若使用者已登出 → 強制回歸預設 UI「無」
                    if (currentUser == null) {
                        updatePrizeInfoSeparate(null, null, isMaster)
                        return
                    }

                    // 其它錯誤再顯示載入失敗
                    updatePrizeInfoSeparate("無", "無", isMaster)
                }
            })
    }

    private fun fetchAndDisplayClawsGiveawayInfo(
        userFirebaseKey: String,
        targetTextView: TextView?
    ) {
        database.child("users").child(userFirebaseKey).child("scratchCards")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    var totalClaws = 0
                    var totalGiveaway = 0
                    var pitchType = "scratch"
                    for (child in snapshot.children) {
                        val card = child.getValue(ScratchCard::class.java)
                        if (card != null && card.inUsed) {
                            totalClaws += card.clawsCount ?: 0
                            totalGiveaway += card.giveawayCount ?: 0
                            pitchType = card.pitchType ?: "scratch"
                        }
                    }
                    updateClawsGiveawayInfo(pitchType,totalClaws, totalGiveaway, targetTextView)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "載入夾出/贈送刮數失敗: ${error.message}", error.toException())
                    updateClawsGiveawayInfo("scratch",0, 0, targetTextView)
                }
            })
    }

    private fun updateClawsGiveawayInfo(
        pitchType: String?,
        clawsCount: Int,
        giveawayCount: Int,
        targetTextView: TextView?
    ) {
        val isShopping = (pitchType == "shopping")

        targetTextView?.text = if (isShopping) {
            "消費${clawsCount}元\n贈送${giveawayCount}刮"
        } else {
            "夾出${clawsCount}樣\n贈送${giveawayCount}刮"
        }
    }

    override fun setCurrentlyInUseScratchCard(
        userFirebaseKey: String,
        serialNumberToSetInUse: String?
    ) {
        database.child("users").child(userFirebaseKey).child("scratchCards")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (child in snapshot.children) {
                        val currentSerial = child.key ?: continue
                        val isInUse = (currentSerial == serialNumberToSetInUse)
                        database.child("users").child(userFirebaseKey).child("scratchCards")
                            .child(currentSerial).child("inUsed").setValue(isInUse)
                            .addOnSuccessListener {
                                Log.d(TAG, "更新刮刮卡 $currentSerial 的 inUsed = $isInUse")
                            }
                            .addOnFailureListener { e ->
                                Log.e(
                                    TAG,
                                    "更新刮刮卡 $currentSerial 的 inUsed 失敗: ${e.message}",
                                    e
                                )
                            }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(
                        TAG,
                        "讀取刮刮卡以更新 inUsed 失敗: ${error.message}",
                        error.toException()
                    )
                }
            })
    }

    // ====== Logout ======
    private fun showLogoutConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("登出確認")
            .setMessage("您確定要登出嗎？")
            .setPositiveButton("確定") { dialog, _ -> performLogout() }
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    fun performLogout() {
        removeForceLogoutWatcher()
        SettingsViewModel.clearAllDrafts()
        currentUser = null
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        render(Mode.MASTER)
        loadFragment(LoginFragment(), containerIdFor(Mode.MASTER))
        ToastManager.show(this, "您已成功登出。")
        Log.d(TAG, "用戶已登出。")
    }

    private fun removeForceLogoutWatcher() {
        try {
            forceLogoutListener?.let { listener ->
                forceLogoutRef?.removeEventListener(listener)
            }
        } catch (e: Exception) {
            Log.e("ForceLogout", "移除 forceLogout 監聽器時發生錯誤：${e.message}")
        }
        forceLogoutListener = null
        forceLogoutRef = null
    }

    private fun setupForceLogoutWatcher() {
        val userKey = currentUser?.firebaseKey ?: return

        // 建立 Firebase Realtime Database Reference
        forceLogoutRef = database.child("users").child(userKey).child("forceLogout")

        // 建立監聽器
        forceLogoutListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val shouldLogout = snapshot.getValue(Boolean::class.java) ?: false

                if (shouldLogout) {
                    Log.d("ForceLogout", "偵測到後端要求登出，執行登出流程")

                    // 避免重複觸發
                    forceLogoutRef?.setValue(false)

                    // 移除監聽（必要）
                    removeForceLogoutWatcher()

                    // Firebase Auth 登出
                    try {
                        FirebaseAuth.getInstance().signOut()
                    } catch (_: Exception) {}

                    // 執行 MainActivity 的登出流程
                    runOnUiThread {
                        performLogout()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("ForceLogout", "後端登出監聽錯誤：${error.message}")
            }
        }

        // 將監聽器掛上 Firebase
        forceLogoutRef?.addValueEventListener(forceLogoutListener!!)
    }

    // ====== Master: 換版密碼 ======
    private fun showPasswordInputDialog() {
        val input = EditText(this).apply {
            hint = "請輸入換版密碼"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("換版密碼")
            .setMessage("請輸入換版密碼：")
            .setView(input)
            .setPositiveButton("確定") { dialog, _ ->
                val pwd = input.text.toString().trim()
                if (pwd.isNotEmpty()) updateSwitchScratchCardPassword(pwd)
                else ToastManager.show(this, "密碼不能為空！")
                dialog.dismiss()
            }
            .setNegativeButton("取消") { d, _ -> d.dismiss() }
            .show()
    }

    private fun updateSwitchScratchCardPassword(newPassword: String) {
        val key = currentUser?.firebaseKey ?: run {
            ToastManager.show(this, "更新失敗：未找到用戶。")
            Log.e(TAG, "更新換版密碼失敗：currentUserFirebaseKey 為空。")
            return
        }
        database.child("users").child(key).child("switchScratchCardPassword")
            .setValue(newPassword)
            .addOnSuccessListener {
                ToastManager.show(this, "換版密碼已更新！")
                Log.d(TAG, "用戶 $key 的換版密碼已更新為: $newPassword")
            }
            .addOnFailureListener { e ->
                ToastManager.show(this, "更新換版密碼失敗: ${e.message}")
                Log.e(TAG, "更新用戶 $key 換版密碼失敗: ${e.message}", e)
            }
    }

    // ====== Player: 下一版密碼、切回台主 ======
    private fun showNextVersionPasswordInputDialog() {
        val key = currentUser?.firebaseKey ?: run {
            ToastManager.show(this, "驗證失敗：未找到用戶。")
            Log.e(TAG, "驗證換版密碼失敗：currentUserFirebaseKey 為空。")
            return
        }

        // 先檢查是否允許切換到下一版
        Log.d(TAG, "【下一版按鈕】開始檢查是否允許切換")
        checkCanSwitchToNextVersion(key) { canSwitch, message ->
            if (canSwitch) {
                // 允許切換，顯示密碼輸入視窗
                Log.d(TAG, "【下一版按鈕】檢查通過，顯示密碼輸入視窗")

                val input = EditText(this).apply {
                    hint = "請輸入換版密碼"
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                AlertDialog.Builder(this)
                    .setTitle("換版密碼")
                    .setMessage("請輸入換版密碼：")
                    .setView(input)
                    .setPositiveButton("確定") { dialog, _ ->
                        val pwd = input.text.toString().trim()
                        if (pwd.isNotEmpty()) verifySwitchVersionPassword(pwd)
                        else ToastManager.show(this, "密碼不能為空！")
                        dialog.dismiss()
                    }
                    .setNegativeButton("取消") { d, _ -> d.dismiss() }
                    .show()
            } else {
                // 不允許切換，顯示提示視窗
                Log.d(TAG, "【下一版按鈕】檢查未通過：$message")
                showCannotSwitchDialog(message ?: "不允許切換到下一版")
            }
        }
    }

    private fun verifySwitchVersionPassword(enteredPassword: String) {
        val key = currentUser?.firebaseKey ?: run {
            ToastManager.show(this, "驗證失敗：未找到用戶。")
            Log.e(TAG, "驗證換版密碼失敗：currentUserFirebaseKey 為空。")
            return
        }

        database.child("users").child(key).child("switchScratchCardPassword")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    val stored = s.getValue(String::class.java)
                    if (stored != null && enteredPassword == stored) {
                        // 密碼正確，切換到下一版
                        switchToNextVersion(key)
                    } else {
                        ToastManager.show(this@MainActivity, "換版密碼錯誤，請重新輸入！")
                        Log.d(TAG, "換版密碼驗證失敗，輸入:$enteredPassword, 儲存:$stored")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    ToastManager.show(this@MainActivity, "驗證失敗：${error.message}")
                    Log.e(TAG, "讀取換版密碼失敗: ${error.message}", error.toException())
                }
            })
    }

    private fun switchToNextVersion(userFirebaseKey: String) {
        database.child("users").child(userFirebaseKey).child("scratchCards")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    // 收集所有刮刮卡並按 order 排序
                    val allCards = mutableListOf<Pair<String, ScratchCard>>()
                    for (child in snapshot.children) {
                        val serialNumber = child.key ?: continue
                        val card = child.getValue(ScratchCard::class.java) ?: continue
                        if (card.order != null) {
                            allCards.add(serialNumber to card)
                        }
                    }

                    if (allCards.isEmpty()) {
                        ToastManager.show(this@MainActivity,"沒有可用的刮刮卡版位")
                        return
                    }

                    // 按 order 排序
                    allCards.sortBy { it.second.order }

                    // 找到目前使用中的版位
                    val currentInUseIndex = allCards.indexOfFirst { it.second.inUsed == true }

                    // 計算下一個版位
                    val nextIndex = if (currentInUseIndex == -1) {
                        // 沒有使用中的卡片，使用第一個
                        0
                    } else {
                        // 循環到下一個版位
                        (currentInUseIndex + 1) % allCards.size
                    }

                    val nextCard = allCards[nextIndex]
                    val nextSerialNumber = nextCard.first
                    val nextOrder = nextCard.second.order

                    // 執行切換：將所有卡片設為未使用，然後將目標卡片設為使用中
                    setCurrentlyInUseScratchCard(userFirebaseKey, nextSerialNumber)

                    ToastManager.show(this@MainActivity,"已切換至版位 $nextOrder")
                    Log.d(TAG, "成功切換至下一版：版位 $nextOrder (序號: $nextSerialNumber)")
                }

                override fun onCancelled(error: DatabaseError) {
                    ToastManager.show(this@MainActivity, "切換版位失敗：${error.message}")
                    Log.e(TAG, "讀取刮刮卡以切換版位失敗: ${error.message}", error.toException())
                }
            })
    }

    private fun showPlayerToMasterLoginDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_master_login, null)
        val accountEt = view.findViewById<EditText>(R.id.edit_text_master_account)
        val passwordEt = view.findViewById<EditText>(R.id.edit_text_master_password)

        val dialog = AlertDialog.Builder(this)
            .setTitle("切換至台主頁面")
            .setView(view)
            .setPositiveButton("確定", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            ToastManager.setHostWindow(dialog.window)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val account = accountEt.text.toString().trim()
                val pwd = passwordEt.text.toString().trim()
                if (account.isEmpty() || pwd.isEmpty()) {
                    ToastManager.show(this, "帳號和密碼都必須填寫！")
                    return@setOnClickListener
                }
                verifyMasterCredentials(account, pwd) { ok, msg ->
                    if (ok) dialog.dismiss()
                    else if (!msg.isNullOrBlank()) ToastManager.show(this, msg)
                }
            }
        }

        dialog.setOnDismissListener {
            ToastManager.clearHostWindow()
        }

        dialog.show()
    }

    private fun verifyMasterCredentials(
        account: String,
        passwordInput: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        // 🔹 獲取裝置 ID
        val deviceId = com.champion.king.util.DeviceInfoUtil.getDeviceId(this)
        authRepository.login(account, passwordInput, deviceId) { success, user, message, needBinding ->
            runOnUiThread {
                if (success && user != null) {
                    currentUser = user
                    render(Mode.MASTER)
                    performScratchTempSync()
                    loadFragment(ScratchCardDisplayFragment(), containerIdFor(Mode.MASTER))
                    ToastManager.show(this@MainActivity,"已切換至台主頁面！")

                    // 觸發條件 3：玩家頁面輸入帳密成功切回台主首頁時檢查更新
                    triggerAutoUpdateCheck(reason = "player_to_master_login_success")
                    onResult(true, null)
                } else {
                    val errorMsg = message ?: "登入失敗，請確認帳號密碼"
                    ToastManager.show(this@MainActivity, errorMsg)
                    onResult(false, errorMsg)
                }
            }
        }
    }

    // ====== Helpers ======
    private fun ensureLoggedIn(): Boolean {
        val ok = currentUser != null
        if (!ok) ToastManager.show(this, "請先登入後再操作！")
        return ok
    }

    private fun updateUserInfoDisplay(user: User) {
        userNamePointsTextViewMaster.text = buildString {
            append("帳號: ${user.account}\n")
        }
        logoutButtonMaster.visibility = View.VISIBLE
        buttonScratchCardPasswordMaster.isEnabled = true
        updateWatermarkDisplay(true)
    }

    /**
     * 檢查是否允許切換到下一版
     * 如果特獎未開出且已刮開超過二分之一，則不允許切換
     */
    private fun checkCanSwitchToNextVersion(userFirebaseKey: String, onResult: (Boolean, String?) -> Unit) {
        database.child("users").child(userFirebaseKey).child("scratchCards")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    // 找到使用中的刮刮卡
                    var currentCard: ScratchCard? = null
                    for (child in snapshot.children) {
                        val card = child.getValue(ScratchCard::class.java)
                        if (card != null && card.inUsed == true) {
                            currentCard = card
                            break
                        }
                    }

                    if (currentCard == null) {
                        // 沒有使用中的刮刮卡，允許切換
                        onResult(true, null)
                        return
                    }

                    // 檢查特獎是否已刮開
                    val specialPrizeNumbers = currentCard.specialPrize
                        ?.split(",")
                        ?.mapNotNull { it.trim().toIntOrNull() }
                        ?: emptyList()

                    if (specialPrizeNumbers.isEmpty()) {
                        // 沒有設定特獎，允許切換
                        onResult(true, null)
                        return
                    }

                    // 檢查特獎是否已被刮開
                    val isSpecialPrizeScratched = currentCard.numberConfigurations?.any { config ->
                        specialPrizeNumbers.contains(config.number) && config.scratched == true
                    } ?: false

                    if (isSpecialPrizeScratched) {
                        // 特獎已刮開，允許切換
                        Log.d(TAG, "【切換版位檢查】特獎已刮開，允許切換")
                        onResult(true, null)
                        return
                    }

                    // 特獎未刮開，檢查已刮開的格子數
                    val totalCells = currentCard.scratchesType ?: 0
                    val scratchedCount = currentCard.numberConfigurations?.count { it.scratched == true } ?: 0
                    val halfOfTotal = totalCells / 2.0

                    Log.d(TAG, "【切換版位檢查】總格數: $totalCells, 已刮: $scratchedCount, 二分之一: $halfOfTotal")

                    if (scratchedCount >= halfOfTotal) {
                        // 已刮開超過二分之一，且特獎未開出，不允許切換
                        Log.d(TAG, "【切換版位檢查】已刮開 $scratchedCount 格 (>= $halfOfTotal)，且特獎未開出，不允許切換")
                        onResult(false, "此刮板已刮超過二分之一，且特獎尚未開出，不允許進入下一版")
                    } else {
                        // 允許切換
                        Log.d(TAG, "【切換版位檢查】已刮開 $scratchedCount 格 (< $halfOfTotal)，允許切換")
                        onResult(true, null)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "【切換版位檢查】讀取失敗: ${error.message}", error.toException())
                    onResult(false, "檢查失敗：${error.message}")
                }
            })
    }

    /**
     * 顯示不允許切換版位的提示視窗
     */
    private fun showCannotSwitchDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("無法切換版位")
            .setMessage(message)
            .setPositiveButton("確定") { dialog, _ ->
                Log.d(TAG, "【切換版位】用戶確認無法切換的提示")
                dialog.dismiss()
            }
            .setCancelable(false) // 禁止點擊外部關閉
            .show()
    }

    /**
     * 啟動時檢查更新
     */
    private fun checkUpdateOnStart() {
        // 觸發條件 1：開啟 APP 還沒登入時，立刻檢查更新
        if (!isUserLoggedIn()) {
            triggerAutoUpdateCheck(reason = "app_start_not_logged_in")
        }
    }

    /**
     * 背景檢查更新
     */
    private fun checkUpdateInBackground() {
        lifecycleScope.launch {
            try {
                when (val result = updateManager.checkUpdate(isManual = false)) {
                    is UpdateResult.HasUpdate -> {
                        showUpdateDialog(result.versionInfo)
                    }
                    // 移除 Maintenance 處理
                    else -> {
                        // NoUpdate 或 Error 時不顯示任何訊息
                    }
                }
            } catch (e: Exception) {
                // 靜默失敗，不影響使用者體驗
                Log.e("MainActivity", "Background update check failed: ${e.message}")
            }
        }
    }

    /**
     * 顯示更新對話框（包含更新歷史）
     */
    private fun showUpdateDialog(versionInfo: com.champion.king.data.api.dto.VersionInfo) {
        // 取得目前版本資訊
        val currentVersionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "未知版本"
        }

        // 建立自訂 Dialog 佈局
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_update, null)

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
        val builder = AlertDialog.Builder(this)
            .setTitle("發現新版本")
            .setView(dialogView)
            .setPositiveButton("立即更新") { dialog, _ ->
                dialog.dismiss()
                startDownloadAndInstall(versionInfo.downloadUrl)
            }

        if (versionInfo.updateType != "force") {
            builder.setNegativeButton("稍後提醒") { dialog, _ ->
                dialog.dismiss()
            }
            builder.setCancelable(true)
        } else {
            builder.setCancelable(false)
        }

        isUpdateDialogShowing = true
        val dialog = builder.create()
        dialog.setOnDismissListener {
            isUpdateDialogShowing = false
        }
        dialog.show()
    }

    /**
     * 開始下載並安裝 APK
     */
    private fun startDownloadAndInstall(downloadUrl: String) {
        val progressDialog = android.app.ProgressDialog(this).apply {
            setTitle("正在下載更新")
            setMessage("下載進度：0%")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        val downloader = ApkDownloader(this)

        downloader.downloadApk(
            downloadUrl = downloadUrl,
            onProgress = { progress ->
                runOnUiThread {
                    progressDialog.progress = progress
                    progressDialog.setMessage("下載進度：$progress%")
                }
            },
            onComplete = { success, message ->
                runOnUiThread {
                    progressDialog.dismiss()

                    if (success) {
                        ToastManager.show(this,"下載完成，準備安裝")
                    } else {
                        ToastManager.show(this,message)
                    }
                }
            }
        )
    }

    /**
     * 判斷使用者是否已登入
     */
    private fun isUserLoggedIn(): Boolean {
        return try {
            val userKey = (this as? UserSessionProvider)?.getCurrentUserFirebaseKey()
            !userKey.isNullOrEmpty()
        } catch (e: Exception) {
            false
        }
    }

    fun enableImmersiveMode() {
        // 只隱藏狀態列（電量、時間等）
        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        lastInteractionTime = System.currentTimeMillis()
        resetIdleTimer()
        return super.dispatchTouchEvent(ev)
    }

    private fun resetIdleTimer() {
        idleHandler.removeCallbacks(idleRunnable)
        idleHandler.postDelayed(idleRunnable, idleTimeoutMillis)
    }

    private fun showAdPoster() {
        runOnUiThread {
            val decorView = window.decorView as FrameLayout

            // 🔹 建立最外層容器（保持滿版、可放置海報與文字）
            val posterContainer = FrameLayout(this).apply {
                alpha = 0f
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.BLACK)
            }

            // 🔹 建立海報 (全螢幕延展，避免裁切)
            val posterImage = ImageView(this).apply {
                setImageResource(R.drawable.splash_poster)
                scaleType = ImageView.ScaleType.FIT_XY
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            posterContainer.addView(posterImage)

            // 🔹 點擊海報時，淡出並移除
            posterContainer.setOnClickListener {
                posterContainer.animate()
                    .alpha(0f)
                    .setDuration(400)
                    .withEndAction {
                        decorView.removeView(posterContainer)
                        resetIdleTimer()
                    }
                    .start()
            }

            // 🔹 放進畫面
            decorView.addView(posterContainer)

            // 🔹 整體淡入
            posterContainer.animate()
                .alpha(1f)
                .setDuration(400)
                .withEndAction {
                    // ⭐ 這裡會呼叫你的 startFloatingTapHint
                    startFloatingTapHint(posterContainer)
                }
                .start()
        }
    }

    private fun startFloatingTapHint(container: FrameLayout) {

        // 建議將尺寸加大，以容納 56f 的字體和爆炸圖。
        val containerSizeDp = 350 // 調整為 350dp (可根據實際爆炸圖效果微調)
        val containerSizePx = (containerSizeDp * resources.displayMetrics.density).toInt()

        // 🔹 1. 建立外層容器：用於顯示爆炸背景圖
        val tapHintContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                containerSizePx, // 寬度跟著變大
                containerSizePx  // 高度跟著變大
            )

            // ❗❗ 假設你新增了一個名為 R.drawable.explosion_icon 的爆炸圖資源 ❗❗
            background = ContextCompat.getDrawable(context, R.drawable.explosion_icon)?.apply {
                // 90% 不透明度
                alpha = (255 * 0.9f).toInt()
            }
            alpha = 0f // 初始設定為透明，等待動畫淡入
        }

        // 🔹 2. 建立內層 TextView：「請點我」
        val textView = TextView(this).apply {
            text = "請點我"
            // 調整字體大小為 56f
            textSize = 56f
            // 為了明顯度，將文字顏色改為黑色
            setTextColor(android.graphics.Color.WHITE)
            typeface = Typeface.create("Microsoft JhengHei", Typeface.BOLD) // 粗體
            gravity = Gravity.CENTER // 文字在容器內置中

            // 移除或不設定陰影，因為文字已改為黑色並有爆炸背景圖

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 將文字加入容器
        tapHintContainer.addView(textView)
        // 將容器加入畫面的最外層容器
        container.addView(tapHintContainer)

        val handler = Handler(Looper.getMainLooper())

        val runnable = object : Runnable {
            override fun run() {

                // 隨機位置：現在是移動 tapHintContainer
                val maxX = container.width - tapHintContainer.width
                val maxY = container.height - tapHintContainer.height

                // 確保容器寬高已正確計算，避免錯誤
                if (maxX > 0 && maxY > 0) {
                    tapHintContainer.x = (0..maxX).random().toFloat()
                    tapHintContainer.y = (0..maxY).random().toFloat()
                }

                // 計時：總共約 2秒
                val fadeIn = 250L
                val stay = 1500L
                val fadeOut = 250L
                val total = fadeIn + stay + fadeOut

                // 淡入 → 停留 → 淡出 (針對 tapHintContainer 執行)
                tapHintContainer.animate()
                    .alpha(1f)
                    .setDuration(fadeIn)
                    .withEndAction {
                        tapHintContainer.animate()
                            .alpha(1f)
                            .setDuration(stay)
                            .withEndAction {
                                tapHintContainer.animate()
                                    .alpha(0f)
                                    .setDuration(fadeOut)
                                    .start()
                            }
                            .start()
                    }
                    .start()

                handler.postDelayed(this, total)
            }
        }

        handler.postDelayed(runnable, 500)
    }

    private fun lockAppToScreen() {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            if (activityManager.lockTaskModeState == android.app.ActivityManager.LOCK_TASK_MODE_NONE) {
                startLockTask()
                ToastManager.show(this,"已啟用鎖定模式，無法跳出遊戲")
            }
        }
    }

    fun relockFromPlayerGesture() {
        if (mode != Mode.PLAYER) return
        enableImmersiveMode()
        lockAppToScreen()
    }

    private fun unlockAppFromScreen() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            stopLockTask()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}