package com.champion.king.ui.settings

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.champion.king.R
import com.champion.king.SettingsViewModel
import com.champion.king.constants.ScratchCardConstants
import com.champion.king.databinding.FragmentSettingsBinding
import com.champion.king.model.ScratchCard

class ShelfManager(
    private val binding: FragmentSettingsBinding,
    private val viewModel: SettingsViewModel
) {
    private lateinit var shelfItems: List<FrameLayout>
    private lateinit var shelfTexts: List<TextView>
    private lateinit var shelfStars: List<TextView>

    var selectedShelfOrder: Int = ScratchCardConstants.DEFAULT_SHELF_ORDER
        private set

    private var selectedShelfItem: FrameLayout? = null
    private lateinit var onShelfClickListener: (Int) -> Unit

    fun setOnShelfClickListener(listener: (Int) -> Unit) {
        onShelfClickListener = listener
    }

    fun initShelfViews() {
        val root = binding.root
        shelfItems = listOf(
            root.findViewById<FrameLayout>(R.id.shelf_item_1),
            root.findViewById<FrameLayout>(R.id.shelf_item_2),
            root.findViewById<FrameLayout>(R.id.shelf_item_3),
            root.findViewById<FrameLayout>(R.id.shelf_item_4),
            root.findViewById<FrameLayout>(R.id.shelf_item_5),
            root.findViewById<FrameLayout>(R.id.shelf_item_6)
        )

        shelfTexts = shelfItems.map { it.findViewById(R.id.shelf_item_text) }
        shelfStars = shelfItems.map { it.findViewById(R.id.shelf_item_star) }

        setupShelfClickListeners()
    }

    private fun setupShelfClickListeners() {
        shelfItems.forEachIndexed { index, frameLayout ->
            frameLayout.setOnClickListener {
                val order = index + 1
                selectShelf(order)
                if (::onShelfClickListener.isInitialized) {
                    onShelfClickListener(order)
                }
            }
        }
    }

    fun selectShelf(order: Int) {
        resetShelfItemBackgrounds()
        selectedShelfOrder = order
        val container = shelfItems[order - 1]
        container.setBackgroundResource(R.drawable.dark_green_bordered_box)
        selectedShelfItem = container
    }

    private fun resetShelfItemBackgrounds() {
        shelfItems.forEach {
            it.setBackgroundResource(R.drawable.blue_bordered_box)
        }
    }

    fun updateShelfUI(cards: Map<Int, ScratchCard>) {
        for (i in 0 until ScratchCardConstants.MAX_SHELF_COUNT) {
            val order = i + 1
            val textView = shelfTexts[i]
            val starView = shelfStars[i]
            val card = cards[order]

            if (card != null) {
                textView.text = buildShelfDisplayText(order, card)
                starView.visibility = if (card.inUsed) View.VISIBLE else View.GONE
            } else {
                textView.text = "${order}號板\n(未設置)"
                starView.visibility = View.GONE
            }
        }
    }

    private fun buildShelfDisplayText(order: Int, card: ScratchCard): String {
        return buildString {
            append("${order}號板\n")
            append("刮數：${card.scratchesType} 刮\n")
            append("特獎：${card.specialPrize ?: "無"}\n")

            // ✅ 大獎顯示優化
            val grandList = card.grandPrize?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val grandDisplay = when {
                grandList.isEmpty() -> "無"
                grandList.size <= 3 -> grandList.joinToString(",")
                else -> grandList.take(3).joinToString(",") + "..."
            }
            append("大獎：$grandDisplay\n")

            append("夾 ${card.clawsCount ?: "無"} 刮 ${card.giveawayCount ?: "無"}\n")
        }
    }

}