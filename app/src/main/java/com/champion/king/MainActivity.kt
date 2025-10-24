package com.champion.king

import android.app.AlertDialog
import android.content.pm.ActivityInfo
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
import com.champion.king.security.PasswordUtils
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*
import com.champion.king.auth.FirebaseAuthHelper
import androidx.lifecycle.lifecycleScope
import com.champion.king.util.ApkDownloader
import kotlinx.coroutines.launch
import com.champion.king.util.UpdateManager
import com.champion.king.util.UpdateResult
import com.champion.king.util.toast

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

    // 如果你已有 AppConfig 可置換此常數，避免重複字串
    private val DB_URL =
        "https://sca3-69342-default-rtdb.asia-southeast1.firebasedatabase.app"

    // ====== Lifecycle ======
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateTimeRunnable)

        // 回到主頁時檢查更新（需已登入且開啟自動檢查）
        if (isUserLoggedIn() && updateManager.isAutoCheckEnabled()) {
            val lastCheckTime = updateManager.getLastCheckTime()
            val timeDiff = System.currentTimeMillis() - lastCheckTime

            // 距離上次檢查超過 5 分鐘才檢查
            if (timeDiff > 5 * 60 * 1000) {
                checkUpdateInBackground()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateTimeRunnable)
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
            }

            Mode.PLAYER -> {
                setContentView(R.layout.player_main)
                initPlayerViews()
                updateCurrentTime()
                currentUser?.firebaseKey?.let { key ->
                    fetchAndDisplayPrizeInfo(key, isMaster = false)
                    fetchAndDisplayClawsGiveawayInfo(key, giveawayCountTextViewPlayer)
                } ?: run {
                    // 未登入時清空顯示
                    specialPrizeTextViewPlayer?.let {
                        updatePrizeInfo(it, grandPrizeTextViewPlayer ?: it, null, null)
                    }
                    updateClawsGiveawayInfo(0, 0, giveawayCountTextViewPlayer)
                }
                // 切玩家頁面即載入顯示頁
                loadFragment(ScratchCardPlayerFragment(), containerIdFor(Mode.PLAYER))
                Toast.makeText(this, "已切換至玩家頁面", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "已切換至玩家頁面。")
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
                    loadFragment(BackpackFragment(), containerIdFor(Mode.MASTER))
                }
                R.id.shop_button_master -> {
                    Log.d(TAG, "Shop button clicked!")
                    loadFragment(ShopFragment(), containerIdFor(Mode.MASTER))
                }
                R.id.user_button_master -> {
                    Log.d(TAG, "User button clicked!")
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

        // 關於平板按鈕
        findViewById<ImageView>(R.id.pad_button_master).setOnClickListener {
            Log.d(TAG, "Pad button clicked! 載入關於平板頁面")
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
                // 切換到玩家頁面前，先執行防弊檢查
                Log.d(TAG, "【切換玩家頁面】執行防弊檢查")
                performAnticheatCheck()

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

        // 登入成功後，執行防弊檢查
        Log.d(TAG, "【登入成功】執行防弊檢查")
        performAnticheatCheck()

        // 現在會載入和玩家頁面一致但無互動的刮卡顯示
        loadFragment(ScratchCardDisplayFragment(), containerIdFor(Mode.MASTER))
        Toast.makeText(this, "歡迎回來，${loggedInUser.account}！", Toast.LENGTH_SHORT).show()

        // 登入成功後檢查更新
        if (updateManager.isAutoCheckEnabled()) {
            checkUpdateInBackground()
        }
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

        val grayColor = ContextCompat.getColor(this, R.color.scratch_card_light_gray)
        val defaultColor = ContextCompat.getColor(this, android.R.color.black)

        // 特獎使用金色圓框、白底、灰色文字
        specialPrizeTv?.apply {
            text = specialPrize ?: "無"
            setTextColor(grayColor)
            setBackgroundResource(R.drawable.special_prize_gold_circle)
        }

        if (grandPrizeTv != null && grandPrizeTv is LinearLayout) {
            displayGrandPrizes(grandPrizeTv, grandPrize)
        }
    }

    private fun displayGrandPrizes(grandPrizeContainer: LinearLayout, grandPrizeStr: String?) {
        grandPrizeContainer.removeAllViews()

        if (grandPrizeStr.isNullOrBlank()) {
            val tv = TextView(this).apply {
                text = "無"
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.black))
                textSize = 20f
            }
            grandPrizeContainer.addView(tv)
            return
        }

        // 最多 12 個大獎，4 個一排
        val allNumbers = grandPrizeStr.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .take(12)
        val chunked = allNumbers.chunked(4)

        val greenColor = ContextCompat.getColor(this, R.color.scratch_card_green)
        val whiteColor = ContextCompat.getColor(this, R.color.scratch_card_white)
        val grayTextColor = ContextCompat.getColor(this, R.color.scratch_card_light_gray)

        // 每顆圓圈大小約 28dp，比之前略小一點
        val sizePx = (28 * resources.displayMetrics.density).toInt()
        val marginPx = (2 * resources.displayMetrics.density).toInt()

        for (rowNumbers in chunked) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
            }

            for (num in rowNumbers) {
                val numView = TextView(this).apply {
                    text = num.toString()
                    textSize = 12f
                    setTextColor(grayTextColor)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(whiteColor)
                        setStroke(3, greenColor)
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
            rowParams.setMargins(0, marginPx, 0, 0) // 行距縮小
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
                    updatePrizeInfoSeparate("載入失敗", "載入失敗", isMaster)
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
                    for (child in snapshot.children) {
                        val card = child.getValue(ScratchCard::class.java)
                        if (card != null && card.inUsed) {
                            totalClaws += card.clawsCount ?: 0
                            totalGiveaway += card.giveawayCount ?: 0
                        }
                    }
                    updateClawsGiveawayInfo(totalClaws, totalGiveaway, targetTextView)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "載入夾出/贈送刮數失敗: ${error.message}", error.toException())
                    updateClawsGiveawayInfo(0, 0, targetTextView)
                }
            })
    }

    private fun updateClawsGiveawayInfo(
        clawsCount: Int,
        giveawayCount: Int,
        targetTextView: TextView?
    ) {
        targetTextView?.text = "夾出${clawsCount}樣\n贈送${giveawayCount}刮"
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

    private fun performLogout() {
        currentUser = null
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        render(Mode.MASTER)
        loadFragment(LoginFragment(), containerIdFor(Mode.MASTER))
        Toast.makeText(this, "您已成功登出。", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "用戶已登出。")
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
                else Toast.makeText(this, "密碼不能為空！", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消") { d, _ -> d.dismiss() }
            .show()
    }

    private fun updateSwitchScratchCardPassword(newPassword: String) {
        val key = currentUser?.firebaseKey ?: run {
            Toast.makeText(this, "更新失敗：未找到用戶。", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "更新換版密碼失敗：currentUserFirebaseKey 為空。")
            return
        }
        database.child("users").child(key).child("switchScratchCardPassword")
            .setValue(newPassword)
            .addOnSuccessListener {
                Toast.makeText(this, "換版密碼已更新！", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "用戶 $key 的換版密碼已更新為: $newPassword")
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "更新換版密碼失敗: ${e.message}", Toast.LENGTH_LONG).show()
                Log.e(TAG, "更新用戶 $key 換版密碼失敗: ${e.message}", e)
            }
    }

    // ====== Player: 下一版密碼、切回台主 ======
    private fun showNextVersionPasswordInputDialog() {
        val key = currentUser?.firebaseKey ?: run {
            Toast.makeText(this, "驗證失敗：未找到用戶。", Toast.LENGTH_SHORT).show()
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
                        else Toast.makeText(this, "密碼不能為空！", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "驗證失敗：未找到用戶。", Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(
                            this@MainActivity,
                            "換版密碼錯誤，請重新輸入！",
                            Toast.LENGTH_SHORT
                        ).show()
                        Log.d(TAG, "換版密碼驗證失敗，輸入:$enteredPassword, 儲存:$stored")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(
                        this@MainActivity,
                        "驗證失敗：${error.message}",
                        Toast.LENGTH_SHORT
                    ).show()
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
                        Toast.makeText(
                            this@MainActivity,
                            "沒有可用的刮刮卡版位",
                            Toast.LENGTH_SHORT
                        ).show()
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

                    Toast.makeText(this@MainActivity, "已切換至版位 $nextOrder", Toast.LENGTH_SHORT)
                        .show()
                    Log.d(TAG, "成功切換至下一版：版位 $nextOrder (序號: $nextSerialNumber)")
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(
                        this@MainActivity,
                        "切換版位失敗：${error.message}",
                        Toast.LENGTH_SHORT
                    ).show()
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
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val account = accountEt.text.toString().trim()
                val pwd = passwordEt.text.toString().trim()
                if (account.isEmpty() || pwd.isEmpty()) {
                    Toast.makeText(this, "帳號和密碼都必須填寫！", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                verifyMasterCredentials(account, pwd) { ok, msg ->
                    if (ok) dialog.dismiss()
                    else if (!msg.isNullOrBlank()) Toast.makeText(this, msg, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
        dialog.show()
    }

    private fun verifyMasterCredentials(
        account: String,
        passwordInput: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        database.child("users").child(account)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    if (!s.exists()) {
                        onResult(false, "登入失敗：帳號不存在！")
                        return
                    }
                    val user = s.getValue(User::class.java)
                    val salt = s.child("salt").getValue(String::class.java)
                    val storedHash = s.child("passwordHash").getValue(String::class.java)

                    if (salt.isNullOrEmpty() || storedHash.isNullOrEmpty() || user == null) {
                        onResult(false, "此帳號資料不完整，請聯絡管理員")
                        return
                    }

                    val inputHash = PasswordUtils.sha256Hex(salt, passwordInput)
                    if (inputHash.equals(storedHash, ignoreCase = true)) {
                        currentUser = user.apply { firebaseKey = s.key }
                        render(Mode.MASTER)
                        loadFragment(ScratchCardDisplayFragment(), containerIdFor(Mode.MASTER))
                        Toast.makeText(this@MainActivity, "已切換至台主頁面！", Toast.LENGTH_SHORT)
                            .show()
                        onResult(true, null)
                    } else {
                        onResult(false, "登入失敗：密碼錯誤！")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    onResult(false, "登入失敗：${error.message}")
                }
            })
    }

    // ====== Helpers ======
    private fun ensureLoggedIn(): Boolean {
        val ok = currentUser != null
        if (!ok) Toast.makeText(this, "請先登入後再操作！", Toast.LENGTH_SHORT).show()
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

    // 在 MainActivity 類中添加這個方法（放在 companion object 之前）

    /**
     * 執行防弊檢查：將所有 hasTriggeredScratchStart=true 且 scratched=false 的格子標記為 scratched=true
     * 針對當前使用中的刮刮卡版位
     */
    private fun performAnticheatCheck() {
        val currentUserFirebaseKey = currentUser?.firebaseKey ?: return

        Log.d(TAG, "【執行防弊檢查】針對用戶 $currentUserFirebaseKey 的使用中刮刮卡")

        database.child("users")
            .child(currentUserFirebaseKey)
            .child("scratchCards")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    // 找到使用中的刮刮卡
                    var inUsedSerialNumber: String? = null
                    for (child in snapshot.children) {
                        val serialNumber = child.key ?: continue
                        val card = child.getValue(ScratchCard::class.java)
                        if (card != null && card.inUsed == true) {
                            inUsedSerialNumber = serialNumber
                            Log.d(TAG, "【防弊檢查】找到使用中的刮刮卡: $serialNumber (版位 ${card.order})")
                            break
                        }
                    }

                    if (inUsedSerialNumber == null) {
                        Log.d(TAG, "【防弊檢查】沒有找到使用中的刮刮卡")
                        return
                    }

                    // 檢查該刮刮卡的所有格子
                    database.child("users")
                        .child(currentUserFirebaseKey)
                        .child("scratchCards")
                        .child(inUsedSerialNumber)
                        .child("numberConfigurations")
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(configSnapshot: DataSnapshot) {
                                var foundTriggered = false

                                for ((index, child) in configSnapshot.children.withIndex()) {
                                    val id = child.child("id").getValue(Int::class.java) ?: continue
                                    val scratched = child.child("scratched").getValue(Boolean::class.java) ?: false
                                    val hasTriggered = child.child("hasTriggeredScratchStart").getValue(Boolean::class.java) ?: false

                                    // 如果已觸發但尚未標記為 scratched，則補上
                                    if (hasTriggered && !scratched) {
                                        foundTriggered = true
                                        Log.d(TAG, "【防弊檢查】發現格子 $id 已觸發但未刮開 (hasTriggeredScratchStart=true, scratched=false)")
                                        Log.d(TAG, "【防弊檢查】補標記格子 $id 為 scratched=true")

                                        database.child("users")
                                            .child(currentUserFirebaseKey)
                                            .child("scratchCards")
                                            .child(inUsedSerialNumber!!)
                                            .child("numberConfigurations")
                                            .child(index.toString())
                                            .child("scratched")
                                            .setValue(true)
                                            .addOnSuccessListener {
                                                Log.d(TAG, "【防弊檢查】✓ 格子 $id 已成功補標記為 scratched=true")
                                            }
                                            .addOnFailureListener { e ->
                                                Log.e(TAG, "【防弊檢查】✗ 補標記格子 $id 失敗: ${e.message}", e)
                                            }
                                    }
                                }

                                if (!foundTriggered) {
                                    Log.d(TAG, "【防弊檢查】沒有發現需要補標記的格子")
                                }
                            }

                            override fun onCancelled(error: DatabaseError) {
                                Log.e(TAG, "【防弊檢查】讀取格子配置失敗: ${error.message}", error.toException())
                            }
                        })
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "【防弊檢查】讀取刮刮卡失敗: ${error.message}", error.toException())
                }
            })
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
        if (updateManager.isAutoCheckEnabled()) {
            checkUpdateInBackground()
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
                    is UpdateResult.Maintenance -> {
                        showMaintenanceDialog(result.message)
                    }
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
     * 顯示更新對話框
     */
    private fun showUpdateDialog(versionInfo: com.champion.king.data.api.dto.VersionInfo) {
        val message = """
        發現新版本：${versionInfo.versionName}
        
        更新內容：
        ${versionInfo.updateMessage}
    """.trimIndent()

        val builder = android.app.AlertDialog.Builder(this)
            .setTitle("發現新版本")
            .setMessage(message)
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

        builder.create().show()
    }

    /**
     * 顯示維護模式對話框
     */
    private fun showMaintenanceDialog(message: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("系統維護中")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("確定") { dialog, _ ->
                dialog.dismiss()
                finish() // 關閉 APP
            }
            .create()
            .show()
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
                        toast("下載完成，準備安裝")
                    } else {
                        toast(message)
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

    companion object {
        private const val TAG = "MainActivity"
    }
}
