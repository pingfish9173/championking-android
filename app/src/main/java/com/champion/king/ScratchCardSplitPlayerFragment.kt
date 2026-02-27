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

        val splitMode = masterCard.splitMode ?: return
        val layoutName = "scratch_card_split_${splitMode.replace("x", "_x")}"
        val layoutResId = resources.getIdentifier(layoutName, "layout", requireContext().packageName)

        if (layoutResId == 0) {
            val placeholder = TextView(requireContext()).apply {
                text = "待開發\n(找不到版型: $layoutName.xml)"
                textSize = 32f
                setTextColor(android.graphics.Color.GRAY)
                gravity = android.view.Gravity.CENTER
            }
            mainContentContainer.addView(placeholder)
            return
        }

        try {
            // 1. 載入田字型外殼
            val splitView = LayoutInflater.from(requireContext()).inflate(layoutResId, mainContentContainer, false)
            mainContentContainer.addView(splitView)

            // 2. 建立 panel 對照表
            val panels = mapOf(
                "A" to splitView.findViewById<FrameLayout>(R.id.panelA),
                "B" to splitView.findViewById<FrameLayout>(R.id.panelB),
                "C" to splitView.findViewById<FrameLayout>(R.id.panelC),
                "D" to splitView.findViewById<FrameLayout>(R.id.panelD)
            )

            // 3. 拿出母卡裡面的 boards，把子版一個一個畫上去
            val boardsMap = masterCard.boards ?: return

            for ((boardId, board) in boardsMap) {
                val panel = panels[boardId] ?: continue
                panel.removeAllViews()

                // 假設每個子版都是 20 刮，我們去抓 scratch_card_20.xml
                val cellsCount = board.numberConfigurations?.size ?: 20
                val cardLayoutResId = resources.getIdentifier("scratch_card_$cellsCount", "layout", requireContext().packageName)

                if (cardLayoutResId != 0) {
                    val cardView = LayoutInflater.from(requireContext()).inflate(cardLayoutResId, panel, false)
                    panel.addView(cardView)

                    // 👉 呼叫專屬的方法來綁定這個子版的所有格子
                    setupBoard(cardView, masterCard.serialNumber ?: "", board)
                } else {
                    Log.w(TAG, "找不到子版佈局: scratch_card_$cellsCount")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "載入分割版型失敗: ${e.message}", e)
        }
    }

    // 針對單一子版（A, B, C 或 D）進行綁定
    private fun setupBoard(containerView: View, serialNumber: String, board: com.champion.king.model.Board) {
        val gridLayout = containerView.findViewById<androidx.gridlayout.widget.GridLayout>(R.id.gridLayout) ?: containerView.findViewById<android.widget.GridLayout>(R.id.gridLayout)

        if (gridLayout == null) {
            Log.e(TAG, "找不到 GridLayout (Board ${board.id})")
            return
        }

        val totalCells = board.numberConfigurations?.size ?: 20
        var cellNumber = 1

        for (i in 0 until gridLayout.childCount) {
            if (cellNumber > totalCells) break

            val frameLayout = gridLayout.getChildAt(i) as? FrameLayout ?: continue
            val cellView = if (frameLayout.childCount > 0) frameLayout.getChildAt(0) else continue

            // 🌟 關鍵：合成唯一的 Key，例如 "A_1"
            val cellKey = "${board.id}_$cellNumber"
            cellViews[cellKey] = cellView

            val config = board.numberConfigurations?.find { it.id == cellNumber }

            // TODO: 更新畫面狀態 (我們稍後把原本的 updateCellDisplay 搬過來改)
            // updateBoardCellDisplay(cellView, cellKey, config?.scratched == true, config?.number, board)

            // 🌟 點擊事件：處理刮卡邏輯
            cellView.setOnClickListener {
                // 防呆：確認還沒刮開
                val refreshedConfig = currentMasterCard?.boards?.get(board.id)?.numberConfigurations?.find { it.id == cellNumber }
                if (refreshedConfig?.scratched == true) return@setOnClickListener

                Log.d(TAG, "點擊了子版 ${board.id} 的第 $cellNumber 格")

                // TODO: 這裡放入你原本的「主動敲門測試 (Ping)」與呼叫 ScratchDialog 的邏輯
                // 記得 ScratchDialog 成功後，呼叫的寫入路徑要是：
                // database.child("users").child(uid).child("scratchCards").child(serialNumber).child("boards").child(board.id).child("numberConfigurations").child(index.toString())
            }
            cellNumber++
        }
    }

    private fun updateSplitBoards(masterCard: ScratchCard) {
        val boardsMap = masterCard.boards ?: return

        for ((boardId, board) in boardsMap) {
            board.numberConfigurations?.forEach { config ->
                val cellKey = "${boardId}_${config.id}"
                val cellView = cellViews[cellKey] ?: return@forEach

                // TODO: 呼叫更新畫面的方法
                // updateBoardCellDisplay(cellView, cellKey, config.scratched == true, config.number, board)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scratchCardsListener?.let { scratchCardsRef?.removeEventListener(it) }
    }
}