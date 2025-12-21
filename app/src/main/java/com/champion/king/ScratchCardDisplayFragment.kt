package com.champion.king

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import androidx.core.content.ContextCompat
import com.champion.king.core.ui.BaseBindingFragment
import com.champion.king.data.DbListenerHandle
import com.champion.king.data.ScratchCardRepository
import com.champion.king.databinding.FragmentScratchCardBinding
import com.champion.king.model.ScratchCard
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.GridLayout
import com.champion.king.util.ToastManager

/**
 * 台主專用的刮刮卡顯示Fragment
 * - 顯示和玩家頁面一致的刮卡界面
 * - 只能看，不能刮（無互動功能）
 * - 支援所有版型（動態檢查布局文件是否存在）
 */
class ScratchCardDisplayFragment : BaseBindingFragment<FragmentScratchCardBinding>() {

    private var userSessionProvider: UserSessionProvider? = null
    private val repo by lazy { ScratchCardRepository() }
    private var scratchCardsHandle: DbListenerHandle? = null

    // 儲存格子視圖的參考，用於更新顯示狀態
    private val cellViews = mutableMapOf<Int, View>()
    private var currentScratchCard: ScratchCard? = null
    // ✅ 新增：台主頁面的剩餘刮數文字
    private var remainingScratchTextView: TextView? = null

    companion object {
        private const val TAG = "ScratchCardDisplayFragment"
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is UserSessionProvider) userSessionProvider = context
        else throw RuntimeException("$context must implement UserSessionProvider")
    }

    override fun onDetach() {
        super.onDetach()
        userSessionProvider = null
    }

    override fun createBinding(inflater: LayoutInflater, container: android.view.ViewGroup?) =
        FragmentScratchCardBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 從 MainActivity (台主畫面) 找到左下角的剩餘刮數 TextView
        remainingScratchTextView = activity?.findViewById(R.id.remaining_scratches_text_view)

        startObserve()
    }

    private fun startObserve() {
        val userKey = userSessionProvider?.getCurrentUserFirebaseKey()
        if (userKey.isNullOrEmpty()) {
            displayMessage("(請先登入)")
            Log.w(TAG, "未登入用戶，無法載入刮刮卡資料。")
            return
        }

        // 先清掉舊監聽
        scratchCardsHandle?.remove()

        // 監聽 /users/{key}/scratchCards
        scratchCardsHandle = repo.observeUserScratchCards(
            userKey,
            onCards = { cards ->
                if (!isAdded || view == null) return@observeUserScratchCards

                val inUse = cards.firstOrNull { it.inUsed }
                if (inUse != null) {
                    displayScratchCard(inUse)
                    userSessionProvider?.setCurrentlyDisplayedScratchCardOrder(inUse.order)
                } else {
                    displayMessage("(準備中...)")
                    userSessionProvider?.setCurrentlyDisplayedScratchCardOrder(null)
                }
            },
            onError = { msg ->
                if (!isAdded || view == null) return@observeUserScratchCards
                activity?.let {
                    ToastManager.show(it, "載入刮刮卡失敗：$msg")
                }
                displayMessage("載入失敗: $msg")
            }
        )
    }

    private fun displayScratchCard(scratchCard: ScratchCard) {
        if (!isAdded || view == null) return

        currentScratchCard = scratchCard

        // ✅ 新增：更新台主頁面的剩餘刮數顯示
        updateRemainingScratchesDisplay()

        val scratchesType = scratchCard.scratchesType ?: 10

        try {
            binding.scratchCardContainer.removeAllViews()
            binding.noScratchCardText.visibility = View.GONE

            // 動態檢查布局資源是否存在
            val layoutResId = resources.getIdentifier(
                "scratch_card_$scratchesType",
                "layout",
                requireContext().packageName
            )

            if (layoutResId == 0) {
                // 找不到對應的布局文件
                displayMessage("尚未建立 ${scratchesType} 刮版型的布局文件 (scratch_card_${scratchesType}.xml)")
                Log.w(TAG, "找不到布局資源: scratch_card_$scratchesType")
                return
            }

            val view = LayoutInflater.from(requireContext())
                .inflate(layoutResId, binding.scratchCardContainer, false)
            binding.scratchCardContainer.addView(view)

            // 設置刮刮卡（只讀模式）
            setupScratchCardReadOnly(view, scratchCard)

            Log.d(TAG, "成功載入${scratchesType}刮版型 (order=${scratchCard.order})，台主只讀模式。")

        } catch (e: Exception) {
            Log.e(TAG, "載入版型失敗: ${e.message}", e)
            displayMessage("載入刮刮卡版型時發生錯誤：${e.message}")
        }
    }

    // 新增通用的 setupScratchCardReadOnly 方法
    private fun setupScratchCardReadOnly(containerView: View, card: ScratchCard) {
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

            // 在 FrameLayout 中找到 TextView
            val cellView = if (frameLayout.childCount > 0) {
                frameLayout.getChildAt(0)
            } else {
                continue
            }

            cellViews[cellNumber] = cellView
            setupCellReadOnly(cellView, cellNumber, card)
            cellNumber++
        }
    }

    private fun setupCellReadOnly(cellView: View, cellNumber: Int, card: ScratchCard) {
        // 根據資料庫結構，使用 numberConfigurations 數組
        val numberConfig = card.numberConfigurations?.find { it.id == cellNumber }
        val isScratched = numberConfig?.scratched == true
        val number = numberConfig?.number

        updateCellDisplay(cellView, isScratched, number)

        // 重點：不設定點擊事件，只顯示狀態
        cellView.isClickable = false
        cellView.isFocusable = false
    }

    private fun updateCellDisplay(cellView: View, isScratched: Boolean, number: Int?) {
        if (isScratched && number != null) {
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
                else -> 2  // 一般數字用細框，和未刮開的一樣
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
        } else {
            // 未刮開：黑色蓋板，灰色細圓框
            val drawable = ContextCompat.getDrawable(requireContext(), R.drawable.circle_cell_background_black)?.mutate()
            if (drawable is android.graphics.drawable.GradientDrawable) {
                drawable.setColor(ContextCompat.getColor(requireContext(), R.color.scratch_card_dark_gray))
                drawable.setStroke(2, ContextCompat.getColor(requireContext(), R.color.scratch_card_light_gray))  // 細框
            }
            cellView.background = drawable

            if (cellView is TextView) {
                cellView.text = ""
            } else {
                removeNumberFromCell(cellView)
            }
        }
    }

    private fun addNumberToCell(
        cellView: View,
        number: Int,
        textColorRes: Int = R.color.scratch_card_light_gray
    ) {
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

    private fun isSpecialPrize(number: Int): Boolean {
        val specialPrizeStr = currentScratchCard?.specialPrize
        return if (specialPrizeStr.isNullOrEmpty()) {
            false
        } else {
            // 處理可能的多個特獎數字（用逗號分隔）
            specialPrizeStr.split(",").map { it.trim().toIntOrNull() }.contains(number)
        }
    }

    private fun isGrandPrize(number: Int): Boolean {
        val grandPrizeStr = currentScratchCard?.grandPrize
        return if (grandPrizeStr.isNullOrEmpty()) {
            false
        } else {
            // 處理可能的多個大獎數字（用逗號分隔）
            grandPrizeStr.split(",").map { it.trim().toIntOrNull() }.contains(number)
        }
    }

    private fun removeNumberFromCell(cellView: View) {
        if (cellView.parent is FrameLayout) {
            val frameLayout = cellView.parent as FrameLayout
            val numberTextView = frameLayout.findViewWithTag<TextView>("number_text")
            numberTextView?.visibility = View.GONE
        }
    }

    private fun displayMessage(message: String) {
        if (!isAdded || view == null) return
        binding.scratchCardContainer.removeAllViews()
        binding.noScratchCardText.text = message
        binding.noScratchCardText.visibility = View.VISIBLE
        cellViews.clear()
        currentScratchCard = null

        // ✅ 無刮卡時，隱藏剩餘刮數顯示
        remainingScratchTextView?.apply {
            text = ""
            visibility = View.GONE
        }
    }

    /**
     * 計算當前刮刮卡剩餘未刮開的格子數量（台主頁面用）
     */
    private fun getRemainingUnscratched(): Int {
        val card = currentScratchCard ?: return 0
        return card.numberConfigurations?.count { it.scratched == false } ?: 0
    }

    /**
     * 更新左側下方顯示的「剩餘刮數 / 總刮數（版型）」- 台主頁面
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

        // 格式跟玩家頁面一樣：剩餘 / 總刮數（版型）
        view.text = "$remaining/$total"
        view.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scratchCardsHandle?.remove()
        scratchCardsHandle = null
    }
}