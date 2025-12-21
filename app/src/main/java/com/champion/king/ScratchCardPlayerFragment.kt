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

    // 新增：儲存漩渦View和動畫
    private val swirlViews = mutableMapOf<Int, SwirlView>()
    private val cellAnimators = mutableMapOf<Int, List<ObjectAnimator>>()
    private var remainingScratchTextView: TextView? = null
    private var relockTapCount = 0
    private var relockLastTapAt = 0L



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
                // 超過 1.2 秒就重算一次連點
                relockTapCount = 0
            }
            relockLastTapAt = now
            relockTapCount++

            if (relockTapCount >= 7) {
                relockTapCount = 0
                (activity as? MainActivity)?.relockFromPlayerGesture()
                activity?.let {
                    ToastManager.show(it, "已重新啟用鎖定模式")
                }
            }
        }
        loadUserScratchCards()
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
        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6.0 及以上版本
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } else {
            // Android 6.0 以下版本
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

    private fun loadUserScratchCards() {
        val currentUserFirebaseKey = userSessionProvider?.getCurrentUserFirebaseKey()
        if (currentUserFirebaseKey == null) {
            displayNoScratchCardMessage("請先登入以查看刮刮卡。")
            return
        }

        // 監聽使用者的刮刮卡資料變化
        database.child("users")
            .child(currentUserFirebaseKey)
            .child("scratchCards")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!canSafelyUpdateUi()) return

                    val available = mutableListOf<Pair<String, ScratchCard>>()
                    for (child in snapshot.children) {
                        val serialNumber = child.key ?: continue
                        val card = child.getValue(ScratchCard::class.java)
                        if (card != null && card.inUsed == true && card.order != null) {
                            available.add(serialNumber to card)
                        }
                    }

                    val toShow = available.minByOrNull { it.second.order!! }
                    if (toShow != null) {
                        val newSerial = toShow.first
                        val newCard = toShow.second

                        // 只在序號改變時重建UI
                        if (currentScratchCard?.serialNumber != newSerial) {
                            Log.d(TAG, "切換刮刮卡序號: $newSerial")
                            displayScratchCard(newSerial, newCard)
                        } else {
                            // 同一張卡，只更新格子狀態
                            currentScratchCard = newCard
                            updateExistingScratchCardUI(newCard)
                        }
                    } else {
                        displayNoScratchCardMessage("目前沒有可用的刮刮卡。")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "載入刮刮卡資料失敗: ${error.message}", error.toException())
                    if (canSafelyUpdateUi()) {
                        activity?.let {
                            ToastManager.show(it, "載入刮刮卡資料失敗。")
                        }
                        displayNoScratchCardMessage("載入刮刮卡失敗，請稍後再試。")
                    }
                }
            })
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

    private fun setupCell(cellView: View, cellNumber: Int, serialNumber: String, card: ScratchCard) {
        val numberConfig = card.numberConfigurations?.find { it.id == cellNumber }
        val isScratched = numberConfig?.scratched == true
        val number = numberConfig?.number

        updateCellDisplay(cellView, cellNumber, isScratched, number)

        // 設定點擊事件
        cellView.setOnClickListener {

            // ✅ 若已有刮卡視窗在顯示中，直接忽略這次點擊
            if (isScratchDialogShowing) {
                Log.d(TAG, "已有刮卡視窗顯示中，忽略格子 $cellNumber 的點擊")
                return@setOnClickListener
            }

            // ✅ 每次點擊都重新檢查最新狀態，避免使用舊的 numberConfig
            val refreshedConfig = currentScratchCard?.numberConfigurations?.find { it.id == cellNumber }
            val isAlreadyScratched = refreshedConfig?.scratched == true

            // ✅ 若已刮開，完全忽略點擊
            if (isAlreadyScratched) {
                Log.d(TAG, "⚠️ 格子 $cellNumber 已刮開，忽略點擊。")
                return@setOnClickListener
            }

            // 檢查網路連線
            if (!isNetworkAvailable()) {
                Log.w(TAG, "【網路檢查】無網路連線，拒絕刮卡")
                showNetworkErrorDialog()
                return@setOnClickListener
            }

            Log.d(TAG, "【網路檢查】網路連線正常，允許刮卡")

            // ✅ 只允許「未刮開」且 number 有效的格子進行互動
            if (refreshedConfig?.scratched != true && number != null) {

                // ✅ 一旦決定要開小視窗，就先鎖住（防止多指連續觸發）
                isScratchDialogShowing = true

                scratchingCells.add(cellNumber)
                updateCellDisplay(cellView, cellNumber, false, number)

                val isSecondToLast = isSecondToLastScratch()
                val hasUnscatchedPrizes = hasUnscatchedPrizes()
                val (totalCells, scratchedCount, remainingCount) = getScratchCardStats()
                Log.d(TAG, "點擊格子 $cellNumber: 總格數=$totalCells, 已刮=$scratchedCount, 剩餘=$remainingCount")
                Log.d(TAG, "是否倒數第二刮=$isSecondToLast, 剩餘是否有獎項=$hasUnscatchedPrizes")

                (activity as? MainActivity)?.enableImmersiveMode()

                // 顯示刮卡彈窗
                val dialog = ScratchDialog(
                    requireContext(),
                    number,
                    isSpecialPrize(number),
                    isGrandPrize(number),
                    isSecondToLast,
                    hasUnscatchedPrizes,
                    onScratchStart = {
                        Log.d(TAG, "【防弊機制觸發】格子 $cellNumber 開始刮卡")
                        writeTempScratch(serialNumber, cellNumber)
                    },
                    onScratchComplete = {
                        Log.d(TAG, "格子 $cellNumber 刮卡完成，標記 scratched = true")
                        scratchCell(serialNumber, cellNumber, cellView)
                    }
                )

                dialog.setOnDismissListener {
                    // ✅ 小視窗關閉後解除鎖定，允許下一次點擊
                    isScratchDialogShowing = false

                    val hasStartedScratching = dialog.hasStartedScratching()
                    if (!hasStartedScratching) {
                        scratchingCells.remove(cellNumber)
                        updateCellDisplay(cellView, cellNumber, false, number)
                    }
                    (activity as? MainActivity)?.enableImmersiveMode()
                }
                dialog.show()
            }
        }
    }

    // ✅ 寫入 scratchCardsTemp 的防弊暫存紀錄
    private fun writeTempScratch(serialNumber: String, cellNumber: Int) {
        val userKey = userSessionProvider?.getCurrentUserFirebaseKey() ?: return

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

    private fun updateCellDisplay(cellView: View, cellNumber: Int, isScratched: Boolean, number: Int?) {
        // 檢查是否正在刮這個格子
        val isScratching = scratchingCells.contains(cellNumber)

        if (isScratched && number != null) {
            // 已刮開：移除正在刮的標記並停止動畫
            scratchingCells.remove(cellNumber)
            stopCellAnimation(cellNumber)

            // 已刮開的背景：統一為白色
            cellView.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.scratch_card_white))

            // 判斷是否為特獎或大獎，設定對應的框
            val strokeColor = when {
                isSpecialPrize(number) -> R.color.scratch_card_gold
                isGrandPrize(number) -> R.color.scratch_card_green
                else -> R.color.scratch_card_light_gray
            }

            // 特獎和大獎用較粗的框，一般數字用細框
            val strokeWidth = when {
                isSpecialPrize(number) || isGrandPrize(number) -> 4
                else -> 2
            }

            // 創建帶有圓框的 Drawable
            val drawable = ContextCompat.getDrawable(requireContext(), R.drawable.circle_cell_normal_background)?.mutate()
            if (drawable is android.graphics.drawable.GradientDrawable) {
                drawable.setColor(ContextCompat.getColor(requireContext(), R.color.scratch_card_white))
                drawable.setStroke(strokeWidth, ContextCompat.getColor(requireContext(), strokeColor))
            }
            cellView.background = drawable

            // 數字顏色：統一為黑色
            val textColor = R.color.black

            if (cellView is TextView) {
                cellView.text = number.toString()
                cellView.setTextColor(ContextCompat.getColor(requireContext(), textColor))
            } else {
                addNumberToCell(cellView, number, textColor)
            }
        } else if (isScratching && !isScratched) {
            // 正在刮但還未刮開：顯示漩渦流動效果
            startSwirlAnimation(cellView, cellNumber)

            if (cellView is TextView) {
                cellView.text = ""
            } else {
                removeNumberFromCell(cellView)
            }
        } else {
            // 未刮開：黑色蓋板，灰色細圓框
            stopCellAnimation(cellNumber)

            val drawable = ContextCompat.getDrawable(requireContext(), R.drawable.circle_cell_background_black)?.mutate()
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

    private fun scratchCell(serialNumber: String, cellNumber: Int, cellView: View) {
        val currentUserFirebaseKey = userSessionProvider?.getCurrentUserFirebaseKey() ?: return

        // 由於資料庫使用 scratched 欄位名稱，但模型類別使用 isScratched，
        // 這裡直接更新資料庫中對應格子的 scratched 欄位
        database.child("users")
            .child(currentUserFirebaseKey)
            .child("scratchCards")
            .child(serialNumber)
            .child("numberConfigurations")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    // 遍歷 numberConfigurations 數組找到對應 id 的項目
                    for ((index, child) in snapshot.children.withIndex()) {
                        val id = child.child("id").getValue(Int::class.java)
                        if (id == cellNumber) {
                            // 更新該項目的 scratched 狀態
                            database.child("users")
                                .child(currentUserFirebaseKey)
                                .child("scratchCards")
                                .child(serialNumber)
                                .child("numberConfigurations")
                                .child(index.toString())
                                .child("scratched")
                                .setValue(true)
                                .addOnSuccessListener {
                                    updateRemainingScratchesDisplay()
                                    Log.d(TAG, "格子 $cellNumber 刮開成功")
                                    // ValueEventListener 會自動觸發UI更新
                                    // 此時會調用 updateCellDisplay，顯示白色背景和數字
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "刮開格子 $cellNumber 失敗: ${e.message}", e)
                                    activity?.let {
                                        ToastManager.show(it, "刮卡操作失敗")
                                    }
                                    // 失敗時也要移除正在刮的標記
                                    scratchingCells.remove(cellNumber)
                                    updateCellDisplay(cellView, cellNumber, false, null)
                                }
                            break
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "讀取格子配置失敗: ${error.message}", error.toException())
                    activity?.let {
                        ToastManager.show(it, "刮卡操作失敗")
                    }
                    // 失敗時也要移除正在刮的標記
                    scratchingCells.remove(cellNumber)
                    cellViews[cellNumber]?.let { updateCellDisplay(it, cellNumber, false, null) }
                }
            })
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

    private fun displayNoScratchCardMessage(message: String) {
        if (!canSafelyUpdateUi()) return

        scratchCardContainer.removeAllViews()
        noScratchCardText.text = message
        noScratchCardText.visibility = View.VISIBLE
        cellViews.clear()
        currentScratchCard = null
        scratchingCells.clear()

        // 額外：沒有刮卡時隱藏剩餘刮數顯示
        remainingScratchTextView?.apply {
            text = ""
            visibility = View.GONE
        }

        // 清理所有動畫
        cellAnimators.keys.toList().forEach { cellNumber ->
            stopCellAnimation(cellNumber)
        }
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
        // 清理所有動畫，防止記憶體洩漏
        cellAnimators.keys.toList().forEach { cellNumber ->
            stopCellAnimation(cellNumber)
        }
    }
}