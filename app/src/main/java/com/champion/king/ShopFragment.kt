package com.champion.king

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import com.champion.king.core.ui.BaseBindingFragment
import com.champion.king.data.DbListenerHandle
import com.champion.king.data.ShopRepository
import com.champion.king.databinding.FragmentShopBinding
import com.champion.king.model.ShopItem
import com.champion.king.util.guardOnline
import com.champion.king.util.setThrottledClick
import com.champion.king.util.toast

class ShopFragment : BaseBindingFragment<FragmentShopBinding>() {

    private var userSessionProvider: UserSessionProvider? = null

    private val repo by lazy { ShopRepository() }

    private var shopItems: List<ShopItem> = emptyList()
    private val itemQuantities = mutableMapOf<String, Int>()
    private var currentUserPoints: Int = 0

    // 監聽奶槍（onDestroyView 會確實移除）
    private var shopItemsHandle: DbListenerHandle? = null
    private var userPointsHandle: DbListenerHandle? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is UserSessionProvider) userSessionProvider = context
        else throw RuntimeException("$context must implement UserSessionProvider")
    }
    override fun onDetach() { super.onDetach(); userSessionProvider = null }

    override fun createBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentShopBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 監聽商店清單
        shopItemsHandle = repo.observeShopItems(
            onItems = { items ->
                if (!isAdded || this@ShopFragment.view == null) return@observeShopItems
                shopItems = items
                displayShopItems()
            },
            onError = { msg ->
                if (!isAdded || this@ShopFragment.view == null) return@observeShopItems
                Log.e("ShopFragment", "Failed to load shop items: $msg")
                requireContext().toast("載入商品失敗：$msg")
            }
        )

        // 監聽當前使用者點數
        val userKey = userSessionProvider?.getCurrentUserFirebaseKey()
        if (userKey.isNullOrEmpty()) {
            binding.userPointsTextview.text = "我的點數: N/A"
            requireContext().toast("無法載入點數：用戶未登入")
        } else {
            userPointsHandle = repo.observeUserPoints(
                userKey,
                onPoints = { p ->
                    if (!isAdded || this@ShopFragment.view == null) return@observeUserPoints
                    currentUserPoints = p
                    binding.userPointsTextview.text = "我的點數: $currentUserPoints"
                },
                onError = { msg ->
                    if (!isAdded || this@ShopFragment.view == null) return@observeUserPoints
                    Log.e("ShopFragment", "Failed to load user points: $msg")
                    requireContext().toast("載入點數失敗：$msg")
                }
            )
        }

        binding.confirmPurchaseButton.setThrottledClick { showPurchaseConfirmationDialog() }
        binding.clearCartButton.setThrottledClick { clearCart() }
    }

    override fun onDestroyView() {
        // ✅ 確實移除監聽，避免 View 銷毀後回呼觸發 UI 操作
        shopItemsHandle?.remove(); shopItemsHandle = null
        userPointsHandle?.remove(); userPointsHandle = null
        super.onDestroyView()
    }

    // ====== UI ======

    private fun displayShopItems() {
        val container = binding.shopItemsContainer
        container.removeAllViews()

        val itemsPerRow = 5
        var rowLayout: LinearLayout? = null

        val totalItems = shopItems.size
        val emptySlots = if (totalItems % itemsPerRow != 0) itemsPerRow - (totalItems % itemsPerRow) else 0

        (0 until totalItems + emptySlots).forEachIndexed { index, _ ->
            if (index % itemsPerRow == 0) {
                rowLayout = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, resources.getDimensionPixelSize(R.dimen.shop_row_margin_bottom))
                    }
                }
                container.addView(rowLayout)
            }

            if (index < totalItems) {
                val item = shopItems[index]
                val itemLayout = LayoutInflater.from(requireContext())
                    .inflate(R.layout.shop_item_template, rowLayout, false)

                itemLayout.layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )

                val productNameTextView: TextView = itemLayout.findViewById(R.id.product_name_textview)
                val priceTextView: TextView = itemLayout.findViewById(R.id.price_textview)
                val quantityEditText: EditText = itemLayout.findViewById(R.id.quantity_edittext)
                val decreaseButton: Button = itemLayout.findViewById(R.id.decrease_quantity_button)
                val increaseButton: Button = itemLayout.findViewById(R.id.increase_quantity_button)

                val name = item.productName ?: ""
                productNameTextView.text = name
                priceTextView.text = "價格：${item.price}點"

                val currentQ = (itemQuantities[name] ?: 0).coerceAtLeast(0)
                quantityEditText.setText(currentQ.toString())

                quantityEditText.addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        val newQuantity = s?.toString()?.toIntOrNull() ?: 0
                        itemQuantities[name] = newQuantity.coerceAtLeast(0)
                        updateTotalAmount()
                        displayPurchaseList()
                        displayBonusList() // 新增：更新贈送清單
                    }
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                })

                decreaseButton.setOnClickListener {
                    val q = (quantityEditText.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
                    quantityEditText.setText((q - 1).coerceAtLeast(0).toString())
                }
                increaseButton.setOnClickListener {
                    val q = (quantityEditText.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
                    quantityEditText.setText((q + 1).toString())
                }

                rowLayout?.addView(itemLayout)
            } else {
                rowLayout?.addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
        }

        updateTotalAmount()
        displayPurchaseList()
        displayBonusList() // 新增：顯示贈送清單
    }

    private fun displayPurchaseList() {
        val list = binding.purchaseListContainer
        list.removeAllViews()
        itemQuantities.forEach { (name, q) ->
            if (q > 0) {
                val tv = TextView(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    text = "$name $q 張"
                    textSize = 16f
                    setTextColor(ContextCompat.getColor(requireContext(), android.R.color.black))
                    setPadding(8.toPx(), 4.toPx(), 8.toPx(), 4.toPx())
                }
                list.addView(tv)
            }
        }
    }

    // 新增：顯示贈送清單（買10送10功能）
    private fun displayBonusList() {
        val bonusList = binding.bonusListContainer
        bonusList.removeAllViews()

        var hasBonusItems = false

        itemQuantities.forEach { (name, quantity) ->
            if (quantity >= 10) {
                val bonusQuantity = (quantity / 10) * 10 // 計算贈送數量
                if (bonusQuantity > 0) {
                    if (!hasBonusItems) {
                        // 添加贈送標題
                        val titleTv = TextView(requireContext()).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            text = "贈送："
                            textSize = 14f
                            setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark))
                            setPadding(8.toPx(), 8.toPx(), 8.toPx(), 4.toPx())
                            setTypeface(null, android.graphics.Typeface.BOLD)
                        }
                        bonusList.addView(titleTv)
                        hasBonusItems = true
                    }

                    val bonusTv = TextView(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        text = "$name ${bonusQuantity}張"
                        textSize = 14f
                        setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
                        setPadding(16.toPx(), 2.toPx(), 8.toPx(), 2.toPx())
                    }
                    bonusList.addView(bonusTv)
                }
            }
        }
    }

    private fun updateTotalAmount() {
        val total = calculateTotalAmount()
        binding.totalAmountTextview.text = "總計：${total}點"
    }

    private fun calculateTotalAmount(): Int {
        var sum = 0
        shopItems.forEach { item ->
            val name = item.productName ?: ""
            val q = itemQuantities[name] ?: 0
            sum += q * item.price
        }
        return sum
    }

    private fun clearCart() {
        itemQuantities.clear()
        displayShopItems()
        requireContext().toast("購物車已清空！")
    }

    private fun showPurchaseConfirmationDialog() = requireContext().guardOnline {
        val total = calculateTotalAmount()
        if (total <= 0) {
            requireContext().toast("您的購物車是空的，請先選擇商品！")
            return@guardOnline
        }

        // 建構確認訊息，包含贈送資訊
        val confirmMessage = buildConfirmationMessage(total)

        AlertDialog.Builder(requireContext())
            .setTitle("確認購買")
            .setMessage(confirmMessage)
            .setPositiveButton("確定") { d, _ -> d.dismiss(); confirmPurchase(total) }
            .setNegativeButton("取消", null)
            .show()
    }

    // 新增：建構確認訊息，包含購物車詳細資訊和贈送資訊
    private fun buildConfirmationMessage(total: Int): String {
        val sb = StringBuilder()

        // 添加購物車清單
        sb.append("購物車內容：\n")
        itemQuantities.forEach { (name, quantity) ->
            if (quantity > 0) {
                val item = shopItems.find { it.productName == name }
                val price = item?.price ?: 0
                val subtotal = quantity * price
                sb.append("• $name $quantity 張 = ${subtotal}點\n")
            }
        }

        sb.append("\n總計：$total 點數\n")

        // 檢查是否有贈送項目
        val bonusItems = mutableListOf<String>()
        itemQuantities.forEach { (name, quantity) ->
            if (quantity >= 10) {
                val bonusQuantity = (quantity / 10) * 10
                if (bonusQuantity > 0) {
                    bonusItems.add("$name ${bonusQuantity}張")
                }
            }
        }

        if (bonusItems.isNotEmpty()) {
            sb.append("\n同時您將獲得以下贈品：\n")
            bonusItems.forEach { item ->
                sb.append("• $item\n")
            }
        }

        sb.append("\n您確定要進行購買嗎？")

        return sb.toString().trim()
    }

    private fun confirmPurchase(totalAmount: Int) = requireContext().guardOnline {
        val userKey = userSessionProvider?.getCurrentUserFirebaseKey()
        if (userKey.isNullOrEmpty()) {
            requireContext().toast("無法完成購買：用戶未登入！")
            return@guardOnline
        }
        if (currentUserPoints < totalAmount) {
            requireContext().toast("您的點數不足，請先聯繫小編進行儲值，再進行購買。")
            return@guardOnline
        }

        // 準備購買資料，包含贈送項目
        val purchaseData = preparePurchaseData()

        repo.purchase(userKey, totalAmount, purchaseData) { ok, msg ->
            if (!isAdded || this@ShopFragment.view == null) return@purchase
            if (ok) {
                val bonusMessage = getBonusMessage()
                val successMessage = if (bonusMessage.isNotEmpty()) {
                    "購買成功！總計扣除 ${totalAmount}點。$bonusMessage"
                } else {
                    "購買成功！總計扣除 ${totalAmount}點。"
                }
                requireContext().toast(successMessage)
                itemQuantities.clear()
                displayShopItems()
            } else {
                when (msg) {
                    "購物車為空" -> requireContext().toast("您的購物車是空的，請先選擇商品！")
                    "點數不足"   -> requireContext().toast("您的點數不足，請先聯繫小編進行儲值，再進行購買。")
                    else          -> requireContext().toast("購買失敗：${msg ?: "請稍後再試"}")
                }
            }
        }
    }

    // 新增：準備購買資料，包含原購買數量和贈送數量
    private fun preparePurchaseData(): Map<String, Int> {
        val purchaseData = mutableMapOf<String, Int>()

        itemQuantities.forEach { (name, quantity) ->
            if (quantity > 0) {
                // 原本購買的數量
                var totalQuantity = quantity

                // 加上贈送數量（買10送10）
                if (quantity >= 10) {
                    val bonusQuantity = (quantity / 10) * 10
                    totalQuantity += bonusQuantity
                }

                purchaseData[name] = totalQuantity
            }
        }

        return purchaseData
    }

    // 新增：取得贈送訊息
    private fun getBonusMessage(): String {
        val bonusItems = mutableListOf<String>()
        itemQuantities.forEach { (name, quantity) ->
            if (quantity >= 10) {
                val bonusQuantity = (quantity / 10) * 10
                if (bonusQuantity > 0) {
                    bonusItems.add("$name ${bonusQuantity}張")
                }
            }
        }

        return if (bonusItems.isNotEmpty()) {
            "\n並獲得贈品：${bonusItems.joinToString("、")}"
        } else {
            ""
        }
    }

    private fun Int.toPx(): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), resources.displayMetrics
    ).toInt()
}