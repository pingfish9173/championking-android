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
import com.champion.king.data.DbListenerHandle
import com.champion.king.data.ScratchCardRepository
import com.champion.king.model.ScratchCard

class ScratchCardSplitDisplayFragment : Fragment() {

    private lateinit var mainContentContainer: FrameLayout
    private lateinit var noCardTextView: TextView
    private var userSessionProvider: UserSessionProvider? = null

    // 使用台主專用的 Repository 監聽資料
    private val repo by lazy { ScratchCardRepository() }
    private var scratchCardsHandle: DbListenerHandle? = null

    private var currentMasterCard: ScratchCard? = null
    private val cellViews = mutableMapOf<String, View>()
    
    // 台主頁面左下角的剩餘刮數文字 (分割版面需將其隱藏)
    private var remainingScratchTextView: TextView? = null

    companion object {
        private const val TAG = "ScratchSplitDisplay"
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is UserSessionProvider) userSessionProvider = context
        else throw RuntimeException("$context must implement UserSessionProvider")
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
        remainingScratchTextView = activity?.findViewById(R.id.remaining_scratches_text_view)

        // 🌟 分割版面：直接隱藏左下角的剩餘刮數
        remainingScratchTextView?.apply {
            text = ""
            visibility = View.GONE
        }

        startObserve()
    }

    private fun startObserve() {
        val userKey = userSessionProvider?.getCurrentUserFirebaseKey()
        if (userKey.isNullOrEmpty()) {
            displayNoCardMessage("(請先登入)")
            return
        }

        scratchCardsHandle?.remove()

        scratchCardsHandle = repo.observeUserScratchCards(
            userKey,
            onCards = { cards ->
                if (!isAdded) return@observeUserScratchCards

                val inUse = cards.firstOrNull { it.inUsed }
                if (inUse != null && !inUse.splitMode.isNullOrEmpty()) {
                    // 找到了使用中的分割版面
                    userSessionProvider?.setCurrentlyDisplayedScratchCardOrder(inUse.order)
                    handleCardUpdate(inUse)
                } else {
                    displayNoCardMessage("目前沒有可用的分割版面刮刮卡")
                    userSessionProvider?.setCurrentlyDisplayedScratchCardOrder(null)
                }
            },
            onError = { msg ->
                if (!isAdded) return@observeUserScratchCards
                displayNoCardMessage("載入失敗: $msg")
            }
        )
    }

    private fun handleCardUpdate(targetCard: ScratchCard) {
        noCardTextView.visibility = View.GONE

        val needRebuild = currentMasterCard?.serialNumber != targetCard.serialNumber || mainContentContainer.childCount <= 1

        currentMasterCard = targetCard

        if (needRebuild) {
            buildSplitLayout(targetCard)
        } else {
            updateSplitBoards(targetCard)
        }
    }

    private fun buildSplitLayout(masterCard: ScratchCard) {
        mainContentContainer.removeAllViews()
        cellViews.clear()

        val splitMode = masterCard.splitMode ?: return
        val layoutName = "scratch_card_split_${splitMode.replace("x", "_x")}"
        val layoutResId = resources.getIdentifier(layoutName, "layout", requireContext().packageName)

        if (layoutResId == 0) return

        try {
            val splitView = LayoutInflater.from(requireContext()).inflate(layoutResId, mainContentContainer, false)
            mainContentContainer.addView(splitView)

            val panels = mapOf(
                "A" to splitView.findViewById<FrameLayout>(R.id.panelA),
                "B" to splitView.findViewById<FrameLayout>(R.id.panelB),
                "C" to splitView.findViewById<FrameLayout>(R.id.panelC),
                "D" to splitView.findViewById<FrameLayout>(R.id.panelD)
            )

            val boardsMap = masterCard.boards ?: emptyMap()

            for ((panelId, panel) in panels) {
                if (panel == null) continue
                val board = boardsMap[panelId]
                panel.removeAllViews()

                if (board == null) {
                    val errorText = TextView(requireContext()).apply {
                        text = "$panelId 區\n(無資料)"
                        setTextColor(android.graphics.Color.RED)
                        textSize = 24f
                        gravity = android.view.Gravity.CENTER
                    }
                    panel.addView(errorText)
                    continue
                }

                val splitModeSuffix = masterCard.splitMode?.replace("x", "_x") ?: "20_x4"
                val subLayoutName = "scratch_card_sub_$splitModeSuffix"
                val cardLayoutResId = resources.getIdentifier(subLayoutName, "layout", requireContext().packageName)

                if (cardLayoutResId != 0) {
                    val cardView = LayoutInflater.from(requireContext()).inflate(cardLayoutResId, panel, false)
                    panel.addView(cardView)
                    setupBoardReadOnly(cardView, board)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "載入分割版型失敗: ${e.message}", e)
        }
    }

    private fun updateSplitBoards(masterCard: ScratchCard) {
        val boardsMap = masterCard.boards ?: return
        for ((boardId, board) in boardsMap) {
            board.numberConfigurations?.forEach { config ->
                val cellKey = "${boardId}_${config.id}"
                val cellView = cellViews[cellKey] ?: return@forEach
                updateBoardCellDisplay(cellView, config.scratched == true, config.number, board)
            }
        }
    }

    private fun setupBoardReadOnly(containerView: View, board: com.champion.king.model.Board) {
        populateBoardHeader(containerView, board)

        val gridLayout = containerView.findViewById<android.view.ViewGroup>(R.id.gridLayout) ?: return
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

            updateBoardCellDisplay(cellView, config?.scratched == true, config?.number, board)

            // 🌟 台主監視器：強制移除所有點擊事件
            frameLayout.setOnClickListener(null)
            frameLayout.isClickable = false
            cellView.isClickable = false

            cellNumber++
        }
    }

    private fun updateBoardCellDisplay(
        cellView: View, isScratched: Boolean, number: Int?, board: com.champion.king.model.Board) {

        val specialPrizeList = board.specialPrize?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        val grandPrizeList = board.grandPrize?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()

        val isSpecial = number != null && specialPrizeList.contains(number)
        val isGrand = number != null && grandPrizeList.contains(number)

        if (isScratched && number != null) {
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
        } else {
            // 🌟 尚未刮開的狀態 (純黑底蓋板，不顯示數字)
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

    private fun populateBoardHeader(containerView: View, board: com.champion.king.model.Board) {
        val tvBoardName = containerView.findViewById<TextView>(R.id.tv_board_name)
        val tvSpecialPrize = containerView.findViewById<TextView>(R.id.tv_special_prize)
        val llGrandPrizes = containerView.findViewById<android.widget.LinearLayout>(R.id.ll_grand_prizes)
        val tvPitchRule = containerView.findViewById<TextView>(R.id.tv_pitch_rule)

        tvBoardName?.text = "${board.id}板"

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

        if (llGrandPrizes != null) {
            llGrandPrizes.removeAllViews()
            val grandPrizeStr = board.grandPrize
            if (grandPrizeStr.isNullOrBlank() || grandPrizeStr == "無") {
                val tv = TextView(requireContext()).apply {
                    text = "無"
                    setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.white))
                    textSize = 10f
                    gravity = android.view.Gravity.CENTER
                }
                llGrandPrizes.addView(tv)
            } else {
                val allNumbers = grandPrizeStr.split(",").mapNotNull { it.trim().toIntOrNull() }
                val green = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.scratch_card_green)
                val whiteText = androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.white)

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

        if (tvPitchRule != null) {
            val pitchType = board.pitchType ?: "scratch"
            val claws = board.clawsCount ?: 0
            val giveaway = board.giveawayCount ?: 0
            tvPitchRule.text = if (pitchType == "shopping") "消費${claws}送${giveaway}" else "夾${claws}送${giveaway}"
        }
    }

    private fun displayNoCardMessage(message: String) {
        if (!isAdded) return
        mainContentContainer.removeAllViews()
        if (noCardTextView.parent == null) mainContentContainer.addView(noCardTextView)
        noCardTextView.text = message
        noCardTextView.visibility = View.VISIBLE
        cellViews.clear()
        currentMasterCard = null
    }

    override fun onDetach() {
        super.onDetach()
        userSessionProvider = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scratchCardsHandle?.remove()
        scratchCardsHandle = null
    }
}