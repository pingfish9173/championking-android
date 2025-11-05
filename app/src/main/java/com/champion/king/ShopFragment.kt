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

                // 🔹 方案三：禁用直接輸入，改用對話框
                quantityEditText.isFocusable = false
                quantityEditText.isCursorVisible = false
                quantityEditText.setOnClickListener {
                    showQuantityInputDialog(name, quantityEditText)
                }

                // 保留 +/- 按鈕功能
                decreaseButton.setOnClickListener {
                    val q = (quantityEditText.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
                    val newValue = (q - 1).coerceAtLeast(0)
                    itemQuantities[name] = newValue
                    quantityEditText.setText(newValue.toString())
                    updateTotalAmount()
                    displayPurchaseList()
                    displayBonusList()
                }

                increaseButton.setOnClickListener {
                    val q = (quantityEditText.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
                    val newValue = if (q >= 9999) {
                        requireContext().toast("單一商品數量上限為 9999")
                        9999
                    } else {
                        q + 1
                    }
                    itemQuantities[name] = newValue
                    quantityEditText.setText(newValue.toString())
                    updateTotalAmount()
                    displayPurchaseList()
                    displayBonusList()
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
        displayBonusList()
    }

    // 🔹 方案三：顯示自定義數字鍵盤對話框
    private fun showQuantityInputDialog(productName: String, editText: EditText) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_quantity_input, null)

        val dialogEditText = dialogView.findViewById<EditText>(R.id.dialog_quantity_edit)
        val btnMinus = dialogView.findViewById<Button>(R.id.dialog_btn_minus)
        val btnPlus = dialogView.findViewById<Button>(R.id.dialog_btn_plus)
        val btnClear = dialogView.findViewById<Button>(R.id.dialog_btn_clear)
        val btnDelete = dialogView.findViewById<Button>(R.id.dialog_btn_delete)

        // 數字按鈕 0-9
        val btn0 = dialogView.findViewById<Button>(R.id.dialog_btn_0)
        val btn1 = dialogView.findViewById<Button>(R.id.dialog_btn_1)
        val btn2 = dialogView.findViewById<Button>(R.id.dialog_btn_2)
        val btn3 = dialogView.findViewById<Button>(R.id.dialog_btn_3)
        val btn4 = dialogView.findViewById<Button>(R.id.dialog_btn_4)
        val btn5 = dialogView.findViewById<Button>(R.id.dialog_btn_5)
        val btn6 = dialogView.findViewById<Button>(R.id.dialog_btn_6)
        val btn7 = dialogView.findViewById<Button>(R.id.dialog_btn_7)
        val btn8 = dialogView.findViewById<Button>(R.id.dialog_btn_8)
        val btn9 = dialogView.findViewById<Button>(R.id.dialog_btn_9)

        // 設置當前數量
        val currentQuantity = itemQuantities[productName] ?: 0
        dialogEditText.setText(currentQuantity.toString())
        dialogEditText.setSelection(dialogEditText.text.length)

        // 禁用系統鍵盤
        dialogEditText.showSoftInputOnFocus = false

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("輸入 $productName 數量")
            .setView(dialogView)
            .setPositiveButton("確定") { d, _ ->
                val newQuantity = dialogEditText.text.toString().toIntOrNull() ?: 0
                itemQuantities[productName] = newQuantity.coerceIn(0, 9999)
                editText.setText(itemQuantities[productName].toString())
                updateTotalAmount()
                displayPurchaseList()
                displayBonusList()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .create()

        // 數字按鈕點擊事件
        val numberClickListener = View.OnClickListener { view ->
            val button = view as Button
            val number = button.text.toString()
            val currentText = dialogEditText.text.toString()
            val currentValue = if (currentText == "0") "" else currentText
            val newText = "$currentValue$number"

            if (newText.length <= 4) {
                val newValue = newText.toIntOrNull() ?: 0
                if (newValue <= 9999) {
                    dialogEditText.setText(newText)
                    dialogEditText.setSelection(dialogEditText.text.length)
                } else {
                    requireContext().toast("單一商品數量上限為 9999")
                }
            }
        }

        btn0.setOnClickListener(numberClickListener)
        btn1.setOnClickListener(numberClickListener)
        btn2.setOnClickListener(numberClickListener)
        btn3.setOnClickListener(numberClickListener)
        btn4.setOnClickListener(numberClickListener)
        btn5.setOnClickListener(numberClickListener)
        btn6.setOnClickListener(numberClickListener)
        btn7.setOnClickListener(numberClickListener)
        btn8.setOnClickListener(numberClickListener)
        btn9.setOnClickListener(numberClickListener)

        // 減少按鈕
        btnMinus.setOnClickListener {
            val current = dialogEditText.text.toString().toIntOrNull() ?: 0
            val newValue = (current - 1).coerceAtLeast(0)
            dialogEditText.setText(newValue.toString())
            dialogEditText.setSelection(dialogEditText.text.length)
        }

        // 增加按鈕
        btnPlus.setOnClickListener {
            val current = dialogEditText.text.toString().toIntOrNull() ?: 0
            val newValue = (current + 1).coerceAtMost(9999)
            if (newValue >= 9999) {
                requireContext().toast("單一商品數量上限為 9999")
            }
            dialogEditText.setText(newValue.toString())
            dialogEditText.setSelection(dialogEditText.text.length)
        }

        // 清除按鈕
        btnClear.setOnClickListener {
            dialogEditText.setText("0")
            dialogEditText.setSelection(dialogEditText.text.length)
        }

        // 刪除按鈕（退格）
        btnDelete.setOnClickListener {
            val currentText = dialogEditText.text.toString()
            if (currentText.isNotEmpty()) {
                val newText = if (currentText.length > 1) {
                    currentText.substring(0, currentText.length - 1)
                } else {
                    "0"
                }
                dialogEditText.setText(newText)
                dialogEditText.setSelection(dialogEditText.text.length)
            }
        }

        dialog.show()

        // 點擊 EditText 時也禁用系統鍵盤
        dialogEditText.setOnClickListener {
            // 不做任何事，阻止系統鍵盤彈出
        }
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
                    setPadding(4.toPx(), 2.toPx(), 4.toPx(), 2.toPx())
                }
                list.addView(tv)
            }
        }
    }

    private fun displayBonusList() {
        val list = binding.bonusListContainer
        list.removeAllViews()

        val hasBonusTitle = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "🎁 贈送"
            textSize = 14f
            setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(4.toPx(), 4.toPx(), 4.toPx(), 2.toPx())
        }

        var hasBonus = false
        itemQuantities.forEach { (name, quantity) ->
            if (quantity >= 10) {
                val bonusQuantity = (quantity / 10) * 10
                if (bonusQuantity > 0) {
                    if (!hasBonus) {
                        list.addView(hasBonusTitle)
                        hasBonus = true
                    }
                    val tv = TextView(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        text = "$name ${bonusQuantity}張"
                        textSize = 14f
                        setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                        setPadding(4.toPx(), 2.toPx(), 4.toPx(), 2.toPx())
                    }
                    list.addView(tv)
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

        // ✨ 新增：準備購買紀錄資料
        val purchaseDetails = preparePurchaseDetailsForRecord()
        val itemPrices = prepareItemPrices()

        // ✨ 修改：先獲取用戶帳號
        repo.getUserAccount(userKey) { success, account ->
            if (!isAdded || this@ShopFragment.view == null) return@getUserAccount

            val username = account ?: userKey  // 如果獲取失敗，使用 userKey 作為備用

            // 執行購買
            repo.purchase(userKey, totalAmount, purchaseData) { ok, msg ->
                if (!isAdded || this@ShopFragment.view == null) return@purchase
                if (ok) {
                    // ✨ 新增：購買成功後保存購買紀錄
                    repo.savePurchaseRecord(
                        userKey = userKey,
                        username = username,  // 使用從 Firebase 獲取的帳號
                        totalPoints = totalAmount,
                        purchaseDetails = purchaseDetails,
                        itemPrices = itemPrices
                    ) { recordSuccess, recordMsg ->
                        if (!recordSuccess) {
                            Log.e("ShopFragment", "保存購買紀錄失敗：$recordMsg")
                        }
                    }

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
    }

    // ✨ 新增：準備購買詳細資料用於紀錄（分離購買數量和贈送數量）
    private fun preparePurchaseDetailsForRecord(): Map<String, Pair<Int, Int>> {
        val details = mutableMapOf<String, Pair<Int, Int>>()

        itemQuantities.forEach { (name, quantity) ->
            if (quantity > 0) {
                val bonusQuantity = if (quantity >= 10) {
                    (quantity / 10) * 10
                } else {
                    0
                }
                details[name] = Pair(quantity, bonusQuantity)
            }
        }

        return details
    }

    // ✨ 新增：準備商品單價資料
    private fun prepareItemPrices(): Map<String, Int> {
        val prices = mutableMapOf<String, Int>()

        shopItems.forEach { item ->
            val name = item.productName ?: ""
            if (name.isNotEmpty()) {
                prices[name] = item.price
            }
        }

        return prices
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