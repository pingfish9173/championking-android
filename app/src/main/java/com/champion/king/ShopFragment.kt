package com.champion.king

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.champion.king.core.ui.BaseBindingFragment
import com.champion.king.data.DbListenerHandle
import com.champion.king.data.ShopRepository
import com.champion.king.databinding.FragmentShopBinding
import com.champion.king.model.ShopItem
import com.champion.king.util.ToastManager
import com.champion.king.util.guardOnline
import com.champion.king.util.setThrottledClick

class ShopFragment : BaseBindingFragment<FragmentShopBinding>() {

    private var userSessionProvider: UserSessionProvider? = null

    private val repo by lazy { ShopRepository() }

    // 🌟 修改：變更為 Pair 結構以儲存節點名稱
    private var shopItems: List<Pair<String, ShopItem>> = emptyList()
    // 🌟 修改：購物車的 Key 現在是 節點名稱 (nodeKey) 而不是 productName
    private val itemQuantities = mutableMapOf<String, Int>()
    private var currentUserPoints: Int = 0

    private var shopItemsHandle: DbListenerHandle? = null
    private var userPointsHandle: DbListenerHandle? = null

    private var isRentalMode: Boolean = false
    private var hasShownRentalShopToast: Boolean = false

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is UserSessionProvider) userSessionProvider = context
        else throw RuntimeException("$context must implement UserSessionProvider")
    }

    override fun onDetach() {
        super.onDetach()
        userSessionProvider = null
    }

    override fun createBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentShopBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        shopItemsHandle = repo.observeShopItems(
            onItems = { items ->
                if (!isAdded || this@ShopFragment.view == null) return@observeShopItems
                shopItems = items
                displayShopItems()
            },
            onError = { msg ->
                if (!isAdded || this@ShopFragment.view == null) return@observeShopItems
                Log.e("ShopFragment", "Failed to load shop items: $msg")
                showToast("載入商品失敗：$msg")
            }
        )

        val userKey = userSessionProvider?.getCurrentUserFirebaseKey()
        if (userKey.isNullOrEmpty()) {
            binding.userPointsTextview.text = "我的點數: N/A"
            showToast("無法載入點數：用戶未登入")
        } else {
            loadBillingModeAndMaybeToast(userKey)
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
                    showToast("載入點數失敗：$msg")
                }
            )
        }

        setupLineTextWithUnderline()

        binding.confirmPurchaseButton.setThrottledClick {
            if (isRentalMode) {
                showToast("此帳號為租賃制，無提供商城服務")
                return@setThrottledClick
            }
            showPurchaseConfirmationDialog()
        }

        binding.clearCartButton.setThrottledClick { clearCart() }
    }

    private fun setupLineTextWithUnderline() {
        val fullText = "儲值請加入官方Line：\n@376xyozd"
        val underlinePart = "@376xyozd"
        val spannable = android.text.SpannableString(fullText)

        val start = fullText.indexOf(underlinePart)
        if (start != -1) {
            val end = start + underlinePart.length
            spannable.setSpan(
                android.text.style.UnderlineSpan(),
                start,
                end,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        binding.lineIdText.apply {
            text = spannable
            gravity = android.view.Gravity.CENTER
        }
    }

    override fun onDestroyView() {
        shopItemsHandle?.remove(); shopItemsHandle = null
        userPointsHandle?.remove(); userPointsHandle = null
        super.onDestroyView()
    }

    private fun loadBillingModeAndMaybeToast(userKey: String) {
        val ref = com.google.firebase.database.FirebaseDatabase.getInstance()
            .reference
            .child("users")
            .child(userKey)
            .child("billingMode")

        ref.addListenerForSingleValueEvent(object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val mode = snapshot.getValue(String::class.java) ?: "POINT"
                isRentalMode = (mode == "RENTAL")

                if (isRentalMode && !hasShownRentalShopToast && isAdded && view != null) {
                    hasShownRentalShopToast = true
                    showToast("此帳號為租賃制，無提供商城服務")
                }
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                Log.w("ShopFragment", "loadBillingMode failed: ${error.message}")
            }
        })
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
                val (nodeKey, item) = shopItems[index] // 🌟 解構出 nodeKey 與 item
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

                // 🌟 使用 nodeKey 來取值與存值
                val currentQ = (itemQuantities[nodeKey] ?: 0).coerceAtLeast(0)
                quantityEditText.setText(currentQ.toString())

                quantityEditText.isFocusable = false
                quantityEditText.isCursorVisible = false
                quantityEditText.setOnClickListener {
                    showQuantityInputDialog(nodeKey, name, quantityEditText)
                }

                decreaseButton.setOnClickListener {
                    val q = (quantityEditText.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
                    val newValue = (q - 1).coerceAtLeast(0)
                    itemQuantities[nodeKey] = newValue
                    quantityEditText.setText(newValue.toString())
                    updateTotalAmount()
                    displayPurchaseList()
                }

                increaseButton.setOnClickListener {
                    val q = (quantityEditText.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
                    val newValue = if (q >= 9999) {
                        showToast("單一商品數量上限為 9999")
                        9999
                    } else {
                        q + 1
                    }
                    itemQuantities[nodeKey] = newValue
                    quantityEditText.setText(newValue.toString())
                    updateTotalAmount()
                    displayPurchaseList()
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
    }

    private fun showQuantityInputDialog(nodeKey: String, productName: String, editText: EditText) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_quantity_input, null)

        val dialogEditText = dialogView.findViewById<EditText>(R.id.dialog_quantity_edit)
        val btnMinus = dialogView.findViewById<Button>(R.id.dialog_btn_minus)
        val btnPlus = dialogView.findViewById<Button>(R.id.dialog_btn_plus)
        val btnClear = dialogView.findViewById<Button>(R.id.dialog_btn_clear)
        val btnDelete = dialogView.findViewById<Button>(R.id.dialog_btn_delete)

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

        val currentQuantity = itemQuantities[nodeKey] ?: 0
        dialogEditText.setText(currentQuantity.toString())
        dialogEditText.setSelection(dialogEditText.text.length)

        dialogEditText.showSoftInputOnFocus = false

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("輸入 $productName 數量")
            .setView(dialogView)
            .setPositiveButton("確定") { d, _ ->
                val newQuantity = dialogEditText.text.toString().toIntOrNull() ?: 0
                itemQuantities[nodeKey] = newQuantity.coerceIn(0, 9999)
                editText.setText(itemQuantities[nodeKey].toString())
                updateTotalAmount()
                displayPurchaseList()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .create()

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
                    showToast("單一商品數量上限為 9999")
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

        btnMinus.setOnClickListener {
            val current = dialogEditText.text.toString().toIntOrNull() ?: 0
            val newValue = (current - 1).coerceAtLeast(0)
            dialogEditText.setText(newValue.toString())
            dialogEditText.setSelection(dialogEditText.text.length)
        }

        btnPlus.setOnClickListener {
            val current = dialogEditText.text.toString().toIntOrNull() ?: 0
            val newValue = (current + 1).coerceAtMost(9999)
            if (newValue >= 9999) {
                showToast("單一商品數量上限為 9999")
            }
            dialogEditText.setText(newValue.toString())
            dialogEditText.setSelection(dialogEditText.text.length)
        }

        btnClear.setOnClickListener {
            dialogEditText.setText("0")
            dialogEditText.setSelection(dialogEditText.text.length)
        }

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
        dialogEditText.setOnClickListener {}
    }

    private fun displayPurchaseList() {
        val list = binding.purchaseListContainer
        list.removeAllViews()
        itemQuantities.forEach { (nodeKey, q) ->
            if (q > 0) {
                // 🌟 從 shopItems 裡找出對應的商品名稱來顯示
                val item = shopItems.find { it.first == nodeKey }?.second
                val name = item?.productName ?: nodeKey

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

    private fun updateTotalAmount() {
        val total = calculateTotalAmount()
        binding.totalAmountTextview.text = "總計：${total}點"
    }

    private fun calculateTotalAmount(): Int {
        var sum = 0
        shopItems.forEach { (nodeKey, item) ->
            val q = itemQuantities[nodeKey] ?: 0
            sum += q * item.price
        }
        return sum
    }

    private fun clearCart() {
        itemQuantities.clear()
        displayShopItems()
        showToast("購物車已清空！")
    }

    private fun showPurchaseConfirmationDialog() = requireContext().guardOnline {
        val total = calculateTotalAmount()
        if (total <= 0) {
            showToast("您的購物車是空的，請先選擇商品！")
            return@guardOnline
        }

        val confirmMessage = buildConfirmationMessage(total)

        AlertDialog.Builder(requireContext())
            .setTitle("確認購買")
            .setMessage(confirmMessage)
            .setPositiveButton("確定") { d, _ -> d.dismiss(); confirmPurchase(total) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildConfirmationMessage(total: Int): String {
        val sb = StringBuilder()
        sb.append("購物車內容：\n")
        itemQuantities.forEach { (nodeKey, quantity) ->
            if (quantity > 0) {
                val item = shopItems.find { it.first == nodeKey }?.second
                val name = item?.productName ?: nodeKey
                val price = item?.price ?: 0
                val subtotal = quantity * price
                sb.append("• $name $quantity 張 = ${subtotal}點\n")
            }
        }
        sb.append("\n總計：$total 點數\n")
        sb.append("\n您確定要進行購買嗎？")

        return sb.toString().trim()
    }

    private fun confirmPurchase(totalAmount: Int) = requireContext().guardOnline {
        if (isRentalMode) {
            showToast("此帳號為租賃制，無提供商城服務")
            return@guardOnline
        }

        val userKey = userSessionProvider?.getCurrentUserFirebaseKey()
        if (userKey.isNullOrEmpty()) {
            showToast("無法完成購買：用戶未登入！")
            return@guardOnline
        }
        if (currentUserPoints < totalAmount) {
            showToast("您的點數不足，請先聯繫小編進行儲值，再進行購買。")
            return@guardOnline
        }

        val purchaseData = preparePurchaseData()
        val purchaseDetails = preparePurchaseDetailsForRecord()
        val itemPrices = prepareItemPrices()

        repo.getUserAccount(userKey) { success, account ->
            if (!isAdded || this@ShopFragment.view == null) return@getUserAccount

            val username = account ?: userKey

            repo.purchase(userKey, totalAmount, purchaseData) { ok, msg ->
                if (!isAdded || this@ShopFragment.view == null) return@purchase
                if (ok) {
                    repo.savePurchaseRecord(
                        userKey = userKey,
                        username = username,
                        totalPoints = totalAmount,
                        purchaseDetails = purchaseDetails,
                        itemPrices = itemPrices
                    ) { recordSuccess, recordMsg ->
                        if (!recordSuccess) {
                            Log.e("ShopFragment", "保存購買紀錄失敗：$recordMsg")
                        }
                    }

                    val successMessage = "購買成功！總計扣除 ${totalAmount}點。"
                    showToast(successMessage)
                    itemQuantities.clear()
                    displayShopItems()
                } else {
                    when (msg) {
                        "購物車為空" -> showToast("您的購物車是空的，請先選擇商品！")
                        "點數不足"   -> showToast("您的點數不足，請先聯繫小編進行儲值，再進行購買。")
                        else          -> showToast("購買失敗：${msg ?: "請稍後再試"}")
                    }
                }
            }
        }
    }

    // 紀錄購買明細時，存入實際顯示的 productName
    private fun preparePurchaseDetailsForRecord(): Map<String, Pair<Int, Int>> {
        val details = mutableMapOf<String, Pair<Int, Int>>()
        itemQuantities.forEach { (nodeKey, quantity) ->
            if (quantity > 0) {
                val item = shopItems.find { it.first == nodeKey }?.second
                val name = item?.productName ?: nodeKey
                details[name] = Pair(quantity, 0)
            }
        }
        return details
    }

    // 商品單價也用 productName 當 Key 存入購買紀錄
    private fun prepareItemPrices(): Map<String, Int> {
        val prices = mutableMapOf<String, Int>()
        shopItems.forEach { (_, item) ->
            val name = item.productName ?: ""
            if (name.isNotEmpty()) {
                prices[name] = item.price
            }
        }
        return prices
    }

    // 🌟 準備傳給 repo.purchase 的 Map (Key 必須是 nodeKey)
    private fun preparePurchaseData(): Map<String, Int> {
        val purchaseData = mutableMapOf<String, Int>()
        itemQuantities.forEach { (nodeKey, quantity) ->
            if (quantity > 0) {
                purchaseData[nodeKey] = quantity
            }
        }
        return purchaseData
    }

    private fun Int.toPx(): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), resources.displayMetrics
    ).toInt()

    private fun showToast(message: String) {
        activity?.let {
            ToastManager.show(it, message)
        }
    }
}