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
import com.champion.king.core.config.AppConfig

class MainActivity : AppCompatActivity(), OnAuthFlowListener, UserSessionProvider {

    // ====== UI Mode ======
    private enum class Mode { MASTER, PLAYER, PLAYER_SPLIT }
    private var mode: Mode = Mode.MASTER
    // ====== Master views ======
    private lateinit var currentTimeTextViewMaster: TextView
    private lateinit var userNamePointsTextViewMaster: TextView
    private lateinit var configButtonMaster: ImageView
    private lateinit var logoutButtonMaster: ImageButton
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
    private val isAdEnabled = false // 💡 設定為 false 即可關閉廣告，改回 true 則恢復
    private var lastInteractionTime: Long = System.currentTimeMillis()
    private val idleTimeoutMillis = 15 * 60 * 1000L // 15分鐘
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { showAdPoster() }
    private val SESSION_LAST_SEEN_AT = "SESSION_LAST_SEEN_AT"
    private val SESSION_EXPIRE_MS = 3L * 24 * 60 * 60 * 1000 // 3 天
    lateinit var messageButtonMaster: ImageButton
    private var messageBadgeTextViewMaster: TextView? = null
    // ====== Firebase 真實連線狀態 ======
    var isFirebaseConnected: Boolean = false
    private var connectionListener: ValueEventListener? = null
    private var playerLogoutClickCount = 0
    private var lastPlayerLogoutClickTime: Long = 0
    // ====== Player Split views ======
    private var currentTimeTextViewPlayerSplit: TextView? = null
    private var buttonNextVersionPlayerSplit: Button? = null
    private var logoSplit: ImageView? = null
    private var slidingMenuRoot: View? = null
    private var menuContentLayout: View? = null
    private var menuToggleButton: View? = null
    private var menuToggleIcon: ImageView? = null
    private var isMenuOpen = false

    // 🌟 新增：用來記錄時間連點次數的變數
    private var splitTimeTapCount = 0
    private var lastSplitTimeTapAt = 0L

    // 🌟 新增：抽屜選單閒置計時器 (60秒)
    private val splitMenuIdleHandler = Handler(Looper.getMainLooper())
    private val SPLIT_MENU_IDLE_TIMEOUT_MS = 60_000L
    private val splitMenuIdleRunnable = Runnable {
        if (isMenuOpen) {
            closeSplitMenu()
        }
    }

    private fun setupFirebaseConnectionMonitor() {
        // 監聽 Firebase 內建的 .info/connected 節點
        val connectedRef = FirebaseDatabase.getInstance(DB_URL).getReference(".info/connected")
        connectionListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                isFirebaseConnected = snapshot.getValue(Boolean::class.java) ?: false
                Log.d(TAG, "Firebase 實體連線狀態: \$isFirebaseConnected")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "監聽 Firebase 連線狀態失敗")
            }
        }
        connectedRef.addValueEventListener(connectionListener!!)
    }

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

    private val AUTO_RESTORE_TIMEOUT_MS = 8000L  // 8 秒超時，避免開機卡死
    private var autoRestoreFinished = false
    private var autoRestoreTimeoutRunnable: Runnable? = null

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

    // === AccountStatus 監聽（停用即登出）===
    private var accountStatusListener: ValueEventListener? = null
    private var accountStatusRef: DatabaseReference? = null
    private var hasHandledSuspension: Boolean = false

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
        setupFirebaseConnectionMonitor() // 🌟 啟動連線監聽

        // ✅ 冷啟動才走這套（避免重建/旋轉造成流程混亂）
        if (savedInstanceState == null) {

            val sp = getSharedPreferences(AppConfig.Prefs.LOGIN_PREFS, MODE_PRIVATE)
            val hasSession = sp.getBoolean("SESSION_LOGGED_IN", false) &&
                    !sp.getString("SESSION_USER_KEY", null).isNullOrBlank()

            if (hasSession) {
                // ✅ 有 session：先顯示黑畫面/Loading，不要 render MASTER，避免閃
                showBootLoadingScreen()

                // ✅ 關鍵：要處理「還原失敗」→ 不能卡在 loading
                val started = tryRestoreSessionAndAutoLogin()
                if (!started) {
                    // 還原流程沒有啟動（例如 session 過期、資料不完整）
                    render(Mode.MASTER)
                    loadFragment(LoginFragment(), containerIdFor(Mode.MASTER))
                    checkUpdateOnStart()
                }

                // ⚠️ 注意：這裡不要 checkUpdateOnStart（避免 loading 期間跳更新）
            } else {
                // ✅ 沒 session：才進台主 + 登入頁（維持你原本行為）
                render(Mode.MASTER)
                loadFragment(LoginFragment(), containerIdFor(Mode.MASTER))
                checkUpdateOnStart()
            }

        } else {
            // 非冷啟動（理論上你已鎖橫向、很少走到）
            render(Mode.MASTER)
        }

        updateCurrentTime()
        enableImmersiveMode()
        resetIdleTimer() // 啟動閒置監測計時
    }

    private fun markSessionSeen() {
        val sp = getSharedPreferences(AppConfig.Prefs.LOGIN_PREFS, MODE_PRIVATE)
        val loggedIn = sp.getBoolean("SESSION_LOGGED_IN", false)
        val userKey = sp.getString("SESSION_USER_KEY", null)
        if (!loggedIn || userKey.isNullOrBlank()) return

        sp.edit()
            .putLong(SESSION_LAST_SEEN_AT, System.currentTimeMillis())
            .apply()
    }

    private fun isSessionExpiredByInactivity(): Boolean {
        val sp = getSharedPreferences(AppConfig.Prefs.LOGIN_PREFS, MODE_PRIVATE)
        val loggedIn = sp.getBoolean("SESSION_LOGGED_IN", false)
        val userKey = sp.getString("SESSION_USER_KEY", null)
        if (!loggedIn || userKey.isNullOrBlank()) return false

        val lastSeen = sp.getLong(SESSION_LAST_SEEN_AT, 0L)
        if (lastSeen <= 0L) return false // 沒有紀錄就先不判過期（避免誤殺）

        val diff = System.currentTimeMillis() - lastSeen
        return diff >= SESSION_EXPIRE_MS
    }

    private fun enforceSessionExpiryIfNeeded(): Boolean {
        if (!isSessionExpiredByInactivity()) return false

        Log.d(TAG, "Session expired by inactivity. clear session.")
        clearLoginSession()
        currentUser = null

        // 回到未登入狀態
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        render(Mode.MASTER)
        loadFragment(LoginFragment(), containerIdFor(Mode.MASTER))
        ToastManager.show(this, "已超過可用期限未使用，請重新登入")
        return true
    }

    private fun tryRestoreSessionAndAutoLogin(): Boolean {
        val sp = getSharedPreferences(AppConfig.Prefs.LOGIN_PREFS, MODE_PRIVATE)
        val loggedIn = sp.getBoolean("SESSION_LOGGED_IN", false)
        val userKey = sp.getString("SESSION_USER_KEY", null)

        if (!loggedIn || userKey.isNullOrBlank()) return false

        // ✅ 只在「離開/沒使用」超過 3 天時，才視為未登入
        if (isSessionExpiredByInactivity()) {
            clearLoginSession()
            return false
        }

        autoRestoreFinished = false

        // ✅ 1. 超時保險：避免重開機時 Firebase 不回呼造成卡死
        autoRestoreTimeoutRunnable?.let { handler.removeCallbacks(it) }
        autoRestoreTimeoutRunnable = Runnable {
            if (autoRestoreFinished) return@Runnable
            autoRestoreFinished = true

            Log.w(TAG, "自動登入超時（可能是剛開機網路未就緒），先進玩家模式避免卡死")
            ToastManager.show(this, "網路尚未就緒，先進入玩家模式")

            val fallbackUser = User().apply {
                firebaseKey = userKey
                account = ""
                accountStatus = "ACTIVE"
            }
            // 🌟 因為離線不知道模式，先給預設的 Mode.PLAYER
            onAutoRestoreSuccessToPlayer(fallbackUser, Mode.PLAYER)

            // 背景重試撈 user
            retryFetchUserAfterEnteredPlayer(userKey, attempt = 1)
        }
        handler.postDelayed(autoRestoreTimeoutRunnable!!, AUTO_RESTORE_TIMEOUT_MS)

        // ✅ 2. 直接用 userKey 把 user 撈回來
        database.child("users").child(userKey)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (autoRestoreFinished) return

                    autoRestoreFinished = true
                    autoRestoreTimeoutRunnable?.let { handler.removeCallbacks(it) }

                    val user = snapshot.getValue(User::class.java)
                    if (user == null) {
                        clearLoginSession()
                        render(Mode.MASTER)
                        loadFragment(LoginFragment(), containerIdFor(Mode.MASTER))
                        return
                    }

                    user.firebaseKey = userKey

                    if (user.accountStatus == "SUSPENDED") {
                        clearLoginSession()
                        handleAccountSuspended()
                        return
                    }

                    // 🌟 核心修改：從 snapshot 檢查使用中的卡片是否為分割模式
                    var targetMode = Mode.PLAYER
                    for (child in snapshot.child("scratchCards").children) {
                        val inUsed = child.child("inUsed").getValue(Boolean::class.java) ?: false
                        if (inUsed) {
                            val splitMode = child.child("splitMode").getValue(String::class.java)
                            if (!splitMode.isNullOrEmpty()) {
                                targetMode = Mode.PLAYER_SPLIT
                            }
                            break
                        }
                    }

                    // ✅ 成功：依照剛剛判斷的 targetMode 進入對應的玩家畫面
                    onAutoRestoreSuccessToPlayer(user, targetMode)
                }

                override fun onCancelled(error: DatabaseError) {
                    if (autoRestoreFinished) return

                    autoRestoreFinished = true
                    autoRestoreTimeoutRunnable?.let { handler.removeCallbacks(it) }

                    Log.e(TAG, "自動登入失敗：${error.message}，先進玩家模式避免卡死")
                    ToastManager.show(this@MainActivity, "連線中，先進入玩家模式")

                    val fallbackUser = User().apply {
                        firebaseKey = userKey
                        account = ""
                        accountStatus = "ACTIVE"
                    }
                    // 🌟 離線錯誤預設給 Mode.PLAYER
                    onAutoRestoreSuccessToPlayer(fallbackUser, Mode.PLAYER)

                    // 背景重試撈 user
                    retryFetchUserAfterEnteredPlayer(userKey, attempt = 1)
                }
            })

        return true
    }

    private fun retryFetchUserAfterEnteredPlayer(userKey: String, attempt: Int) {
        // 最多重試 6 次：3s, 3s, 3s... 你也可以改成遞增
        if (attempt > 6) {
            Log.w(TAG, "背景重試撈 user 已達上限，停止重試")
            return
        }

        handler.postDelayed({
            database.child("users").child(userKey)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val user = snapshot.getValue(User::class.java)
                        if (user == null) {
                            retryFetchUserAfterEnteredPlayer(userKey, attempt + 1)
                            return
                        }

                        user.firebaseKey = userKey

                        // ✅ 一旦拿到 user，補上 currentUser + 監聽 + 防弊同步 + 停用檢查
                        currentUser = user

                        if (user.accountStatus == "SUSPENDED") {
                            clearLoginSession()
                            handleAccountSuspended()
                            return
                        }

                        // 🌟 背景連線恢復後，檢查正確的版面模式
                        var targetMode = Mode.PLAYER
                        for (child in snapshot.child("scratchCards").children) {
                            val inUsed = child.child("inUsed").getValue(Boolean::class.java) ?: false
                            if (inUsed) {
                                val splitMode = child.child("splitMode").getValue(String::class.java)
                                if (!splitMode.isNullOrEmpty()) {
                                    targetMode = Mode.PLAYER_SPLIT
                                }
                                break
                            }
                        }

                        // 🌟 如果原本因為斷線預設進了 PLAYER，但網路恢復後發現應該是 PLAYER_SPLIT，我們就自動幫他切過去！
                        if (mode != targetMode) {
                            Log.d(TAG, "網路恢復，重新校正玩家模式為: $targetMode")
                            render(targetMode)
                        }

                        setupForceLogoutWatcher()
                        setupAccountStatusWatcher()
                        performScratchTempSync()

                        // 玩家畫面如果需要立刻刷新獎項/刮數資訊（可選）
                        fetchAndDisplayPrizeInfo(userKey, isMaster = false)
                        fetchAndDisplayClawsGiveawayInfo(userKey, giveawayCountTextViewPlayer)

                        Log.d(TAG, "背景重試撈 user 成功，已補掛監聽/同步")
                    }

                    override fun onCancelled(error: DatabaseError) {
                        retryFetchUserAfterEnteredPlayer(userKey, attempt + 1)
                    }
                })
        }, 3000L)
    }

    // 🌟 修改這裡：加入 targetMode 參數
    private fun onAutoRestoreSuccessToPlayer(user: User, targetMode: Mode) {
        // 這條路徑是「重開 APP 自動還原」才會走到
        currentUser = user
        hasHandledSuspension = false

        // ✅ 掛上你原本登入成功會掛的監聽，避免後端停用/強登出失效
        setupForceLogoutWatcher()
        setupAccountStatusWatcher()

        // ✅ 你原本登入成功會做的防弊同步，重開也做一次（但不進台主）
        Log.d(TAG, "【自動還原登入】執行防弊檢查")
        performScratchTempSync()

        // ✅ 關鍵：根據拿到的資料，動態進入單版面或分割版面
        render(targetMode)
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateTimeRunnable)

        // ✅ 從背景回來也要檢查：如果放著3天沒開再回來，立刻視為未登入
        if (enforceSessionExpiryIfNeeded()) return

        // ✅ 有在使用就更新 lastSeenAt（不會踢人）
        markSessionSeen()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateTimeRunnable)
        idleHandler.removeCallbacks(idleRunnable) // 停止閒置檢查
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionListener?.let {
            FirebaseDatabase.getInstance(DB_URL).getReference(".info/connected").removeEventListener(it)
        }
    }

    // ====== Rendering ======
    private fun render(target: Mode) {
        mode = target
        when (target) {
            Mode.MASTER -> {
                setContentView(R.layout.activity_main)
                initMasterViews()
                refreshUnreadBadgeOnMaster()
                updateCurrentTime()
                updateVersionInfo()
                updateWatermarkDisplay(currentUser != null)
                if (currentUser != null) {
                    updateUserInfoDisplay(currentUser!!)
                    currentUser!!.firebaseKey?.let { fetchAndDisplayPrizeInfo(it, isMaster = true) }
                } else {
                    userNamePointsTextViewMaster.text = "請登入/註冊"
                    updatePrizeInfoSeparate(null, null, null, true)
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
                    updatePrizeInfoSeparate(null, null, null, false)
                    updateClawsGiveawayInfo("scratch",0, 0, giveawayCountTextViewPlayer)
                }
                // 切玩家頁面即載入顯示頁
                loadFragment(ScratchCardPlayerFragment(), containerIdFor(Mode.PLAYER))
                Log.d(TAG, "已切換至玩家頁面。")
                lockAppToScreen()
            }

            Mode.PLAYER_SPLIT -> {
                setContentView(R.layout.player_split_main)
                initPlayerSplitViews()
                updateCurrentTime()
                enableImmersiveMode()

                // 載入未來的 Fragment 邏輯
                loadFragment(ScratchCardSplitPlayerFragment(), containerIdFor(Mode.PLAYER_SPLIT))

                Log.d(TAG, "已切換至分割玩家頁面。")
                lockAppToScreen()
            }
        }
    }

    private fun initMasterViews() {
        currentTimeTextViewMaster = findViewById(R.id.current_time_text_view_master)
        userNamePointsTextViewMaster = findViewById(R.id.user_name_points_text_view_master)
        configButtonMaster = findViewById(R.id.config_button_master)
        messageButtonMaster = findViewById(R.id.message_button_master)
        messageBadgeTextViewMaster = findViewById(R.id.message_badge_master)
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
            refreshMessageBadge()
            if (currentUser != null) {
                routeToMasterDisplay()
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
                R.id.message_button_master -> {
                    clearRemainingScratchesDisplayOnMaster()  // 你原本各頁面都有清
                    refreshMessageBadge()  // 🌟 新增這一行：點擊訊息按鈕進入時，強制重拉一次最新的未讀數字
                    loadFragment(MessageFragment(), containerIdFor(Mode.MASTER))
                }
            }
        }
        bagButtonMaster.setOnClickListener(protectedClick)
        shopButtonMaster.setOnClickListener(protectedClick)
        userButtonMaster.setOnClickListener(protectedClick)
        configButtonMaster.setOnClickListener(protectedClick)
        messageButtonMaster.setOnClickListener(protectedClick)

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
                // 🌟 點擊確定後，先進入我們寫好的分流檢查站，確認版面狀態
                checkAndEnterPlayerMode()
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    // ====== 台主畫面分流站 ======
    private fun routeToMasterDisplay() {
        val userKey = currentUser?.firebaseKey ?: return

        database.child("users").child(userKey).child("scratchCards")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    var isSplitMode = false

                    // 尋找目前 inUsed = true 的卡片
                    for (child in snapshot.children) {
                        val card = child.getValue(ScratchCard::class.java)
                        if (card != null && card.inUsed == true) {
                            if (!card.splitMode.isNullOrEmpty()) {
                                isSplitMode = true
                            }
                            break
                        }
                    }

                    // 根據判斷結果載入對應的 Fragment
                    if (isSplitMode) {
                        Log.d(TAG, "台主頁面載入：分割版面顯示器")
                        loadFragment(ScratchCardDisplayFragment(), containerIdFor(Mode.MASTER)) // 🌟 注意：這裡要等我下面解釋
                        loadFragment(ScratchCardSplitDisplayFragment(), containerIdFor(Mode.MASTER))
                    } else {
                        Log.d(TAG, "台主頁面載入：單一版面顯示器")
                        loadFragment(ScratchCardDisplayFragment(), containerIdFor(Mode.MASTER))
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    // 若網路異常或錯誤，預設退回舊版
                    loadFragment(ScratchCardDisplayFragment(), containerIdFor(Mode.MASTER))
                }
            })
    }

    private fun checkAndEnterPlayerMode() {
        val userKey = currentUser?.firebaseKey ?: return
        database.child("users").child(userKey).child("scratchCards")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val inUseCards = mutableListOf<ScratchCard>()
                    for (child in snapshot.children) {
                        val card = child.getValue(ScratchCard::class.java)
                        if (card != null && card.inUsed == true) {
                            inUseCards.add(card)
                        }
                    }

                    // 核心分流邏輯
                    when {
                        inUseCards.size > 1 -> {
                            // 異常防呆：超過 1 張啟用
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("資料異常")
                                .setMessage("系統偵測到多張使用中的刮板，請聯繫台主協助處理。")
                                .setPositiveButton("確定") { d, _ -> d.dismiss() }
                                .show()
                        }
                        inUseCards.isEmpty() -> {
                            // 沒卡片：進入舊版，讓原 Fragment 顯示「目前沒有可用的刮刮卡」
                            render(Mode.PLAYER)
                        }
                        else -> {
                            // 剛好 1 張：檢查是否為分割模式
                            val currentCard = inUseCards.first()
                            if (currentCard.splitMode.isNullOrEmpty()) {
                                Log.d(TAG, "進入單版面模式")
                                render(Mode.PLAYER)
                            } else {
                                Log.d(TAG, "進入分割版面模式: ${currentCard.splitMode}")
                                render(Mode.PLAYER_SPLIT)
                            }
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "檢查版面狀態失敗: ${error.message}")
                    ToastManager.show(this@MainActivity, "網路異常，無法切換頁面")
                }
            })
    }

    private fun initPlayerViews() {
        currentTimeTextViewPlayer = findViewById(R.id.current_time_text_view_player)
        specialPrizeTextViewPlayer = findViewById(R.id.special_prize_text_view_player)
        grandPrizeTextViewPlayer = findViewById(R.id.grand_prize_text_view_player)
        giveawayCountTextViewPlayer = findViewById(R.id.giveaway_count_text_view_player)
        buttonNextVersionPlayer = findViewById(R.id.button_next_version_player)
        fragmentContainerPlayer = findViewById(R.id.main_content_container_player)
        watermarkOverlayContainerPlayer = findViewById(R.id.watermark_overlay_container_player)

        if (BuildConfig.DEBUG) {
            specialPrizeTextViewPlayer?.setOnClickListener {
                Log.d(TAG, "🥷 觸發測試捷徑：點擊單一版面特獎圈圈，一鍵進入設置頁面！")
                devBypassToSettings()
            }
        }

        val giveawayContainer = findViewById<View>(R.id.giveaway_count_container_player)
        giveawayContainer.setOnClickListener {
            val currentTime = System.currentTimeMillis()

            // 如果兩次點擊間隔超過 2 秒，重置計數
            if (currentTime - lastPlayerLogoutClickTime > 2000) {
                playerLogoutClickCount = 0
            }

            playerLogoutClickCount++
            lastPlayerLogoutClickTime = currentTime

            Log.d(TAG, "玩家區域點擊次數: $playerLogoutClickCount")

            if (playerLogoutClickCount >= 7) {
                playerLogoutClickCount = 0 // 觸發後重置
                showLogoutConfirmationDialog()
            }
        }

        // 玩家頁面的 Home 按鈕 - 需要輸入帳號密碼才能回到台主頁面
        findViewById<ImageView>(R.id.home_button_player).setOnClickListener {
            Log.d(TAG, "玩家頁面 Home button clicked - 顯示登入對話框")
            showPlayerToMasterLoginDialog()
        }

        buttonNextVersionPlayer?.setOnClickListener { showNextVersionPasswordInputDialog() }
    }

    private fun initPlayerSplitViews() {
        currentTimeTextViewPlayerSplit = findViewById(R.id.current_time_text_view_player_split)
        buttonNextVersionPlayerSplit = findViewById(R.id.button_next_version_player_split)
        logoSplit = findViewById(R.id.logo_split)

        slidingMenuRoot = findViewById(R.id.sliding_menu_root)
        menuContentLayout = findViewById(R.id.menu_content_layout)
        menuToggleButton = findViewById(R.id.menu_toggle_button)
        menuToggleIcon = findViewById(R.id.menu_toggle_icon)

        // 🌟 設定側邊選單動畫與自動收合
        isMenuOpen = false
        slidingMenuRoot?.post {
            val hideDistance = -(menuContentLayout?.width?.toFloat() ?: 0f)
            slidingMenuRoot?.translationX = hideDistance

            menuToggleButton?.setOnClickListener {
                if (isMenuOpen) {
                    // 縮回
                    closeSplitMenu()
                    splitMenuIdleHandler.removeCallbacks(splitMenuIdleRunnable)
                } else {
                    // 彈出
                    slidingMenuRoot?.animate()?.translationX(0f)?.setDuration(300)?.start()
                    menuToggleIcon?.animate()?.rotation(180f)?.setDuration(300)?.start()
                    isMenuOpen = true
                    resetSplitMenuIdleTimer() // 🌟 展開時啟動閒置倒數
                }
            }
        }

        logoSplit?.setOnClickListener {
            Log.d(TAG, "分割玩家頁面 LOGO clicked - 顯示登入對話框")
            showPlayerToMasterLoginDialog()
        }

        buttonNextVersionPlayerSplit?.setOnClickListener {
            Log.d(TAG, "分割玩家頁面 下一版 button clicked")
            showNextVersionPasswordInputDialog()
        }

        currentTimeTextViewPlayerSplit?.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastSplitTimeTapAt > 1200) {
                splitTimeTapCount = 0
            }
            lastSplitTimeTapAt = now
            splitTimeTapCount++

            if (splitTimeTapCount >= 7) {
                splitTimeTapCount = 0
                Log.d(TAG, "時間區域連點7次，觸發登出確認視窗")
                showLogoutConfirmationDialog()
            }
        }
    }

    // ====== 分割版面：自動收合選單邏輯 ======
    private fun closeSplitMenu() {
        if (!isMenuOpen) return
        val hideDistance = -(menuContentLayout?.width?.toFloat() ?: 0f)
        slidingMenuRoot?.animate()?.translationX(hideDistance)?.setDuration(300)?.start()
        menuToggleIcon?.animate()?.rotation(0f)?.setDuration(300)?.start()
        isMenuOpen = false
        Log.d(TAG, "閒置 1 分鐘，自動收合分割版面抽屜選單")
    }

    private fun resetSplitMenuIdleTimer() {
        splitMenuIdleHandler.removeCallbacks(splitMenuIdleRunnable)
        splitMenuIdleHandler.postDelayed(splitMenuIdleRunnable, SPLIT_MENU_IDLE_TIMEOUT_MS)
    }

    private fun containerIdFor(target: Mode): Int = when (target) {
        Mode.MASTER -> R.id.fragment_container_master
        Mode.PLAYER -> R.id.main_content_container_player
        Mode.PLAYER_SPLIT -> R.id.main_content_container_player_split // 對應 player_split_main.xml 裡的主畫面容器
    }

    private fun containerIdForCurrent(): Int = containerIdFor(mode)

    private fun setMessageBadge(count: Int) {
        val tv = messageBadgeTextViewMaster ?: return
        if (count <= 0) {
            tv.visibility = View.GONE
            return
        }
        tv.visibility = View.VISIBLE
        tv.text = if (count > 99) "99+" else count.toString()
    }

    private fun refreshUnreadBadgeOnMaster() {
        val sp = getSharedPreferences(AppConfig.Prefs.LOGIN_PREFS, MODE_PRIVATE)
        val loggedIn = sp.getBoolean("SESSION_LOGGED_IN", false)
        val userKey = sp.getString("SESSION_USER_KEY", null)

        if (!loggedIn || userKey.isNullOrBlank()) {
            setMessageBadge(0)
            return
        }

        lifecycleScope.launch {
            try {
                val resp = com.champion.king.data.api.RetrofitClient.apiService
                    .getUnreadCount(userKey = userKey, category = "ALL")

                if (!resp.isSuccessful) {
                    Log.e(TAG, "[getUnreadCount] http=${resp.code()} msg=${resp.message()}")
                    return@launch
                }

                val body = resp.body()
                val unread = body?.unread ?: 0
                setMessageBadge(unread)

            } catch (e: Exception) {
                Log.e(TAG, "[getUnreadCount] exception: ${e.message}", e)
            }
        }
    }

    fun refreshMessageBadge() {
        refreshUnreadBadgeOnMaster()
    }

    fun decreaseMessageBadge() {
        val tv = messageBadgeTextViewMaster ?: return
        if (tv.visibility == View.GONE) return

        val currentText = tv.text.toString()
        if (currentText == "99+") {
            // 如果顯示 99+，扣 1 還是 99+，這時為求精準我們還是讓它背景重刷一次
            refreshMessageBadge()
            return
        }

        val currentCount = currentText.toIntOrNull() ?: 0
        if (currentCount > 0) {
            setMessageBadge(currentCount - 1)
        }
    }

    fun clearMessageBadge() {
        val tv = messageBadgeTextViewMaster ?: return
        tv.visibility = View.GONE
        tv.text = "0"
    }

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
            Mode.PLAYER_SPLIT -> currentTimeTextViewPlayerSplit?.text = text
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
        hasHandledSuspension = false

        // ✅ 登入當下就先判斷（避免畫面先進主頁又被踢）
        if (loggedInUser.accountStatus == "SUSPENDED") {
            Log.d(TAG, "登入成功但帳號為 SUSPENDED，立即登出")
            handleAccountSuspended()
            return
        }

        saveLoginSession(loggedInUser)

        Log.d(TAG, "登入成功，右上角資訊已更新為: ${loggedInUser.account}")
        render(Mode.MASTER)

        setupForceLogoutWatcher()
        setupAccountStatusWatcher() // ✅ 新增：監聽停用狀態

        // 登入成功後，執行防弊檢查
        Log.d(TAG, "【登入成功】執行防弊檢查")
        performScratchTempSync()

        routeToMasterDisplay()

        triggerAutoUpdateCheck(reason = "login_success")
        ToastManager.show(this, "歡迎回來，${loggedInUser.account}！")
    }

    private fun saveLoginSession(user: User) {
        val sp = getSharedPreferences(AppConfig.Prefs.LOGIN_PREFS, MODE_PRIVATE)
        sp.edit()
            .putBoolean("SESSION_LOGGED_IN", true)
            .putString("SESSION_USER_KEY", user.firebaseKey)
            .putLong(SESSION_LAST_SEEN_AT, System.currentTimeMillis()) // ✅ 登入當下視為「正在使用」
            .apply()
    }

    private fun clearLoginSession() {
        val sp = getSharedPreferences(AppConfig.Prefs.LOGIN_PREFS, MODE_PRIVATE)
        sp.edit()
            .remove("SESSION_LOGGED_IN")
            .remove("SESSION_USER_KEY")
            .remove(SESSION_LAST_SEEN_AT)
            .apply()
    }

    private fun showBootLoadingScreen() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val progress = ProgressBar(this).apply {
            isIndeterminate = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        }

        val tv = TextView(this).apply {
            text = "載入中..."
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                topMargin = (60 * resources.displayMetrics.density).toInt()
            }
        }

        root.addView(progress)
        root.addView(tv)

        setContentView(root)
        enableImmersiveMode()
    }

    /**
     * 將 scratchCardsTemp 中的紀錄同步到正式 scratchCards
     */
    private fun performScratchTempSync() {
        val userKey = currentUser?.firebaseKey ?: return
        Log.d(TAG, "【同步 scratchCardsTemp】開始同步用戶 $userKey 的暫存刮卡紀錄")

        // 🌟 呼叫本地硬碟回補機制
        syncLocalPendingScratches()

        val userRef = database.child("users").child(userKey)
        val tempRef = userRef.child("scratchCardsTemp")

        tempRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    Log.d(TAG, "【同步 scratchCardsTemp】沒有暫存紀錄，略過。")
                    return
                }

                // 🌟 改為 Triple: 卡號, 子版ID(可為null), 格子號碼
                val updates = mutableListOf<Triple<String, String?, Int>>()
                for (child in snapshot.children) {
                    val cardId = child.child("cardId").getValue(String::class.java)
                    val boardId = child.child("boardId").getValue(String::class.java) // 舊版沒這個欄位會是 null
                    val cellNumber = child.child("cellNumber").getValue(Int::class.java)
                    if (cardId != null && cellNumber != null) {
                        updates.add(Triple(cardId, boardId, cellNumber))
                    }
                }

                if (updates.isEmpty()) return
                Log.d(TAG, "【同步 scratchCardsTemp】共 ${updates.size} 筆要更新")

                for ((cardId, boardId, cellNumber) in updates) {
                    // 🌟 智慧判斷路徑
                    val targetRef = if (boardId != null) {
                        userRef.child("scratchCards").child(cardId).child("boards").child(boardId).child("numberConfigurations")
                    } else {
                        userRef.child("scratchCards").child(cardId).child("numberConfigurations")
                    }

                    targetRef.addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(configSnapshot: DataSnapshot) {
                            for ((index, config) in configSnapshot.children.withIndex()) {
                                val id = config.child("id").getValue(Int::class.java)
                                if (id == cellNumber) {
                                    targetRef.child(index.toString()).child("scratched").setValue(true)
                                    Log.d(TAG, "【同步 scratchCardsTemp】已補寫 scratched=true: 卡=$cardId, 板=${boardId ?: "單一版"}, 格=$cellNumber")
                                    break
                                }
                            }
                        }
                        override fun onCancelled(error: DatabaseError) {}
                    })
                }

                // 全部同步後清空暫存表
                tempRef.removeValue()
            }
            override fun onCancelled(error: DatabaseError) {}
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
        splitMode: String?,
        isMaster: Boolean
    ) {
        val (specialPrizeTv, grandPrizeTv) = if (isMaster) {
            specialPrizeTextViewMaster to grandPrizeTextViewMaster
        } else {
            specialPrizeTextViewPlayer to grandPrizeTextViewPlayer
        }

        // 🌟 核心修改：如果是台主頁面，且版面是分割版面
        if (isMaster && !splitMode.isNullOrEmpty()) {
            // 隱藏原本的特獎與大獎相關元件
            for (i in 0 until prizeInfoContainerMaster.childCount) {
                val child = prizeInfoContainerMaster.getChildAt(i)
                if (child.tag != "split_mode_label") {
                    child.visibility = View.GONE
                }
            }

            // 動態加入或更新「版型字樣」
            var splitLabelTv = prizeInfoContainerMaster.findViewWithTag<TextView>("split_mode_label")
            if (splitLabelTv == null) {
                splitLabelTv = TextView(this@MainActivity).apply {
                    tag = "split_mode_label"
                    textSize = 28f
                    setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.black))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER // 🌟 讓文字內容置中
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, // 🌟 寬度設為 MATCH_PARENT 確保完美置中
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (24 * resources.displayMetrics.density).toInt()
                    }
                }
                prizeInfoContainerMaster.addView(splitLabelTv)
            }

            // 🌟 將 "20x4" 轉換為 "20刮x4板" 格式
            val displaySplitMode = if (splitMode.contains("x")) {
                val parts = splitMode.split("x")
                if (parts.size == 2) {
                    "${parts[0]}刮x${parts[1]}板"
                } else {
                    "${splitMode}版型" // 防呆兜底
                }
            } else {
                "${splitMode}版型" // 防呆兜底
            }

            splitLabelTv.text = displaySplitMode
            splitLabelTv.visibility = View.VISIBLE

            return // 分割版面不需要繼續渲染下面的單一版面獎項，直接結束

        } else if (isMaster) {
            // 🌟 恢復單一版面的正常顯示：隱藏版型字樣，顯示特獎大獎
            for (i in 0 until prizeInfoContainerMaster.childCount) {
                val child = prizeInfoContainerMaster.getChildAt(i)
                if (child.tag == "split_mode_label") {
                    child.visibility = View.GONE
                } else {
                    child.visibility = View.VISIBLE
                }
            }
        }

        // === 特獎 (單一版面邏輯維持不變) ===
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

        // === 大獎 ===
        if (grandPrizeTv is LinearLayout) {
            displayGrandPrizes(grandPrizeTv, grandPrize)
        }
    }

    private fun displayGrandPrizes(grandPrizeContainer: LinearLayout, grandPrizeStr: String?) {
        // 1. 清空容器
        grandPrizeContainer.removeAllViews()

        val noPrize = grandPrizeStr.isNullOrBlank() || grandPrizeStr == "無"
        if (noPrize) {
            val tv = TextView(this).apply {
                text = "無"
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.black))
                textSize = 20f
            }
            grandPrizeContainer.addView(tv)
            // 確保顯示（如果之前被隱藏）
            grandPrizeContainer.alpha = 1f
            return
        }

        val allNumbers = grandPrizeStr!!.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .take(16)

        val columns = 4
        val rows = allNumbers.chunked(columns)

        fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

        val maxSizePx = dp(31)         // 理想最大大小
        val minSizePx = dp(18)         // 最小允許大小
        val gapPx = dp(6)              // 水平間距
        val vGapPx = dp(6)             // 垂直間距

        val green = ContextCompat.getColor(this, R.color.scratch_card_green)
        val whiteText = ContextCompat.getColor(this, android.R.color.white)

        // 定義繪製函式
        fun build(sizePx: Int) {
            grandPrizeContainer.removeAllViews()

            val textSizeSp = when {
                sizePx <= dp(20) -> 10f
                sizePx <= dp(24) -> 11f
                else -> 12f
            }

            for (rowNumbers in rows) {
                val rowLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = vGapPx
                    }
                }

                rowNumbers.forEachIndexed { idx, num ->
                    val tv = TextView(this).apply {
                        text = num.toString()
                        textSize = textSizeSp
                        setTextColor(whiteText)
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(green)
                            setStroke(3, green)
                        }
                        layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                            if (idx != 0) leftMargin = gapPx
                        }
                    }
                    rowLayout.addView(tv)
                }
                grandPrizeContainer.addView(rowLayout)
            }
        }

        // ==========================================
        //  修正邏輯：處理 wrap_content 初始寬度為 0 的問題
        // ==========================================

        val executeLayout = Runnable {
            val parentView = grandPrizeContainer.parent as? View

            val containerW = grandPrizeContainer.width -
                    grandPrizeContainer.paddingLeft -
                    grandPrizeContainer.paddingRight

            val parentW = if (parentView != null) {
                parentView.width - parentView.paddingLeft - parentView.paddingRight
            } else 0

            val w = if (containerW > 0) containerW else parentW

            // 如果取得寬度仍 <= 0，恢復顯示並結束（避免無限隱藏），但通常這步會有寬度了
            if (w <= 0) {
                grandPrizeContainer.alpha = 1f
                return@Runnable
            }

            // 計算適合的大小
            val computedSize = ((w - (gapPx * (columns - 1))) / columns)
                .coerceIn(minSizePx, maxSizePx)

            // 用正確大小重畫
            build(computedSize)

            // 最後顯示出來
            grandPrizeContainer.alpha = 1f
        }

        if (grandPrizeContainer.width > 0) {
            // 如果已經有寬度（例如資料刷新），直接執行
            grandPrizeContainer.alpha = 1f
            executeLayout.run()
        } else {
            // 【關鍵修正】
            // 如果寬度是 0 (因為 wrap_content 且是空的)，我們必須先塞入東西把它「撐開」。
            // 為了不讓使用者看到撐開的過程（避免閃爍），我們先設為透明 (alpha = 0)。

            build(maxSizePx) // 先用最大尺寸撐開版面
            grandPrizeContainer.alpha = 0f // 隱藏

            // 等待 Layout 完成，取得被撐開後的實際可用寬度，再重畫
            grandPrizeContainer.post(executeLayout)
        }
    }

    private fun fetchAndDisplayPrizeInfo(userFirebaseKey: String, isMaster: Boolean) {
        database.child("users").child(userFirebaseKey).child("scratchCards")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    var specialPrize: String? = null
                    var grandPrize: String? = null
                    var splitMode: String? = null // 🌟 新增讀取版型

                    for (child in snapshot.children) {
                        val card = child.getValue(ScratchCard::class.java)
                        if (card != null && card.inUsed) {
                            specialPrize = card.specialPrize
                            grandPrize = card.grandPrize
                            splitMode = card.splitMode // 🌟 取得分割版面屬性
                            break
                        }
                    }
                    // 將 splitMode 傳給 UI 渲染函數
                    updatePrizeInfoSeparate(specialPrize, grandPrize, splitMode, isMaster)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "載入獎項資訊失敗：${error.message}")

                    // ❗❗ 若使用者已登出 → 強制回歸預設 UI「無」
                    if (currentUser == null) {
                        updatePrizeInfoSeparate(null, null, null, isMaster)
                        return
                    }

                    // 其它錯誤再顯示載入失敗
                    updatePrizeInfoSeparate("無", "無", null, isMaster)
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
        removeAccountStatusWatcher()
        hasHandledSuspension = false

        // Firebase Auth 登出（建議統一做，避免 token 還在）
        try {
            FirebaseAuth.getInstance().signOut()
        } catch (_: Exception) {}

        SettingsViewModel.clearAllDrafts()
        clearLoginSession()
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

    private fun setupAccountStatusWatcher() {
        val userKey = currentUser?.firebaseKey ?: return

        // users/{uid}/accountStatus
        accountStatusRef = database.child("users").child(userKey).child("accountStatus")

        accountStatusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.getValue(String::class.java) ?: "ACTIVE"
                Log.d(TAG, "AccountStatus changed: $status")

                if (status == "SUSPENDED") {
                    handleAccountSuspended()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "AccountStatus 監聽錯誤：${error.message}")
            }
        }

        accountStatusRef?.addValueEventListener(accountStatusListener!!)
    }

    private fun removeAccountStatusWatcher() {
        try {
            accountStatusListener?.let { listener ->
                accountStatusRef?.removeEventListener(listener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "移除 accountStatus 監聽器時發生錯誤：${e.message}")
        }
        accountStatusListener = null
        accountStatusRef = null
    }

    private fun handleAccountSuspended() {
        if (hasHandledSuspension) return
        hasHandledSuspension = true

        Log.d(TAG, "偵測到帳號已停用（SUSPENDED），即刻登出")

        // 先移除監聽，避免重複觸發
        removeAccountStatusWatcher()
        removeForceLogoutWatcher()

        // Firebase Auth 登出
        try {
            FirebaseAuth.getInstance().signOut()
        } catch (_: Exception) {}

        runOnUiThread {
            performLogoutWithMessage("您的帳號已被停用，請聯繫小編協助開通")
        }
    }

    private fun performLogoutWithMessage(message: String) {
        // 保險：清乾淨所有監聽
        removeForceLogoutWatcher()
        removeAccountStatusWatcher()

        SettingsViewModel.clearAllDrafts()
        clearLoginSession()
        currentUser = null

        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        render(Mode.MASTER)
        loadFragment(LoginFragment(), containerIdFor(Mode.MASTER))

        ToastManager.show(this, message)
        Log.d(TAG, "用戶已登出（原因：$message）")
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
    // ====== Player: 下一版密碼、切換邏輯 ======
    private fun checkHasNextVersion(userFirebaseKey: String, onResult: (Boolean, Int) -> Unit) {
        database.child("users").child(userFirebaseKey).child("scratchCards")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    var validCardCount = 0
                    var inUseCount = 0

                    for (child in snapshot.children) {
                        val card = child.getValue(ScratchCard::class.java)
                        if (card != null && card.order != null) {
                            validCardCount++
                            if (card.inUsed == true) {
                                inUseCount++
                            }
                        }
                    }

                    if (validCardCount == 0) {
                        // 0 張卡：無版可切
                        onResult(false, 0)
                    } else if (validCardCount == 1) {
                        // 1 張卡：如果它還沒被啟用，就可以切換去啟用它
                        onResult(inUseCount == 0, 1)
                    } else {
                        // 大於 1 張卡：絕對可以切換
                        onResult(true, validCardCount)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "檢查版位數量失敗: ${error.message}")
                    onResult(false, 0)
                }
            })
    }

    private fun showNextVersionPasswordInputDialog() {
        val key = currentUser?.firebaseKey ?: run {
            ToastManager.show(this, "驗證失敗：未找到用戶。")
            return
        }

        // 🌟 接收回傳的 (是否可切換, 卡片總數)
        checkHasNextVersion(key) { hasNext, cardCount ->
            if (!hasNext) {
                // 🌟 根據卡片總數給出最精準的提示
                if (cardCount == 0) {
                    ToastManager.show(this@MainActivity, "目前無可用刮板")
                } else {
                    ToastManager.show(this@MainActivity, "目前只有一個版面，沒有下一版可切換")
                }
                return@checkHasNextVersion
            }

            // 確認有下一版，才顯示密碼輸入框
            val input = EditText(this).apply {
                hint = "請輸入換版密碼"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }

            val dialog = AlertDialog.Builder(this)
                .setTitle("換版密碼")
                .setMessage("請輸入換版密碼：")
                .setView(input)
                .setPositiveButton("確定") { d, _ ->
                    val pwd = input.text.toString().trim()
                    if (pwd.isNotEmpty()) verifySwitchVersionPassword(pwd)
                    else ToastManager.show(this, "密碼不能為空！")
                    d.dismiss()
                }
                .setNegativeButton("取消") { d, _ -> d.dismiss() }
                .create()

            val timeoutHelper = DialogTimeoutHelper(dialog, input)

            dialog.setOnShowListener { timeoutHelper.startTimer() }
            dialog.setOnDismissListener { timeoutHelper.stopTimer() }

            dialog.show()
        }
    }

    private fun verifySwitchVersionPassword(enteredPassword: String) {
        val key = currentUser?.firebaseKey ?: return

        database.child("users").child(key).child("switchScratchCardPassword")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    val stored = s.getValue(String::class.java)
                    if (stored != null && enteredPassword == stored) {
                        // 🌟 密碼正確，呼叫過場動畫，把切換邏輯包進去
                        executeWithTransitionOverlay {
                            switchToNextVersion(key)
                        }
                    } else {
                        ToastManager.show(this@MainActivity, "換版密碼錯誤，請重新輸入！")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    ToastManager.show(this@MainActivity, "驗證失敗：${error.message}")
                }
            })
    }

    private fun switchToNextVersion(userFirebaseKey: String) {
        database.child("users").child(userFirebaseKey).child("scratchCards")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val allCards = mutableListOf<Pair<String, ScratchCard>>()
                    for (child in snapshot.children) {
                        val serialNumber = child.key ?: continue
                        val card = child.getValue(ScratchCard::class.java) ?: continue
                        if (card.order != null) {
                            allCards.add(serialNumber to card)
                        }
                    }

                    if (allCards.isEmpty()) {
                        ToastManager.show(this@MainActivity, "沒有可用的刮刮卡版位")
                        return
                    }

                    // 嚴格按照 order 排序
                    allCards.sortBy { it.second.order }

                    val currentInUseIndex = allCards.indexOfFirst { it.second.inUsed == true }

                    // 防呆：如果只有 1 張且已經在使用中，不執行切換
                    if (allCards.size == 1 && currentInUseIndex != -1) {
                        ToastManager.show(this@MainActivity, "目前只有一個版面，沒有下一版可切換")
                        return
                    }

                    // 🌟 修正：如果沒有任何版面使用中 (index = -1)，預設啟用排序第 1 張 (index = 0)
                    // 如果跑到最後一版，就 % 取餘數回到第 0 索引
                    val nextIndex = if (currentInUseIndex == -1) 0 else (currentInUseIndex + 1) % allCards.size

                    val nextCard = allCards[nextIndex]
                    val nextSerialNumber = nextCard.first
                    val nextOrder = nextCard.second.order

                    // 批次更新 Firebase：將目標卡片設為 true，其他全部設為 false
                    val updates = mutableMapOf<String, Any>()
                    for (card in allCards) {
                        updates["${card.first}/inUsed"] = (card.first == nextSerialNumber)
                    }

                    database.child("users").child(userFirebaseKey).child("scratchCards")
                        .updateChildren(updates)
                        .addOnSuccessListener {
                            Log.d(TAG, "成功切換至下一版：版位 $nextOrder (序號: $nextSerialNumber)")

                            // 更新完資料庫後，呼叫分流檢查站，搭配過場動畫滑順切換！
                            checkAndEnterPlayerMode()
                        }
                        .addOnFailureListener { e ->
                            ToastManager.show(this@MainActivity, "切換版位失敗")
                            Log.e(TAG, "更新 inUsed 失敗", e)
                        }
                }

                override fun onCancelled(error: DatabaseError) {
                    ToastManager.show(this@MainActivity, "切換版位失敗：${error.message}")
                }
            })
    }

    // ====== 版面切換過場動畫 ======
    private fun executeWithTransitionOverlay(action: () -> Unit) {
        val decorView = window.decorView as FrameLayout

        // 如果已經有過場動畫在跑，先移除舊的避免重複
        decorView.findViewWithTag<View>("transition_overlay")?.let { decorView.removeView(it) }

        val transitionView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.BLACK)
            alpha = 0f
            tag = "transition_overlay"

            val textView = TextView(this@MainActivity).apply {
                text = "載入版面中..."
                setTextColor(android.graphics.Color.WHITE)
                textSize = 24f
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            }
            addView(textView)
        }

        decorView.addView(transitionView)

        // 1. 淡入黑畫面 (250毫秒)
        transitionView.animate()
            .alpha(1f)
            .setDuration(250)
            .withEndAction {
                // 2. 畫面全黑後，執行實際的切換邏輯
                action.invoke()

                // 3. 延遲 1 秒後開始淡出黑畫面
                Handler(Looper.getMainLooper()).postDelayed({

                    // 獨立定義移除 View 的動作
                    val removeRunnable = Runnable {
                        if (transitionView.parent != null) {
                            decorView.removeView(transitionView)
                        }
                    }

                    transitionView.animate()
                        .alpha(0f)
                        .setDuration(350)
                        .withEndAction(removeRunnable)
                        .start()

                    // 🛑 核心防呆：不管動畫有沒有被 Android 系統強制中斷，
                    // 給它多 50 毫秒的緩衝，時間到強制移除 View！
                    Handler(Looper.getMainLooper()).postDelayed(removeRunnable, 400)

                }, 1000)
            }
            .start()
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

        // 🌟 呼叫小幫手，並把需要監聽的 EditText 傳進去
        val timeoutHelper = DialogTimeoutHelper(dialog, accountEt, passwordEt)

        dialog.setOnShowListener {
            ToastManager.setHostWindow(dialog.window)
            timeoutHelper.startTimer() // 🌟 啟動倒數

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
            timeoutHelper.stopTimer() // 🌟 關閉倒數
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
                    saveLoginSession(user)
                    render(Mode.MASTER)
                    performScratchTempSync()
                    routeToMasterDisplay()
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
        val dialog = AlertDialog.Builder(this)
            .setTitle("無法切換版位")
            .setMessage(message)
            .setPositiveButton("確定") { d, _ -> d.dismiss() }
            .setCancelable(false)
            .create()

        // 🌟 呼叫小幫手 (這個視窗沒有輸入框，所以不傳 EditText)
        val timeoutHelper = DialogTimeoutHelper(dialog)

        dialog.setOnShowListener { timeoutHelper.startTimer() } // 🌟 啟動
        dialog.setOnDismissListener { timeoutHelper.stopTimer() } // 🌟 關閉

        dialog.show()
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

        // 👇 加入這一行，強制要求使用者只能透過按鈕操作，點擊外部區域將不會有任何反應
        dialog.setCanceledOnTouchOutside(false)

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
        markSessionSeen() // ✅ 使用者有操作就刷新 lastSeenAt

        // 🌟 新增：攔截分割版面抽屜選單開啟時的外部點擊
        if (isMenuOpen && ev != null && ev.action == android.view.MotionEvent.ACTION_DOWN) {
            // 取得「選單內容」與「開關按鈕」目前的螢幕絕對座標範圍
            val menuRect = android.graphics.Rect()
            menuContentLayout?.getGlobalVisibleRect(menuRect)

            val buttonRect = android.graphics.Rect()
            menuToggleButton?.getGlobalVisibleRect(buttonRect)

            val touchX = ev.rawX.toInt()
            val touchY = ev.rawY.toInt()

            // 判斷點擊的位置是否「不在選單內部」且「不在開關按鈕上」
            val isTouchOutside = (menuRect.width() > 0 && !menuRect.contains(touchX, touchY)) &&
                    (buttonRect.width() > 0 && !buttonRect.contains(touchX, touchY))

            if (isTouchOutside) {
                Log.d(TAG, "點擊選單外部，自動收合選單並攔截底層點擊事件")
                closeSplitMenu()
                splitMenuIdleHandler.removeCallbacks(splitMenuIdleRunnable) // 收合後清除計時器
                return true // 🛑 核心修改：回傳 true 攔截這個 ACTION_DOWN 事件，不往下傳遞給刮板！
            } else {
                // 點擊在選單內部，重置抽屜閒置計時器
                resetSplitMenuIdleTimer()
            }
        } else if (isMenuOpen) {
            // 滑動等其他手勢，只要選單開著就重置閒置計時器
            resetSplitMenuIdleTimer()
        }

        return super.dispatchTouchEvent(ev)
    }

    fun resetIdleTimer() {
        idleHandler.removeCallbacks(idleRunnable)
        // 💡 讀取 currentUser 的動態設定
        val adEnabled = currentUser?.isAdEnabled ?: false
        if (adEnabled) {
            val minutes = currentUser?.idleAdMinutes ?: 15
            // 💡 確保時間單位轉換正確（這裡設定為分鐘 * 60秒 * 1000毫秒）
            val dynamicTimeoutMillis = minutes * 60 * 1000L
            idleHandler.postDelayed(idleRunnable, dynamicTimeoutMillis)
            Log.d(TAG, "廣告計時器已重置，將在 $minutes 分鐘後觸發")
        } else {
            Log.d(TAG, "廣告功能未啟用，計時器不啟動")
        }
    }

    private fun showAdPoster() {
        val adEnabled = currentUser?.isAdEnabled ?: false
        if (!adEnabled) return

        runOnUiThread {
            val decorView = window.decorView as FrameLayout

            val posterContainer = FrameLayout(this).apply {
                alpha = 0f
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.BLACK)
            }

            val posterImage = ImageView(this).apply {
                setImageResource(R.drawable.splash_poster)
                scaleType = ImageView.ScaleType.FIT_XY
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            posterContainer.addView(posterImage)

            // 💡 點擊海報時的密碼解鎖判斷
            posterContainer.setOnClickListener {
                val savedPassword = currentUser?.unlockAdPassword

                if (!savedPassword.isNullOrEmpty()) {
                    // 有設定密碼：跳出要求輸入密碼小視窗
                    showUnlockAdPasswordDialog(posterContainer, savedPassword)
                } else {
                    // 沒設定密碼：直接關閉海報
                    closeAdPoster(posterContainer)
                }
            }

            decorView.addView(posterContainer)

            posterContainer.animate()
                .alpha(1f)
                .setDuration(400)
                .withEndAction {
                    startFloatingTapHint(posterContainer)
                }
                .start()
        }
    }

    // 關閉海報共用函式
    private fun closeAdPoster(posterContainer: View) {
        posterContainer.animate()
            .alpha(0f)
            .setDuration(400)
            .withEndAction {
                (window.decorView as FrameLayout).removeView(posterContainer)
                resetIdleTimer() // 恢復計時
            }
            .start()
    }

    // 顯示解鎖密碼對話框
    private fun showUnlockAdPasswordDialog(posterContainer: View, correctPassword: String) {
        val input = EditText(this).apply {
            hint = "請輸入解除廣告密碼"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("解除廣告")
            .setView(input)
            .setCancelable(false) // 防止點旁邊關閉，強迫一定要輸入對
            .setPositiveButton("確定", null) // 💡 關鍵 1：傳入 null，告訴系統「不要自動幫我關閉視窗」
            .setNegativeButton("取消") { d, _ ->
                d.dismiss()
            }
            .create()

        // 💡 加入你在其他地方寫好的防呆小幫手，避免對話框開著放太久
        val timeoutHelper = DialogTimeoutHelper(dialog, input)

        dialog.setOnShowListener {
            ToastManager.setHostWindow(dialog.window)
            timeoutHelper.startTimer() // 啟動倒數

            // 💡 關鍵 2：視窗顯示後，我們才自己去接管「確定」按鈕的點擊事件
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                if (input.text.toString() == correctPassword) {
                    // 密碼正確：關閉海報、關閉視窗
                    closeAdPoster(posterContainer)
                    dialog.dismiss()
                } else {
                    // 💡 密碼錯誤：不呼叫 dismiss()！直接在輸入框顯示紅色錯誤提示，並清空輸入內容
                    input.error = "密碼錯誤"
                    input.setText("")
                }
            }
        }

        dialog.setOnDismissListener {
            ToastManager.clearHostWindow()
            timeoutHelper.stopTimer() // 關閉倒數
        }

        dialog.show()
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
            }
        }
    }

    fun relockFromPlayerGesture() {
        // 🌟 修改：讓單一玩家版面與分割玩家版面都能呼叫重新鎖定
        if (mode != Mode.PLAYER && mode != Mode.PLAYER_SPLIT) return
        enableImmersiveMode()
        lockAppToScreen()
    }

    fun unlockAppFromScreen() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            stopLockTask()
        }
    }

    /**
     * 從本地 SharedPreferences 撈取因為「斷線+滑掉APP」而遺失的刮卡紀錄，並強制回補到資料庫
     */
    private fun syncLocalPendingScratches() {
        val userKey = currentUser?.firebaseKey ?: return
        val sp = getSharedPreferences("LocalPendingScratches_$userKey", MODE_PRIVATE)
        val pendingSet = sp.getStringSet("pending_scratches", emptySet()) ?: emptySet()

        if (pendingSet.isEmpty()) return

        Log.d(TAG, "🚨 【本地硬碟防弊】發現 ${pendingSet.size} 筆斷線且APP被關閉的遺失紀錄，啟動強制回補！")
        val userRef = database.child("users").child(userKey)

        pendingSet.forEach { entry ->
            val parts = entry.split(":")
            // 🌟 支援舊版 (長度2) 與 新版分割 (長度3)
            if (parts.size == 2 || parts.size == 3) {
                val cardId = parts[0]
                val boardId = if (parts.size == 3) parts[1] else null
                val cellNumber = if (parts.size == 3) parts[2].toIntOrNull() else parts[1].toIntOrNull()

                if (cellNumber != null) {
                    // 🌟 智慧判斷路徑
                    val targetRef = if (boardId != null) {
                        userRef.child("scratchCards").child(cardId).child("boards").child(boardId).child("numberConfigurations")
                    } else {
                        userRef.child("scratchCards").child(cardId).child("numberConfigurations")
                    }

                    targetRef.addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(configSnapshot: DataSnapshot) {
                            for ((index, config) in configSnapshot.children.withIndex()) {
                                val id = config.child("id").getValue(Int::class.java)
                                if (id == cellNumber) {
                                    // 執行回補
                                    targetRef.child(index.toString()).child("scratched").setValue(true)
                                    targetRef.child(index.toString()).child("scratchedAt").setValue(ServerValue.TIMESTAMP)
                                    Log.d(TAG, "✅ 【本地硬碟防弊】成功回補遺失紀錄: 卡=$cardId, 板=${boardId ?: "單一版"}, 格=$cellNumber")

                                    // 回補成功後移除
                                    val currentSp = getSharedPreferences("LocalPendingScratches_$userKey", MODE_PRIVATE)
                                    val currentSet = currentSp.getStringSet("pending_scratches", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                                    currentSet.remove(entry)
                                    currentSp.edit().putStringSet("pending_scratches", currentSet).apply()
                                    break
                                }
                            }
                        }
                        override fun onCancelled(error: DatabaseError) {}
                    })
                }
            }
        }
    }

    // 🌟 1. 統一設定閒置時間：以後要改幾分鐘，只要改這裡就好！（60_000L = 60秒）
    private val DIALOG_IDLE_TIMEOUT_MS = 60_000L

    // 🌟 2. 抽出共用的計時器小幫手
    private inner class DialogTimeoutHelper(
        private val dialog: AlertDialog,
        vararg editTexts: EditText // 允許傳入多個輸入框
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private val dismissRunnable = Runnable {
            if (dialog.isShowing) {
                ToastManager.show(this@MainActivity, "閒置過久，已自動關閉視窗")
                dialog.dismiss()
                Log.d("DialogTimeout", "視窗已因閒置自動關閉")
            }
        }

        init {
            // 監聽所有傳入的輸入框，只要有打字就重新倒數
            val textWatcher = object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { resetTimer() }
            }
            editTexts.forEach { it.addTextChangedListener(textWatcher) }
        }

        fun startTimer() {
            handler.removeCallbacks(dismissRunnable)
            handler.postDelayed(dismissRunnable, DIALOG_IDLE_TIMEOUT_MS)
        }

        fun stopTimer() {
            handler.removeCallbacks(dismissRunnable)
        }

        private fun resetTimer() {
            startTimer()
        }
    }

    fun devBypassToSettings() {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "🥷 開發者捷徑：直接切換到台主設置頁面")
            unlockAppFromScreen() // 1. 解除玩家頁面的螢幕鎖定
            render(Mode.MASTER)   // 2. 切換為台主版面
            loadFragment(SettingsFragment(), containerIdFor(Mode.MASTER)) // 3. 直接載入設置頁面
        }
    }

    fun getCurrentUser(): User? {
        return currentUser
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}