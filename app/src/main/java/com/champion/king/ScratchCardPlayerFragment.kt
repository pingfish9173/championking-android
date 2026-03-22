package com.champion.king

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.champion.king.model.ScratchCard
import com.google.firebase.database.*
import android.widget.GridLayout
import android.app.AlertDialog
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.champion.king.util.ToastManager
import com.google.firebase.database.ServerValue
import android.os.Handler
import android.os.Looper


class ScratchCardPlayerFragment : Fragment() {

    private lateinit var scratchCardContainer: FrameLayout
    private lateinit var noScratchCardText: TextView
    private lateinit var database: DatabaseReference
    private var userSessionProvider: UserSessionProvider? = null

    // 儲存格子視圖的參考，用於更新顯示狀態
    private val cellViews = mutableMapOf<Int, View>()
    private var currentScratchCard: ScratchCard? = null

    // 追蹤正在刮的格子 ID
    private val scratchingCells = mutableSetOf<Int>()

    // ✅ 新增：是否已有刮卡小視窗正在顯示（避免多指同時開多個）
    private var isScratchDialogShowing: Boolean = false

    // 🌟 解決多指點擊 BUG：新增是否正在處理網路請求的狀態
    private var isProcessingClick: Boolean = false

    // 新增：儲存漩渦View和動畫
    private val swirlViews = mutableMapOf<Int, SwirlView>()
    private val cellAnimators = mutableMapOf<Int, List<ObjectAnimator>>()
    private var remainingScratchTextView: TextView? = null
    private var relockTapCount = 0
    private var relockLastTapAt = 0L

    // ====== Offline / Auto-retry ======
    private val netHandler = Handler(Looper.getMainLooper())
    private var netRetryRunnable: Runnable? = null
    private var hasShownOfflineDialog = false

    // ====== ScratchCards listener (avoid multiple listeners) ======
    private var scratchCardsRef: DatabaseReference? = null
    private var scratchCardsListener: ValueEventListener? = null

    private val networkHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var networkLoopStarted = false

    // ✅ 新增：記錄目前的 Dialog 實體，用來做無縫接軌判斷
    private var currentScratchDialog: ScratchDialog? = null
    // ✅ 新增：本地樂觀更新防呆鎖，避免網路慢時玩家狂點同一個格子
    private val optimisticallyScratchedCells = mutableSetOf<Int>()

    companion object {
        private const val TAG = "ScratchCardPlayerFragment"
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is UserSessionProvider) {
            userSessionProvider = context
        } else {
            throw RuntimeException("$context must implement UserSessionProvider")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = FirebaseDatabase
            .getInstance("https://sca3-69342-default-rtdb.asia-southeast1.firebasedatabase.app")
            .reference
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_scratch_card, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scratchCardContainer = view.findViewById(R.id.scratch_card_container)
        noScratchCardText = view.findViewById(R.id.no_scratch_card_text)
        remainingScratchTextView = activity?.findViewById(R.id.remaining_scratches_text_view)

        remainingScratchTextView?.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - relockLastTapAt > 1200) {
                relockTapCount = 0
            }
            relockLastTapAt = now
            relockTapCount++

            if (relockTapCount >= 7) {
                relockTapCount = 0
                (activity as? MainActivity)?.relockFromPlayerGesture()
                activity?.let { ToastManager.show(it, "已重新啟用鎖定模式") }
            }
        }

        // ✅ 先啟動離線提示/自動重試（避免離線時出現空白）
        startNetworkHintLoop()

        // ✅ 載入刮刮卡（有網路會正常顯示格子；沒網路會顯示提示文字）
        loadUserScratchCards()
    }

    // 👇 1. 替換 setupCell：加入強制中斷舊視窗的邏輯
    private fun setupCell(cellView: View, cellNumber: Int, serialNumber: String, card: ScratchCard) {
        val numberConfig = card.numberConfigurations?.find { it.id == cellNumber }
        val isScratched = numberConfig?.scratched == true
        val number = numberConfig?.number

        updateCellDisplay(cellView, cellNumber, isScratched, number)

        cellView.setOnClickListener {
            // 🛑 本地樂觀更新防呆：避免網路慢時，玩家重複點擊已經刮開(但DB還沒回傳)的格子
            if (optimisticallyScratchedCells.contains(cellNumber)) {
                return@setOnClickListener
            }

            // ✅ 核心優化：若已有刮卡視窗在顯示中
            if (isScratchDialogShowing) {
                // 檢查舊視窗是否允許被瞬間中斷 (一般數字且已刮完)
                if (currentScratchDialog?.canBeInstantlyDismissed() == true) {
                    Log.d(TAG, "偵測到玩家快速點擊下一個刮格，強制關閉舊視窗並放行新點擊！")
                    currentScratchDialog?.dismiss()
                    currentScratchDialog = null
                    isScratchDialogShowing = false // 強制解除鎖定
                } else {
                    return@setOnClickListener
                }
            }

            if (isProcessingClick) return@setOnClickListener

            val refreshedConfig = currentScratchCard?.numberConfigurations?.find { it.id == cellNumber }
            val isAlreadyScratched = refreshedConfig?.scratched == true

            if (isAlreadyScratched) return@setOnClickListener

            if (!isNetworkAvailable()) {
                activity?.let { ToastManager.show(it, "偵測到網路異常，請檢查連線") }
                return@setOnClickListener
            }

            val isReallyConnected = (activity as? MainActivity)?.isFirebaseConnected ?: false
            if (!isReallyConnected) {
                activity?.let { ToastManager.show(it, "資料庫連線中斷，請確認網路狀態後再試") }
                return@setOnClickListener
            }

            if (refreshedConfig?.scratched != true && number != null) {
                val userKey = userSessionProvider?.getCurrentUserFirebaseKey() ?: return@setOnClickListener

                isProcessingClick = true
                val pingRef = database.child("users").child(userKey).child("ping")

                cellView.isEnabled = false
                var isAcknowledged = false
                var isTimedOut = false

                val timeoutHandler = Handler(Looper.getMainLooper())
                val timeoutRunnable = Runnable {
                    if (!isAcknowledged) {
                        isTimedOut = true
                        cellView.isEnabled = true
                        isProcessingClick = false
                        activity?.let { ToastManager.show(it, "網路不穩定，無法開啟刮卡，請稍後再試") }
                    }
                }

                timeoutHandler.postDelayed(timeoutRunnable, 1500)

                pingRef.setValue(ServerValue.TIMESTAMP).addOnCompleteListener { task ->
                    isAcknowledged = true
                    timeoutHandler.removeCallbacks(timeoutRunnable)

                    if (isTimedOut) return@addOnCompleteListener

                    cellView.isEnabled = true
                    isProcessingClick = false

                    if (task.isSuccessful) {
                        if (isScratchDialogShowing) return@addOnCompleteListener

                        isScratchDialogShowing = true
                        scratchingCells.add(cellNumber)
                        updateCellDisplay(cellView, cellNumber, false, number)

                        val isSecondToLast = isSecondToLastScratch()
                        val hasUnscatchedPrizes = hasUnscatchedPrizes()

                        (activity as? MainActivity)?.enableImmersiveMode()

                        val dialog = ScratchDialog(
                            requireContext(),
                            number,
                            isSpecialPrize(number),
                            isGrandPrize(number),
                            isSecondToLast,
                            hasUnscatchedPrizes,
                            onScratchStart = { writeTempScratch(serialNumber, cellNumber) },
                            onScratchComplete = { scratchCell(serialNumber, cellNumber, cellView) },
                            onTimeoutForceReveal = { scratchCell(serialNumber, cellNumber, cellView) }
                        )

                        // 🌟 記錄目前正在顯示的視窗實體
                        currentScratchDialog = dialog

                        dialog.setOnDismissListener {
                            isScratchDialogShowing = false
                            // 視窗關閉時清除實體紀錄
                            if (currentScratchDialog == dialog) {
                                currentScratchDialog = null
                            }

                            val hasStartedScratching = dialog.hasStartedScratching()
                            if (!hasStartedScratching) {
                                scratchingCells.remove(cellNumber)
                                updateCellDisplay(cellView, cellNumber, false, number)
                            }
                            (activity as? MainActivity)?.enableImmersiveMode()
                        }
                        dialog.show()
                    } else {
                        activity?.let { ToastManager.show(it, "網路異常，請重試") }
                    }
                }
            }
        }
    }

    // 👇 2. 替換 scratchCell：加入防呆鎖註記
    private fun scratchCell(serialNumber: String, cellNumber: Int, cellView: View) {
        val currentUserFirebaseKey = userSessionProvider?.getCurrentUserFirebaseKey() ?: return

        // 🌟 樂觀更新與防呆
        optimisticallyScratchedCells.add(cellNumber)
        val number = currentScratchCard?.numberConfigurations?.find { it.id == cellNumber }?.number
        scratchingCells.remove(cellNumber)
        updateCellDisplay(cellView, cellNumber, true, number)

        database.child("users")
            .child(currentUserFirebaseKey)
            .child("scratchCards")
            .child(serialNumber)
            .child("numberConfigurations")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for ((index, child) in snapshot.children.withIndex()) {
                        val id = child.child("id").getValue(Int::class.java)
                        if (id == cellNumber) {
                            val alreadyScratched = child.child("scratched").getValue(Boolean::class.java) ?: false
                            if (alreadyScratched) return

                            val cellRef = database.child("users")
                                .child(currentUserFirebaseKey)
                                .child("scratchCards")
                                .child(serialNumber)
                                .child("numberConfigurations")
                                .child(index.toString())

                            val updates = mapOf<String, Any>(
                                "scratched" to true,
                                "scratchedAt" to ServerValue.TIMESTAMP
                            )

                            cellRef.updateChildren(updates)
                                .addOnSuccessListener {
                                    updateRemainingScratchesDisplay()
                                    val sp = requireContext().getSharedPreferences("LocalPendingScratches_$currentUserFirebaseKey", Context.MODE_PRIVATE)
                                    val pendingSet = sp.getStringSet("pending_scratches", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                                    pendingSet.remove("$serialNumber:$cellNumber")
                                    sp.edit().putStringSet("pending_scratches", pendingSet).apply()
                                }
                                .addOnFailureListener {
                                    optimisticallyScratchedCells.remove(cellNumber) // 失敗則解除鎖定
                                    scratchingCells.remove(cellNumber)
                                    updateCellDisplay(cellView, cellNumber, false, null)
                                }
                            break
                        }
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    optimisticallyScratchedCells.remove(cellNumber)
                    scratchingCells.remove(cellNumber)
                    cellViews[cellNumber]?.let { updateCellDisplay(it, cellNumber, false, null) }
                }
            })
    }

    // 👇 3. 替換 displayNoScratchCardMessage：重置版面時清空防呆鎖
    private fun displayNoScratchCardMessage(message: String) {
        if (!canSafelyUpdateUi()) return

        scratchCardContainer.removeAllViews()
        noScratchCardText.text = message
        noScratchCardText.visibility = View.VISIBLE
        cellViews.clear()
        currentScratchCard = null
        scratchingCells.clear()
        optimisticallyScratchedCells.clear() // 🌟 清空防呆鎖

        remainingScratchTextView?.apply {
            text = ""
            visibility = View.GONE
        }

        cellAnimators.keys.toList().forEach { cellNumber ->
            stopCellAnimation(cellNumber)
        }
    }

    override fun onDetach() {
        super.onDetach()
        userSessionProvider = null
    }

    private fun canSafelyUpdateUi(): Boolean =
        isAdded && view != null &&
                viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    /**
     * 檢查網路連線狀態
     */
    private fun isNetworkAvailable(): Boolean {
        // 🌟 修正：安全取用 context
        val ctx = context ?: return false
        val connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            networkInfo != null && networkInfo.isConnected
        }
    }

    /**
     * 顯示網路連線錯誤對話框
     */
    private fun showNetworkErrorDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("無法連線")
            .setMessage("目前無法連線，請檢查網路連線後再試")
            .setPositiveButton("確定") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }

    private fun startNetworkHintLoop() {
        if (networkLoopStarted) return
        networkLoopStarted = true

        val runnable = object : Runnable {
            override fun run() {
                if (!isAdded) return

                val online = isNetworkAvailable()
                if (!online) {
                    // 離線：確保畫面有提示（避免空白以為BUG）
                    if (scratchCardContainer.childCount == 0) {
                        displayNoScratchCardMessage("目前未連線網路，請先連接 Wi-Fi / 行動網路後再使用。")
                    }
                    networkHandler.postDelayed(this, 1500)
                    return
                }

                // 已連線：嘗試載入刮刮卡（若已成功顯示，就不會一直重建）
                loadUserScratchCards()
                networkHandler.postDelayed(this, 3000)
            }
        }

        networkHandler.post(runnable)
    }

    private fun startNetworkAutoRetry() {
        if (netRetryRunnable != null) return

        netRetryRunnable = Runnable {
            if (!isAdded) return@Runnable

            if (isNetworkAvailable()) {
                // 網路回來：停止輪詢並重新載入
                stopNetworkAutoRetry()
                hasShownOfflineDialog = false
                activity?.let { ToastManager.show(it, "網路已連線，載入中...") }
                loadUserScratchCards()
            } else {
                // 還是沒網路：2 秒後再試
                netHandler.postDelayed(netRetryRunnable!!, 2000)
            }
        }

        netHandler.postDelayed(netRetryRunnable!!, 2000)
    }

    private fun stopNetworkAutoRetry() {
        netRetryRunnable?.let { netHandler.removeCallbacks(it) }
        netRetryRunnable = null
    }

    private fun removeScratchCardsWatcher() {
        scratchCardsListener?.let { l ->
            scratchCardsRef?.removeEventListener(l)
        }
        scratchCardsListener = null
        scratchCardsRef = null
    }



    private fun loadUserScratchCards() {
        val uid = userSessionProvider?.getCurrentUserFirebaseKey()
        if (uid.isNullOrBlank()) {
            displayNoScratchCardMessage("請先登入以查看刮刮卡。")
            return
        }

        // ✅ 無網路時：先顯示提示（避免玩家看到「空白」以為 BUG）
        if (!isNetworkAvailable()) {
            displayNoScratchCardMessage("目前未連線網路，請先連接 Wi-Fi / 行動網路後再使用。")
            return
        }

        // ✅ 防止重複掛監聽（避免多次 onDataChange 造成 UI 混亂）
        scratchCardsListener?.let { old ->
            scratchCardsRef?.removeEventListener(old)
        }

        scratchCardsRef = database.child("users").child(uid).child("scratchCards")

        scratchCardsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!canSafelyUpdateUi()) return

                // ✅ 若此刻沒網路（例如進來後斷線），直接提示
                if (!isNetworkAvailable()) {
                    displayNoScratchCardMessage("目前未連線網路，請先連接 Wi-Fi / 行動網路後再使用。")
                    return
                }

                val available = mutableListOf<Pair<String, ScratchCard>>()
                for (child in snapshot.children) {
                    val serialNumber = child.key ?: continue
                    val card = child.getValue(ScratchCard::class.java) ?: continue
                    if (card.inUsed == true && card.order != null) {
                        available.add(serialNumber to card)
                    }
                }

                val toShow = available.minByOrNull { it.second.order!! }
                if (toShow == null) {
                    displayNoScratchCardMessage("目前沒有可用的刮刮卡。")
                    return
                }

                val newSerial = toShow.first
                val newCard = toShow.second

                // ✅ 一定要補 serialNumber，不然比對/切卡會出錯
                newCard.serialNumber = newSerial

                val uiNotBuiltYet =
                    scratchCardContainer.childCount == 0 || cellViews.isEmpty()

                // ✅ 只要「第一次建立UI / UI還沒建立 / 換卡」→ 一律重建 UI（避免空白）
                if (uiNotBuiltYet || currentScratchCard?.serialNumber != newSerial) {
                    Log.d(TAG, "displayScratchCard(): uiNotBuiltYet=$uiNotBuiltYet, serial=$newSerial")
                    displayScratchCard(newSerial, newCard)
                } else {
                    // ✅ 同一張卡 → 只更新格子狀態（不卡頓、不閃爍）
                    currentScratchCard = newCard
                    updateExistingScratchCardUI(newCard)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                // 🌟 修正：確保 Fragment 存活才處理錯誤
                if (!isAdded) return

                Log.e(TAG, "載入刮刮卡資料失敗: ${error.message}", error.toException())

                if (!isNetworkAvailable()) {
                    displayNoScratchCardMessage("目前未連線網路，請先連接 Wi-Fi / 行動網路後再使用。")
                    return
                }

                if (canSafelyUpdateUi()) {
                    activity?.let { ToastManager.show(it, "載入刮刮卡資料失敗。") }
                    displayNoScratchCardMessage("載入刮刮卡失敗，請稍後再試。")
                }
            }
        }

        scratchCardsRef?.addValueEventListener(scratchCardsListener!!)
    }

    private fun updateExistingScratchCardUI(updatedCard: ScratchCard) {
        updatedCard.numberConfigurations?.forEach { config ->
            val cellView = cellViews[config.id] ?: return@forEach
            updateCellDisplay(
                cellView,
                config.id,
                config.scratched == true,
                config.number
            )
        }
    }

    private fun displayScratchCard(serialNumber: String, card: ScratchCard) {
        currentScratchCard = card.apply { this.serialNumber = serialNumber }

        val scratchesType = card.scratchesType ?: 10
        updateRemainingScratchesDisplay()

        try {
            scratchCardContainer.removeAllViews()
            noScratchCardText.visibility = View.GONE

            // 動態檢查布局資源是否存在
            val layoutResId = resources.getIdentifier(
                "scratch_card_$scratchesType",
                "layout",
                requireContext().packageName
            )

            if (layoutResId == 0) {
                // 找不到對應的布局文件
                displayNoScratchCardMessage("尚未建立 ${scratchesType} 刮版型的布局文件 (scratch_card_${scratchesType}.xml)")
                Log.w(TAG, "找不到布局資源: scratch_card_$scratchesType")
                return
            }

            val view = LayoutInflater.from(requireContext()).inflate(layoutResId, scratchCardContainer, false)
            scratchCardContainer.addView(view)

            // 設置刮刮卡
            setupScratchCard(view, serialNumber, card)

            Log.d(TAG, "成功載入${scratchesType}刮版型")

        } catch (e: Exception) {
            Log.e(TAG, "載入版型失敗: ${e.message}", e)
            activity?.let {
                ToastManager.show(it, "載入刮刮卡版型失敗。")
            }
            displayNoScratchCardMessage("載入刮刮卡版型時發生錯誤：${e.message}")
        }
    }

    // 新增通用的 setupScratchCard 方法
    private fun setupScratchCard(containerView: View, serialNumber: String, card: ScratchCard) {
        cellViews.clear()
        val totalCells = card.scratchesType ?: 10

        // 找到 GridLayout
        val gridLayout = containerView.findViewById<GridLayout>(R.id.gridLayout)
        if (gridLayout == null) {
            Log.e(TAG, "找不到 GridLayout")
            return
        }

        var cellNumber = 1

        // 遍歷 GridLayout 的每個子 View
        for (i in 0 until gridLayout.childCount) {
            if (cellNumber > totalCells) break

            val frameLayout = gridLayout.getChildAt(i) as? FrameLayout ?: continue

            // 在 FrameLayout 中找到 TextView (第一個子 View)
            val cellView = if (frameLayout.childCount > 0) {
                frameLayout.getChildAt(0)
            } else {
                continue
            }

            cellViews[cellNumber] = cellView
            setupCell(cellView, cellNumber, serialNumber, card)
            cellNumber++
        }
    }

    /**
     * 計算當前刮刮卡剩餘未刮開的格子數量
     */
    private fun getRemainingUnscratched(): Int {
        val card = currentScratchCard ?: return 0
        return card.numberConfigurations?.count { it.scratched == false } ?: 0
    }

    /**
     * 判斷是否為倒數第二刮（剩餘2個未刮時）
     */
    private fun isSecondToLastScratch(): Boolean {
        return getRemainingUnscratched() == 2
    }

    /**
     * 檢查剩餘未刮的格子中是否有特獎或大獎
     */
    private fun hasUnscatchedPrizes(): Boolean {
        val card = currentScratchCard ?: return false

        // 獲取所有未刮開的格子數字
        val unscatchedNumbers = card.numberConfigurations
            ?.filter { it.scratched == false }
            ?.mapNotNull { it.number }
            ?: emptyList()

        // 檢查這些數字中是否有特獎或大獎
        return unscatchedNumbers.any { number ->
            isSpecialPrize(number) || isGrandPrize(number)
        }
    }

    /**
     * 獲取刮刮卡統計資訊（用於除錯）
     */
    private fun getScratchCardStats(): Triple<Int, Int, Int> {
        val card = currentScratchCard
        if (card == null) {
            return Triple(0, 0, 0)
        }

        val totalCells = card.scratchesType ?: 0
        val scratchedCount = card.numberConfigurations?.count { it.scratched == true } ?: 0
        val remainingCount = card.numberConfigurations?.count { it.scratched == false } ?: 0

        return Triple(totalCells, scratchedCount, remainingCount)
    }

    // ✅ 寫入 scratchCardsTemp 的防弊暫存紀錄
    private fun writeTempScratch(serialNumber: String, cellNumber: Int) {
        val userKey = userSessionProvider?.getCurrentUserFirebaseKey() ?: return

        // 🌟 終極防線：寫入手機本地硬碟 (SharedPreferences)
        val sp = requireContext().getSharedPreferences("LocalPendingScratches_$userKey", Context.MODE_PRIVATE)
        val pendingSet = sp.getStringSet("pending_scratches", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        pendingSet.add("$serialNumber:$cellNumber")
        sp.edit().putStringSet("pending_scratches", pendingSet).apply()
        Log.d(TAG, "🔒 [本地硬碟防弊] 已將卡 $serialNumber 格子 $cellNumber 寫入本地備用紀錄")

        try {
            val db = FirebaseDatabase.getInstance().reference
            val tempRef = db.child("users").child(userKey).child("scratchCardsTemp").push()

            val data = mapOf(
                "cardId" to serialNumber,
                "cellNumber" to cellNumber,
                "createdAt" to System.currentTimeMillis()
            )

            tempRef.setValue(data)
                .addOnSuccessListener {
                    Log.d(TAG, "✅ 已寫入 scratchCardsTemp: cardId=$serialNumber, cellNumber=$cellNumber")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ 寫入 scratchCardsTemp 失敗: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "❌ writeTempScratch() 例外錯誤: ${e.message}")
        }
    }

    fun updateCellDisplay(cellView: View, cellNumber: Int, isScratched: Boolean, number: Int?) {
        val isScratching = scratchingCells.contains(cellNumber)

        if (isScratched && number != null) {
            // 已刮開
            scratchingCells.remove(cellNumber)
            stopCellAnimation(cellNumber)

            val isSpecial = isSpecialPrize(number)
            val isGrand = isGrandPrize(number)

            // ✅ 填滿底色判斷
            val fillColorRes = when {
                isSpecial -> R.color.scratch_card_gold      // 特獎：黃
                isGrand -> R.color.scratch_card_green       // 大獎：綠
                else -> R.color.scratch_card_white
            }

            // 框線顏色
            val strokeColorRes = when {
                isSpecial -> R.color.scratch_card_gold
                isGrand -> R.color.scratch_card_green
                else -> R.color.scratch_card_light_gray
            }

            // 框線粗細
            val strokeWidth = when {
                isSpecial || isGrand -> 4
                else -> 2
            }

            // 背景 drawable（整格填滿）
            val drawable = ContextCompat
                .getDrawable(requireContext(), R.drawable.circle_cell_normal_background)
                ?.mutate()

            if (drawable is android.graphics.drawable.GradientDrawable) {
                drawable.setColor(ContextCompat.getColor(requireContext(), fillColorRes))
                drawable.setStroke(strokeWidth, ContextCompat.getColor(requireContext(), strokeColorRes))
            }

            cellView.background = drawable

            // ✅ 特獎 / 大獎 → 白色字，其餘黑色
            val textColorRes = if (isSpecial || isGrand) {
                android.R.color.white
            } else {
                R.color.black
            }

            if (cellView is TextView) {
                cellView.text = number.toString()
                cellView.setTextColor(ContextCompat.getColor(requireContext(), textColorRes))
            } else {
                addNumberToCell(cellView, number, textColorRes)
            }

        } else if (isScratching && !isScratched) {
            // 正在刮
            startSwirlAnimation(cellView, cellNumber)

            if (cellView is TextView) {
                cellView.text = ""
            } else {
                removeNumberFromCell(cellView)
            }

        } else {
            // 尚未刮開
            stopCellAnimation(cellNumber)

            val drawable = ContextCompat
                .getDrawable(requireContext(), R.drawable.circle_cell_background_black)
                ?.mutate()

            if (drawable is android.graphics.drawable.GradientDrawable) {
                drawable.setColor(ContextCompat.getColor(requireContext(), R.color.scratch_card_dark_gray))
                drawable.setStroke(2, ContextCompat.getColor(requireContext(), R.color.scratch_card_light_gray))
            }

            cellView.background = drawable

            if (cellView is TextView) {
                cellView.text = ""
            } else {
                removeNumberFromCell(cellView)
            }
        }
    }

    /**
     * 啟動漩渦流動動畫效果
     */
    private fun startSwirlAnimation(cellView: View, cellNumber: Int) {
        // 先停止之前的動畫
        stopCellAnimation(cellNumber)

        // 確保 cellView 的父容器是 FrameLayout
        val parent = cellView.parent as? FrameLayout ?: return

        // 創建漩渦View
        val swirlView = SwirlView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 將漩渦View添加到父容器的最底層（在cellView之下）
        parent.addView(swirlView, 0)
        swirlViews[cellNumber] = swirlView

        // 隱藏原本的cellView背景
        cellView.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // 添加脈動效果 - 只作用於漩渦View，不影響格子
        val scaleXAnimator = ObjectAnimator.ofFloat(swirlView, "scaleX", 1.0f, 1.08f, 1.0f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }

        val scaleYAnimator = ObjectAnimator.ofFloat(swirlView, "scaleY", 1.0f, 1.08f, 1.0f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }

        // 啟動動畫
        scaleXAnimator.start()
        scaleYAnimator.start()

        // 保存動畫引用
        cellAnimators[cellNumber] = listOf(scaleXAnimator, scaleYAnimator)
    }

    /**
     * 停止指定格子的動畫
     */
    private fun stopCellAnimation(cellNumber: Int) {
        // 停止並移除動畫
        cellAnimators[cellNumber]?.forEach { animator ->
            animator.cancel()
            animator.removeAllListeners()
        }
        cellAnimators.remove(cellNumber)

        // 移除漩渦View
        swirlViews[cellNumber]?.let { swirlView ->
            (swirlView.parent as? ViewGroup)?.removeView(swirlView)
        }
        swirlViews.remove(cellNumber)

        // 不需要重置父容器的縮放了，因為現在只有 swirlView 在跳動
    }

    private fun addNumberToCell(cellView: View, number: Int, textColorRes: Int = R.color.scratch_card_light_gray) {
        if (cellView.parent is FrameLayout) {
            val frameLayout = cellView.parent as FrameLayout
            var numberTextView = frameLayout.findViewWithTag<TextView>("number_text")

            if (numberTextView == null) {
                numberTextView = TextView(requireContext()).apply {
                    tag = "number_text"
                    text = number.toString()
                    textSize = 42f
                    setTextColor(ContextCompat.getColor(requireContext(), textColorRes))
                    gravity = android.view.Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        android.view.Gravity.CENTER
                    )
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                frameLayout.addView(numberTextView)
            } else {
                numberTextView.text = number.toString()
                numberTextView.setTextColor(ContextCompat.getColor(requireContext(), textColorRes))
                numberTextView.visibility = View.VISIBLE
            }
        }
    }

    private fun removeNumberFromCell(cellView: View) {
        if (cellView.parent is FrameLayout) {
            val frameLayout = cellView.parent as FrameLayout
            val numberTextView = frameLayout.findViewWithTag<TextView>("number_text")
            numberTextView?.visibility = View.GONE
        }
    }

    /**
     * 更新左側下方顯示的「剩餘刮數 / 總刮數（版型）」- 玩家頁面
     */
    private fun updateRemainingScratchesDisplay() {
        val view = remainingScratchTextView ?: return

        val card = currentScratchCard ?: run {
            // 沒有正在使用的刮卡就隱藏
            view.text = ""
            view.visibility = View.GONE
            return
        }

        val total = card.scratchesType ?: 0
        val remaining = card.numberConfigurations?.count { it.scratched == false } ?: 0

        view.text = "$remaining/$total"
        view.visibility = View.VISIBLE
    }

    private fun isSpecialPrize(number: Int): Boolean {
        val specialPrizeStr = currentScratchCard?.specialPrize
        return if (specialPrizeStr.isNullOrEmpty()) {
            false
        } else {
            specialPrizeStr.split(",").map { it.trim().toIntOrNull() }.contains(number)
        }
    }

    private fun isGrandPrize(number: Int): Boolean {
        val grandPrizeStr = currentScratchCard?.grandPrize
        return if (grandPrizeStr.isNullOrEmpty()) {
            false
        } else {
            grandPrizeStr.split(",").map { it.trim().toIntOrNull() }.contains(number)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // ✅ 移除 Firebase 監聽
        scratchCardsListener?.let { listener ->
            scratchCardsRef?.removeEventListener(listener)
        }
        scratchCardsListener = null
        scratchCardsRef = null

        // ✅ 停止網路 loop
        networkHandler.removeCallbacksAndMessages(null)
        networkLoopStarted = false

        // ✅ 清理所有動畫
        cellAnimators.keys.toList().forEach { cellNumber ->
            stopCellAnimation(cellNumber)
        }
    }
}