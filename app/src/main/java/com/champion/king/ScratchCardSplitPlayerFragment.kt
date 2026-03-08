package com.champion.king

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.champion.king.model.ScratchCard
import com.champion.king.util.ToastManager
import com.google.firebase.database.*
import android.animation.ObjectAnimator
import android.animation.ValueAnimator

class ScratchCardSplitPlayerFragment : Fragment() {

    private lateinit var mainContentContainer: FrameLayout
    private lateinit var noCardTextView: TextView // 🌟 新增這行用來顯示無卡片或斷線的提示
    private lateinit var database: DatabaseReference
    private var userSessionProvider: UserSessionProvider? = null

    // 監聽器
    private var scratchCardsRef: DatabaseReference? = null
    private var scratchCardsListener: ValueEventListener? = null

    // 記錄目前的母卡
    private var currentMasterCard: ScratchCard? = null

    // ====== 紀錄格子視圖與動畫狀態 (使用 "BoardID_CellID" 作為 Key，例如 "A_1") ======
    private val cellViews = mutableMapOf<String, View>()
    private val scratchingCells = mutableSetOf<String>()
    private var isScratchDialogShowing: Boolean = false  // 防止多指同時開啟多個刮卡視窗

    // 🌟 新增：儲存漩渦View和動畫 (使用 String 型別的 cellKey，例如 "A_1")
    private val swirlViews = mutableMapOf<String, View>()
    private val cellAnimators = mutableMapOf<String, List<ObjectAnimator>>()

    // 🌟 修改：用來記錄「夾X送X」連點次數的變數 (改為重新鎖定)
    private var relockTapCount = 0
    private var lastRelockTapAt = 0L

    // 🌟 新增：網路自動重試輪詢
    private val networkHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var networkLoopStarted = false

    companion object {
        private const val TAG = "ScratchCardSplitPlayer"
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
        database = FirebaseDatabase.getInstance("https://sca3-69342-default-rtdb.asia-southeast1.firebasedatabase.app").reference
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootLayout = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 🌟 建立提示文字元件
        noCardTextView = TextView(requireContext()).apply {
            text = "載入中..."
            setTextColor(android.graphics.Color.WHITE)
            textSize = 32f
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            )
            visibility = View.VISIBLE
        }

        rootLayout.addView(noCardTextView)
        return rootLayout
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mainContentContainer = view as FrameLayout

        startNetworkHintLoop() // 🌟 啟動自動恢復機制
        loadSplitScratchCard()
    }

    private fun loadSplitScratchCard() {
        val uid = userSessionProvider?.getCurrentUserFirebaseKey() ?: return

        if (!isNetworkAvailable()) {
            displayNoCardMessage("目前未連線網路，請先連接 Wi-Fi / 行動網路後再使用。")
            return
        }

        scratchCardsRef = database.child("users").child(uid).child("scratchCards")
        scratchCardsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return

                if (!isNetworkAvailable()) {
                    displayNoCardMessage("目前未連線網路，請先連接 Wi-Fi / 行動網路後再使用。")
                    return
                }

                var targetCard: ScratchCard? = null
                var targetSerial = ""

                for (child in snapshot.children) {
                    val card = child.getValue(ScratchCard::class.java)
                    if (card != null && card.inUsed == true) {
                        targetCard = card
                        targetSerial = child.key ?: ""
                        break
                    }
                }

                if (targetCard == null || targetCard.splitMode.isNullOrEmpty()) {
                    Log.w(TAG, "找不到使用中的分割版面母卡")
                    // 🌟 呼叫完美防護 Function
                    displayNoCardMessage("目前沒有可用的分割版面刮刮卡")
                    return
                }

                // 成功載入，隱藏提示文字
                noCardTextView.visibility = View.GONE
                targetCard.serialNumber = targetSerial

                val needRebuild = currentMasterCard?.serialNumber != targetSerial || mainContentContainer.childCount <= 1

                currentMasterCard = targetCard

                if (needRebuild) {
                    Log.d(TAG, "重新建立分割版面 (needRebuild = true)")
                    buildSplitLayout(targetCard)
                } else {
                    Log.d(TAG, "更新分割版面格子狀態 (needRebuild = false)")
                    updateSplitBoards(targetCard)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                // 🌟 修正：如果 Fragment 已經被拔除 (例如正在登出)，直接終止，不要去更新 UI
                if (!isAdded) return

                Log.e(TAG, "載入失敗: ${error.message}")
                if (!isNetworkAvailable()) {
                    displayNoCardMessage("目前未連線網路，請先連接 Wi-Fi / 行動網路後再使用。")
                } else {
                    displayNoCardMessage("載入失敗，請稍後再試")
                }
            }
        }
        scratchCardsRef?.addValueEventListener(scratchCardsListener!!)
    }

    // 🌟 新增：這就是負責把刮開後的數字和顏色畫出來的關鍵 Function！
    private fun updateSplitBoards(masterCard: ScratchCard) {
        val boardsMap = masterCard.boards ?: return

        for ((boardId, board) in boardsMap) {
            board.numberConfigurations?.forEach { config ->
                val cellKey = "${boardId}_${config.id}"
                val cellView = cellViews[cellKey] ?: return@forEach

                // 呼叫更新畫面的方法
                updateBoardCellDisplay(cellView, cellKey, config.scratched == true, config.number, board)
            }
        }
    }

    private fun buildSplitLayout(masterCard: ScratchCard) {
        mainContentContainer.removeAllViews()
        cellViews.clear()
        scratchingCells.clear()

        val splitMode = masterCard.splitMode ?: return // 預期拿到 "20x4"
        val layoutName = "scratch_card_split_${splitMode.replace("x", "_x")}"
        val layoutResId = resources.getIdentifier(layoutName, "layout", requireContext().packageName)

        if (layoutResId == 0) {
            Log.e(TAG, "找不到外殼版型: $layoutName.xml")
            return
        }

        try {
            // 1. 載入田字型外殼
            val splitView = LayoutInflater.from(requireContext()).inflate(layoutResId, mainContentContainer, false)
            mainContentContainer.addView(splitView)

            // 🌟 建立這 4 個畫框的對照表
            val panels = mapOf(
                "A" to splitView.findViewById<FrameLayout>(R.id.panelA),
                "B" to splitView.findViewById<FrameLayout>(R.id.panelB),
                "C" to splitView.findViewById<FrameLayout>(R.id.panelC),
                "D" to splitView.findViewById<FrameLayout>(R.id.panelD)
            )

            val boardsMap = masterCard.boards ?: emptyMap()

            // 🌟 遍歷 4 個「畫框」
            for ((panelId, panel) in panels) {
                if (panel == null) continue

                val board = boardsMap[panelId]

                // 無論有沒有資料，先把預設的白色字體清空！
                panel.removeAllViews()

                if (board == null) {
                    // 🚨 如果資料庫缺少這個版的資料，直接在畫面上顯示紅色警告！
                    val errorText = TextView(requireContext()).apply {
                        text = "$panelId 區\n(資料庫無資料)"
                        setTextColor(android.graphics.Color.RED)
                        textSize = 24f
                        gravity = android.view.Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    }
                    panel.addView(errorText)
                    Log.w(TAG, "Firebase 缺少 $panelId 區的資料！")
                    continue
                }

                // 🌟 關鍵修正：改為根據 splitMode 動態尋找「專屬的子版 XML」
                // 例如 splitMode 是 "20x4"，就會去尋找 "scratch_card_sub_20_x4"
                val splitModeSuffix = masterCard.splitMode?.replace("x", "_x") ?: "20_x4"
                val subLayoutName = "scratch_card_sub_$splitModeSuffix"
                val cardLayoutResId = resources.getIdentifier(subLayoutName, "layout", requireContext().packageName)

                if (cardLayoutResId != 0) {
                    val cardView = LayoutInflater.from(requireContext()).inflate(cardLayoutResId, panel, false)
                    panel.addView(cardView)

                    // 呼叫綁定格子與點擊事件的方法
                    setupBoard(cardView, masterCard.serialNumber ?: "", board)
                } else {
                    // 如果還沒建好專屬子版，印出警告
                    Log.w(TAG, "找不到專屬子版佈局: $subLayoutName.xml")

                    val errorText = TextView(requireContext()).apply {
                        text = "子版待開發\n($subLayoutName)"
                        setTextColor(android.graphics.Color.YELLOW)
                        gravity = android.view.Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    }
                    panel.addView(errorText)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "載入分割版型失敗: ${e.message}", e)
        }
    }

    // 針對單一子版（A, B, C 或 D）進行綁定
    private fun setupBoard(
        containerView: View,
        serialNumber: String, board: com.champion.king.model.Board) {

        populateBoardHeader(containerView, board)

        val gridLayout = containerView.findViewById<android.view.ViewGroup>(R.id.gridLayout)

        if (gridLayout == null) {
            Log.e(TAG, "在 Board ${board.id} 中找不到 GridLayout")
            return
        }

        val totalCells = board.numberConfigurations?.size ?: 20
        var cellNumber = 1

        for (i in 0 until gridLayout.childCount) {
            if (cellNumber > totalCells) break

            val frameLayout = gridLayout.getChildAt(i) as? FrameLayout ?: continue
            val cellView = if (frameLayout.childCount > 0) frameLayout.getChildAt(0) else continue

            val currentCellNumber = cellNumber
            val cellKey = "${board.id}_$currentCellNumber"

            cellViews[cellKey] = cellView
            val config = board.numberConfigurations?.find { it.id == currentCellNumber }

            updateBoardCellDisplay(cellView, cellKey, config?.scratched == true, config?.number, board)

            frameLayout.setOnClickListener {
                // 🛑 防呆 1：如果已經有刮卡視窗在顯示中，直接忽略
                if (isScratchDialogShowing) return@setOnClickListener

                // 🛑 防呆 2：【核心修改】確保同一時間只有「一個格子」能處於處理/漩渦狀態！
                if (scratchingCells.isNotEmpty()) {
                    Log.d(TAG, "已經有格子正在處理中，防呆攔截多指連點！")
                    return@setOnClickListener
                }

                // 重新取得最新狀態，確認還沒被刮開
                val refreshedConfig = currentMasterCard?.boards?.get(board.id)?.numberConfigurations?.find { it.id == currentCellNumber }
                if (refreshedConfig?.scratched == true) return@setOnClickListener

                if (!isNetworkAvailable()) {
                    activity?.let { ToastManager.show(it, "偵測到網路異常，請檢查連線") }
                    return@setOnClickListener
                }

                val isReallyConnected = (activity as? MainActivity)?.isFirebaseConnected ?: false
                if (!isReallyConnected) {
                    activity?.let { ToastManager.show(it, "資料庫連線中斷，請確認網路狀態") }
                    return@setOnClickListener
                }

                val number = refreshedConfig?.number
                if (number != null) {
                    val userKey = userSessionProvider?.getCurrentUserFirebaseKey() ?: return@setOnClickListener

                    // 💡 體驗升級：一按下去立刻加入 scratchingCells，不僅完成防呆鎖定，也馬上秀出黃色漩渦！
                    scratchingCells.add(cellKey)
                    updateBoardCellDisplay(cellView, cellKey, false, number, board)

                    frameLayout.isEnabled = false
                    var isAcknowledged = false
                    var isTimedOut = false

                    val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
                    val timeoutRunnable = Runnable {
                        if (!isAcknowledged) {
                            isTimedOut = true
                            frameLayout.isEnabled = true

                            // ❌ 逾時沒回應：移除漩渦，恢復黑底
                            scratchingCells.remove(cellKey)
                            updateBoardCellDisplay(cellView, cellKey, false, number, board)

                            activity?.let { ToastManager.show(it, "網路不穩定，無法開啟刮卡") }
                        }
                    }

                    timeoutHandler.postDelayed(timeoutRunnable, 1500)

                    database.child("users").child(userKey).child("ping")
                        .setValue(ServerValue.TIMESTAMP).addOnCompleteListener { task ->
                            isAcknowledged = true
                            timeoutHandler.removeCallbacks(timeoutRunnable)

                            if (isTimedOut) return@addOnCompleteListener
                            frameLayout.isEnabled = true

                            if (task.isSuccessful) {
                                // 🌟 敲門成功！正式呼叫刮卡小視窗
                                isScratchDialogShowing = true

                                val isSecondToLast = isSecondToLastScratch(board)
                                val hasUnscatchedPrizes = hasUnscatchedPrizes(board)
                                (activity as? MainActivity)?.enableImmersiveMode()

                                val dialog = ScratchDialog(
                                    requireContext(),
                                    number,
                                    isSpecialPrize(number, board),
                                    isGrandPrize(number, board),
                                    isSecondToLast,
                                    hasUnscatchedPrizes,
                                    onScratchStart = {
                                        Log.d(TAG, "開始刮卡：$cellKey")
                                        writeTempScratch(serialNumber, board.id, currentCellNumber)
                                    },
                                    onScratchComplete = {
                                        Log.d(TAG, "刮卡完成：$cellKey")
                                        scratchCell(serialNumber, board.id, currentCellNumber, cellKey, cellView)
                                    },
                                    onTimeoutForceReveal = {
                                        Log.d(TAG, "逾時強制刮開：$cellKey")
                                        scratchCell(serialNumber, board.id, currentCellNumber, cellKey, cellView)
                                    }
                                )

                                dialog.setOnDismissListener {
                                    isScratchDialogShowing = false
                                    val hasStartedScratching = dialog.hasStartedScratching()
                                    // 若玩家開啟視窗後沒刮就關閉：移除漩渦，恢復黑底
                                    if (!hasStartedScratching) {
                                        scratchingCells.remove(cellKey)
                                        updateBoardCellDisplay(cellView, cellKey, false, number, board)
                                    }
                                    (activity as? MainActivity)?.enableImmersiveMode()
                                }
                                dialog.show()

                            } else {
                                // ❌ 敲門失敗：移除漩渦，恢復黑底
                                scratchingCells.remove(cellKey)
                                updateBoardCellDisplay(cellView, cellKey, false, number, board)
                                activity?.let { ToastManager.show(it, "網路異常，請重試") }
                            }
                        }
                }
            }
            cellNumber++
        }
    }

    // ====== 渲染格子狀態 ======
    private fun updateBoardCellDisplay(
        cellView: View, cellKey: String,
        isScratched: Boolean, number: Int?, board: com.champion.king.model.Board) {
        val isScratching = scratchingCells.contains(cellKey)

        val specialPrizeList = board.specialPrize?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        val grandPrizeList = board.grandPrize?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()

        val isSpecial = number != null && specialPrizeList.contains(number)
        val isGrand = number != null && grandPrizeList.contains(number)

        if (isScratched && number != null) {
            // ✅ 狀態 1：已刮開 (金/綠/白底)
            scratchingCells.remove(cellKey)
            stopCellAnimation(cellKey) // 🌟 清除動畫

            val fillColorRes = when {
                isSpecial -> R.color.scratch_card_gold
                isGrand -> R.color.scratch_card_green
                else -> R.color.scratch_card_white
            }

            val strokeColorRes = when {
                isSpecial -> R.color.scratch_card_gold
                isGrand -> R.color.scratch_card_green
                else -> R.color.scratch_card_light_gray
            }

            val strokeWidth = if (isSpecial || isGrand) 4 else 2

            val drawable = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.circle_cell_normal_background)?.mutate()
            if (drawable is android.graphics.drawable.GradientDrawable) {
                drawable.setColor(androidx.core.content.ContextCompat.getColor(requireContext(), fillColorRes))
                drawable.setStroke(strokeWidth, androidx.core.content.ContextCompat.getColor(requireContext(), strokeColorRes))
            }
            cellView.background = drawable

            val textColorRes = if (isSpecial || isGrand) android.R.color.white else R.color.black
            if (cellView is TextView) {
                cellView.text = number.toString()
                cellView.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), textColorRes))
            }
        } else if (isScratching && !isScratched) {
            // 🌟 狀態 2：正在刮卡中 (敲門 Ping 中) - 啟動漩渦動畫
            startSwirlAnimation(cellView, cellKey)
        } else {
            // ⬛ 狀態 3：尚未刮開 (黑底蓋板)
            stopCellAnimation(cellKey) // 🌟 清除動畫

            val drawable = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.circle_cell_background_black)?.mutate()
            if (drawable is android.graphics.drawable.GradientDrawable) {
                drawable.setColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.scratch_card_dark_gray))
                drawable.setStroke(2, androidx.core.content.ContextCompat.getColor(requireContext(), R.color.scratch_card_light_gray))
            }
            cellView.background = drawable

            if (cellView is TextView) {
                cellView.text = ""
            }
        }
    }

    // ====== 漩渦動畫邏輯 ======
    private fun startSwirlAnimation(cellView: View, cellKey: String) {
        // 先停止之前的動畫
        stopCellAnimation(cellKey)

        // 確保 cellView 的父容器是 FrameLayout
        val parent = cellView.parent as? FrameLayout ?: return

        // 創建漩渦View (引用既有的 SwirlView)
        val swirlView = SwirlView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 將漩渦View添加到父容器的最底層（在原本黑格子的下方）
        parent.addView(swirlView, 0)
        swirlViews[cellKey] = swirlView

        // 隱藏原本的 cellView 背景，讓底下的漩渦透出來
        cellView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        if (cellView is TextView) {
            cellView.text = ""
        }

        // 添加脈動效果 - 只作用於漩渦View
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

        // 保存動畫引用以便後續清除
        cellAnimators[cellKey] = listOf(scaleXAnimator, scaleYAnimator)
    }

    private fun stopCellAnimation(cellKey: String) {
        // 停止並移除動畫
        cellAnimators[cellKey]?.forEach { animator ->
            animator.cancel()
            animator.removeAllListeners()
        }
        cellAnimators.remove(cellKey)

        // 移除漩渦View
        swirlViews[cellKey]?.let { swirlView ->
            (swirlView.parent as? ViewGroup)?.removeView(swirlView)
        }
        swirlViews.remove(cellKey)
    }

    // 渲染子版頂部資訊列
    private fun populateBoardHeader(containerView: View, board: com.champion.king.model.Board) {
        val tvBoardName = containerView.findViewById<TextView>(R.id.tv_board_name)
        val tvSpecialPrize = containerView.findViewById<TextView>(R.id.tv_special_prize)
        val llGrandPrizes = containerView.findViewById<android.widget.LinearLayout>(R.id.ll_grand_prizes)
        val tvPitchRule = containerView.findViewById<TextView>(R.id.tv_pitch_rule)
        val tvGrandPrizeLabel = containerView.findViewById<TextView>(R.id.tv_grand_prize_label) // 🌟 新增：取得大獎標籤

        // 1. 設定版名
        tvBoardName?.text = "${board.id}板"

        // 2. 設定特獎 (黃底白字)
        if (tvSpecialPrize != null) {
            val specialPrizeStr = board.specialPrize
            if (specialPrizeStr.isNullOrBlank() || specialPrizeStr == "無") {
                tvSpecialPrize.text = "無"
                tvSpecialPrize.background = null
            } else {
                tvSpecialPrize.text = specialPrizeStr
                val gold = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.scratch_card_gold)
                tvSpecialPrize.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(gold)
                    setStroke(2, gold)
                }
            }
        }

        // 3. 設定大獎 (綠底白字)
        if (llGrandPrizes != null) {
            llGrandPrizes.removeAllViews()
            val grandPrizeStr = board.grandPrize
            if (grandPrizeStr.isNullOrBlank() || grandPrizeStr == "無") {
                // 🌟 如果沒有大獎，直接隱藏「大獎：」標籤與整個容器
                tvGrandPrizeLabel?.visibility = View.GONE
                llGrandPrizes.visibility = View.GONE
            } else {
                // 🌟 如果有大獎，確保標籤與容器顯示出來
                tvGrandPrizeLabel?.visibility = View.VISIBLE
                llGrandPrizes.visibility = View.VISIBLE

                val allNumbers = grandPrizeStr.split(",").mapNotNull { it.trim().toIntOrNull() }
                val green = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.scratch_card_green)
                val whiteText = androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.white)

                // 🌟 大幅增加圈圈的直徑 (與特獎的 38dp 一致)
                val sizePx = (38 * resources.displayMetrics.density).toInt()
                val marginPx = (4 * resources.displayMetrics.density).toInt()

                for (num in allNumbers) {
                    val tv = TextView(requireContext()).apply {
                        text = num.toString()
                        textSize = 18f // 🌟 放大數字字體
                        setTextColor(whiteText)
                        gravity = android.view.Gravity.CENTER
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(green)
                            setStroke(2, green)
                        }
                        layoutParams = android.widget.LinearLayout.LayoutParams(sizePx, sizePx).apply {
                            marginEnd = marginPx
                        }
                    }
                    llGrandPrizes.addView(tv)
                }
            }
        }

        // 4. 設定夾送規則 (黃色字體)
        if (tvPitchRule != null) {
            val pitchType = board.pitchType ?: "scratch"
            val claws = board.clawsCount ?: 0
            val giveaway = board.giveawayCount ?: 0

            if (pitchType == "shopping") {
                tvPitchRule.text = "消費${claws}送${giveaway}"
            } else {
                tvPitchRule.text = "夾${claws}送${giveaway}"
            }

            // 🌟 點擊夾送規則連點 7 下「重新鎖定螢幕」
            tvPitchRule.setOnClickListener {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastRelockTapAt > 1200) {
                    relockTapCount = 0
                }
                lastRelockTapAt = now
                relockTapCount++

                if (relockTapCount >= 7) {
                    relockTapCount = 0
                    Log.d(TAG, "夾送規則連點7次，觸發重新鎖定螢幕")
                    (activity as? MainActivity)?.relockFromPlayerGesture()
                    activity?.let { ToastManager.show(it, "已重新啟用鎖定模式") }
                }
            }
        }
    }

    // ====== 顯示無卡片或斷線提示 (完美復刻舊版防護) ======
    private fun displayNoCardMessage(message: String) {
        if (!isAdded) return

        // 1. 清空所有舊版面
        mainContentContainer.removeAllViews()

        // 2. 把提示文字「加回」畫面上
        if (noCardTextView.parent == null) {
            mainContentContainer.addView(noCardTextView)
        }

        // 3. 更新文字並顯示
        noCardTextView.text = message
        noCardTextView.visibility = View.VISIBLE

        // 4. 清空狀態，避免記憶體洩漏與舊資料殘留
        cellViews.clear()
        currentMasterCard = null
        scratchingCells.clear()

        // 5. 清理所有動畫
        cellAnimators.keys.toList().forEach { cellKey ->
            stopCellAnimation(cellKey)
        }
    }

    // ====== 網路狀態檢查 ======
    private fun isNetworkAvailable(): Boolean {
        // 🌟 修正：使用安全的 context 取代 requireContext()，避免 Fragment 拔除時閃退
        val ctx = context ?: return false
        val connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            networkInfo != null && networkInfo.isConnected
        }
    }

    // ==========================================
    // 🌟 分割版面專用：獎項判斷與寫入資料庫邏輯
    // ==========================================

    private fun isSpecialPrize(number: Int, board: com.champion.king.model.Board): Boolean {
        val specialPrizeStr = board.specialPrize
        return if (specialPrizeStr.isNullOrEmpty()) false
        else specialPrizeStr.split(",").mapNotNull { it.trim().toIntOrNull() }.contains(number)
    }

    private fun isGrandPrize(number: Int, board: com.champion.king.model.Board): Boolean {
        val grandPrizeStr = board.grandPrize
        return if (grandPrizeStr.isNullOrEmpty()) false
        else grandPrizeStr.split(",").mapNotNull { it.trim().toIntOrNull() }.contains(number)
    }

    private fun isSecondToLastScratch(board: com.champion.king.model.Board): Boolean {
        val remaining = board.numberConfigurations?.count { it.scratched == false } ?: 0
        return remaining == 2
    }

    private fun hasUnscatchedPrizes(board: com.champion.king.model.Board): Boolean {
        val unscratchedNumbers = board.numberConfigurations
            ?.filter { it.scratched == false }
            ?.mapNotNull { it.number } ?: emptyList()
        return unscratchedNumbers.any { isSpecialPrize(it, board) || isGrandPrize(it, board) }
    }

    // ✅ 寫入 scratchCardsTemp 防弊暫存 (加入 boardId)
    private fun writeTempScratch(serialNumber: String, boardId: String, cellNumber: Int) {
        val userKey = userSessionProvider?.getCurrentUserFirebaseKey() ?: return

        // 🌟 寫入本地硬碟 (加入 boardId: A_15)
        val sp = requireContext().getSharedPreferences("LocalPendingScratches_$userKey", Context.MODE_PRIVATE)
        val pendingSet = sp.getStringSet("pending_scratches", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        pendingSet.add("$serialNumber:$boardId:$cellNumber")
        sp.edit().putStringSet("pending_scratches", pendingSet).apply()

        try {
            val db = FirebaseDatabase.getInstance().reference
            val tempRef = db.child("users").child(userKey).child("scratchCardsTemp").push()

            val data = mapOf(
                "cardId" to serialNumber,
                "boardId" to boardId,
                "cellNumber" to cellNumber,
                "createdAt" to ServerValue.TIMESTAMP
            )
            tempRef.setValue(data)
                .addOnSuccessListener { Log.d(TAG, "✅ 已寫入暫存: $boardId 板, 第 $cellNumber 格") }
        } catch (e: Exception) {
            Log.e(TAG, "❌ writeTempScratch() 失敗: ${e.message}")
        }
    }

    // ✅ 正式刮開寫入 Firebase (增強 Log 版本)
    private fun scratchCell(serialNumber: String, boardId: String, cellNumber: Int, cellKey: String, cellView: View) {
        val userKey = userSessionProvider?.getCurrentUserFirebaseKey() ?: return

        Log.d(TAG, "準備寫入 Firebase -> user:$userKey, serial:$serialNumber, board:$boardId, cell:$cellNumber")

        // 🌟 路徑：users/uid/scratchCards/serial/boards/boardId/numberConfigurations
        val targetRef = database.child("users").child(userKey).child("scratchCards")
            .child(serialNumber).child("boards").child(boardId).child("numberConfigurations")

        targetRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    Log.e(TAG, "❌ Firebase 找不到對應的 numberConfigurations (路徑可能錯誤)")
                    return
                }

                var found = false
                for ((index, child) in snapshot.children.withIndex()) {
                    val id = child.child("id").getValue(Int::class.java)
                    if (id == cellNumber) {
                        found = true
                        val alreadyScratched = child.child("scratched").getValue(Boolean::class.java) ?: false
                        if (alreadyScratched) {
                            scratchingCells.remove(cellKey)
                            Log.d(TAG, "⚠️ 格子 $cellKey 已經是刮開狀態，略過重複寫入")
                            return
                        }

                        val updates = mapOf<String, Any>(
                            "scratched" to true,
                            "scratchedAt" to ServerValue.TIMESTAMP
                        )

                        targetRef.child(index.toString()).updateChildren(updates)
                            .addOnSuccessListener {
                                Log.d(TAG, "✅ 成功寫入 Firebase: $cellKey 刮開")

                                // 解除本地硬碟防弊鎖
                                val sp = requireContext().getSharedPreferences("LocalPendingScratches_$userKey", Context.MODE_PRIVATE)
                                val pendingSet = sp.getStringSet("pending_scratches", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                                pendingSet.remove("$serialNumber:$boardId:$cellNumber")
                                sp.edit().putStringSet("pending_scratches", pendingSet).apply()
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "❌ 寫入 Firebase 失敗: ${e.message}")
                                scratchingCells.remove(cellKey)
                                val board = currentMasterCard?.boards?.get(boardId)
                                if (board != null) updateBoardCellDisplay(cellView, cellKey, false, null, board)
                            }
                        break
                    }
                }

                if (!found) {
                    Log.e(TAG, "❌ 在 snapshot 中找不到 id == $cellNumber 的格子！")
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "❌ 讀取資料庫失敗: ${error.message}")
                scratchingCells.remove(cellKey)
            }
        })
    }

    // ====== 網路斷線自動恢復機制 ======
    private fun canSafelyUpdateUi(): Boolean =
        isAdded && view != null &&
                viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)

    private fun startNetworkHintLoop() {
        if (networkLoopStarted) return
        networkLoopStarted = true

        val runnable = object : Runnable {
            override fun run() {
                if (!isAdded) return

                val online = isNetworkAvailable()
                if (!online) {
                    // 如果沒有卡片，或者卡片已經被清空，就持續顯示斷線提示
                    if (mainContentContainer.childCount <= 1 && currentMasterCard == null) {
                        displayNoCardMessage("目前未連線網路，請先連接 Wi-Fi / 行動網路後再使用。")
                    }
                    networkHandler.postDelayed(this, 1500)
                    return
                }

                // 網路恢復了，如果卡片還沒載入，就自動幫忙載入
                if (mainContentContainer.childCount <= 1 && currentMasterCard == null) {
                    loadSplitScratchCard()
                }
                networkHandler.postDelayed(this, 3000)
            }
        }
        networkHandler.post(runnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scratchCardsListener?.let { scratchCardsRef?.removeEventListener(it) }

        // 🌟 停止網路 loop
        networkHandler.removeCallbacksAndMessages(null)
        networkLoopStarted = false

        // 清理所有動畫
        cellAnimators.keys.toList().forEach { cellKey ->
            stopCellAnimation(cellKey)
        }
    }
}