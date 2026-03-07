package com.champion.king.ui.settings

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
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
            val frameLayout = shelfItems[i]
            val textView = shelfTexts[i]
            val starView = shelfStars[i]

            // 🌟 尋找分割版面專屬 UI 元件
            val splitContainer = frameLayout.findViewById<LinearLayout>(R.id.shelf_item_split_container)
            val boardATv = frameLayout.findViewById<TextView>(R.id.shelf_item_board_a)
            val boardBTv = frameLayout.findViewById<TextView>(R.id.shelf_item_board_b)
            val boardCTv = frameLayout.findViewById<TextView>(R.id.shelf_item_board_c)
            val boardDTv = frameLayout.findViewById<TextView>(R.id.shelf_item_board_d)
            val boardETv = frameLayout.findViewById<TextView>(R.id.shelf_item_board_e)
            val boardFTv = frameLayout.findViewById<TextView>(R.id.shelf_item_board_f)
            val rowEF = frameLayout.findViewById<LinearLayout>(R.id.shelf_item_row_ef)

            val card = cards[order]

            if (card != null) {
                starView.visibility = if (card.inUsed) View.VISIBLE else View.GONE

                if (!card.splitMode.isNullOrEmpty()) {
                    // ===================================
                    // 🌟 分割版面顯示邏輯
                    // ===================================
                    splitContainer?.visibility = View.VISIBLE
                    val parts = card.splitMode!!.split("x")
                    val splitCountStr = if (parts.size == 2) "${parts[0]}刮x${parts[1]}板" else card.splitMode
                    textView.text = "${order}號板\n刮數：$splitCountStr"

                    // 幫助函式：萃取子板資料並組合文字
                    fun getBoardText(boardId: String): String {
                        // 透過反射安全地獲取 boards 屬性 (避免編譯錯誤或型別異常)
                        val boardsMap = try {
                            card.javaClass.getMethod("getBoards").invoke(card) as? Map<*, *>
                        } catch (e: Exception) { null }

                        val rawBoard = boardsMap?.get(boardId) ?: return ""

                        var sp: String? = null
                        var gp: String? = null

                        if (rawBoard is Map<*, *>) {
                            sp = rawBoard["specialPrize"] as? String
                            gp = rawBoard["grandPrize"] as? String
                        } else {
                            sp = try { rawBoard.javaClass.getMethod("getSpecialPrize").invoke(rawBoard) as? String } catch(e: Exception) { null }
                            gp = try { rawBoard.javaClass.getMethod("getGrandPrize").invoke(rawBoard) as? String } catch(e: Exception) { null }
                        }

                        if (sp.isNullOrEmpty()) return ""

                        // 判斷大獎是否有值，有就補上 ..
                        val dot = if (!gp.isNullOrEmpty() && gp != "無") ".." else ""
                        return "$boardId:特$sp$dot"
                    }

                    // 依序填入文字
                    boardATv?.text = getBoardText("A")
                    boardBTv?.text = getBoardText("B")
                    boardCTv?.text = getBoardText("C")
                    boardDTv?.text = getBoardText("D")

                    // 預留 EF 擴充防呆
                    val textE = getBoardText("E")
                    val textF = getBoardText("F")
                    boardETv?.text = textE
                    boardFTv?.text = textF
                    rowEF?.visibility = if (textE.isEmpty() && textF.isEmpty()) View.GONE else View.VISIBLE

                } else {
                    // ===================================
                    // 🌟 單一版面顯示邏輯
                    // ===================================
                    splitContainer?.visibility = View.GONE
                    textView.text = buildShelfDisplayText(order, card)
                }
            } else {
                textView.text = "${order}號板\n(未設置)"
                splitContainer?.visibility = View.GONE
                starView.visibility = View.GONE
            }
        }
    }

    private fun buildShelfDisplayText(order: Int, card: ScratchCard): String {
        return buildString {
            append("${order}號板\n")
            append("刮數：${card.scratchesType} 刮\n")
            append("特獎：${card.specialPrize ?: "無"}\n")

            val grandList = card.grandPrize?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val grandDisplay = when {
                grandList.isEmpty() -> "無"
                grandList.size <= 3 -> grandList.joinToString(",")
                else -> grandList.take(3).joinToString(",") + "..."
            }
            append("大獎：$grandDisplay\n")

            val prefix = if (card.pitchType == "shopping") "消費" else "夾"
            append("$prefix ${card.clawsCount ?: "無"} 刮 ${card.giveawayCount ?: "無"}\n")
        }
    }
}