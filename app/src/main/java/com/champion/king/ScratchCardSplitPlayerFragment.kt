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

class ScratchCardSplitPlayerFragment : Fragment() {

    private lateinit var mainContentContainer: FrameLayout

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
        // 🌟 關鍵修改：不再載入 player_split_main.xml
        // 直接產生一個乾淨的空 FrameLayout，用來裝載未來的田字型刮板 (A/B/C/D)
        return FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. 這個 view 就是我們剛剛在 onCreateView 建好的空 FrameLayout
        mainContentContainer = view as FrameLayout

        // 2. 直接開始載入 Firebase 資料，專心處理刮板邏輯！
        loadSplitScratchCard()
    }

    private fun loadSplitScratchCard() {
        val uid = userSessionProvider?.getCurrentUserFirebaseKey() ?: return

        scratchCardsRef = database.child("users").child(uid).child("scratchCards")
        scratchCardsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return

                var targetCard: ScratchCard? = null
                var targetSerial = ""

                // 找尋 inUsed == true 的母卡
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
                    return
                }

                targetCard.serialNumber = targetSerial
                
                // 判斷是否需要重新建立整個田字型 XML (例如第一次載入，或台主換了不同的版型)
                val needRebuild = currentMasterCard?.serialNumber != targetSerial || mainContentContainer.childCount == 0

                currentMasterCard = targetCard

                if (needRebuild) {
                    buildSplitLayout(targetCard)
                } else {
                    // TODO: 只更新格子狀態 (下一個步驟實作)
                    // updateSplitBoards(targetCard)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "載入失敗: ${error.message}")
            }
        }
        scratchCardsRef?.addValueEventListener(scratchCardsListener!!)
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
    private fun setupBoard(containerView: View, serialNumber: String, board: com.champion.king.model.Board) {

        // 🌟 呼叫剛寫好的 Function，動態填入頂部的獎項與規則！
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

            // 🌟 關鍵：合成唯一的 Key，例如 "A_1", "B_15"
            val cellKey = "${board.id}_$cellNumber"
            cellViews[cellKey] = cellView

            val config = board.numberConfigurations?.find { it.id == cellNumber }

            // 更新畫面為刮卡黑底狀態
            updateBoardCellDisplay(cellView, cellKey, config?.scratched == true, config?.number, board)

            // 🌟 點擊測試
            cellView.setOnClickListener {
                Log.d(TAG, "你點擊了 ${board.id} 區 的第 $cellNumber 格！")
                activity?.let { ToastManager.show(it, "這是 ${board.id} 區的第 $cellNumber 格") }

                // TODO: 之後會把主動敲門 (Ping) 測試跟彈出 ScratchDialog 的邏輯寫在這裡
            }
            cellNumber++
        }
    }

    private fun updateBoardCellDisplay(cellView: View, cellKey: String, isScratched: Boolean, number: Int?, board: com.champion.king.model.Board) {
        val isScratching = scratchingCells.contains(cellKey)

        // 將子版獨立的獎項字串轉為 List
        val specialPrizeList = board.specialPrize?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        val grandPrizeList = board.grandPrize?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()

        val isSpecial = number != null && specialPrizeList.contains(number)
        val isGrand = number != null && grandPrizeList.contains(number)

        if (isScratched && number != null) {
            // 已刮開的狀態 (顯示數字、判斷金/綠/白底)
            scratchingCells.remove(cellKey)

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
            if (cellView is TextView) cellView.text = ""
        } else {
            // 尚未刮開的狀態 (黑底)
            val drawable = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.circle_cell_background_black)?.mutate()
            if (drawable is android.graphics.drawable.GradientDrawable) {
                drawable.setColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.scratch_card_dark_gray))
                drawable.setStroke(2, androidx.core.content.ContextCompat.getColor(requireContext(), R.color.scratch_card_light_gray))
            }
            cellView.background = drawable

            if (cellView is TextView) {
                // 🌟 恢復為空白，玩家在刮開之前絕對看不到數字
                cellView.text = ""
            }
        }
    }

    // 渲染子版頂部資訊列
    private fun populateBoardHeader(containerView: View, board: com.champion.king.model.Board) {
        val tvBoardName = containerView.findViewById<TextView>(R.id.tv_board_name)
        val tvSpecialPrize = containerView.findViewById<TextView>(R.id.tv_special_prize)
        val llGrandPrizes = containerView.findViewById<android.widget.LinearLayout>(R.id.ll_grand_prizes)
        val tvPitchRule = containerView.findViewById<TextView>(R.id.tv_pitch_rule)

        // 1. 設定版名 (例如: A板)
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
                val tv = TextView(requireContext()).apply {
                    text = "無"
                    setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.white))
                    textSize = 10f // 配合縮小
                    gravity = android.view.Gravity.CENTER
                }
                llGrandPrizes.addView(tv)
            } else {
                val allNumbers = grandPrizeStr.split(",").mapNotNull { it.trim().toIntOrNull() }
                val green = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.scratch_card_green)
                val whiteText = androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.white)

                // 🌟 配合 XML，將綠圈的大小縮小到 22dp
                val sizePx = (22 * resources.displayMetrics.density).toInt()
                val marginPx = (2 * resources.displayMetrics.density).toInt()

                for (num in allNumbers) {
                    val tv = TextView(requireContext()).apply {
                        text = num.toString()
                        textSize = 10f
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
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scratchCardsListener?.let { scratchCardsRef?.removeEventListener(it) }
    }
}