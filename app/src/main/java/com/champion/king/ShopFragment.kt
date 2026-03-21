package com.champion.king

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
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

        // 🌟 當使用者點擊右上角 QRCode 區域時 (id: shop_activity_zone)
        binding.shopActivityZone.setOnClickListener {
            // 呼叫上方實作的彈窗函式
            showLargeQrCodeDialog()
        }

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

    /**
     * ✅ 實作商城優化 (修正版 4.0)：聚焦模式全螢幕暗化背景
     *
     * 需求：
     * 1. 背景呈現整個暗掉的狀態（類似聚焦模式）。
     * 2. 解決右邊切線不乾淨的問題：透過全螢幕透明主題與沉浸式 UI 標籤，強制 Dialog 覆蓋邊緣。
     * 3. 點擊卡片以外任意位置關閉彈窗。
     */
    private fun showLargeQrCodeDialog() {
        val context = requireContext()
        // 🌟 關鍵修正 1：使用全螢幕透明主題，確保 Dialog 視窗本身佔滿整個物理螢幕
        val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        // Helper Function: DP 轉 PX
        fun dpToPx(dp: Int): Int {
            val density = context.resources.displayMetrics.density
            return (dp * density).toInt()
        }

        // 1️⃣ 根佈局 (手動控制暗化背景)
        val rootLayout = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // 🌟 關鍵修正 2：手動設定半透明黑色背景 (約 65% 黑)，模擬聚焦模式
            setBackgroundColor(Color.parseColor("#A6000000"))
        }

        // 2️⃣ 內容容器 (極簡白色圓角卡片，浮動置中)
        val contentCard = FrameLayout(context).apply {
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
            layoutParams = params

            // 內部間距設為相同，讓 QRCode 置中且精緻
            val p = dpToPx(32)
            setPadding(p, p, p, p)

            elevation = dpToPx(12).toFloat() // 保持陰影深度，加強浮動立體感

            // 保持卡片圓角 (16dp)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                setCornerRadius(dpToPx(16).toFloat())
            }

            // 防止點擊卡片內部時穿透到根佈局導致彈窗關閉
            isClickable = true
            isFocusable = true
        }
        rootLayout.addView(contentCard)

        // 3️⃣ QRCode 圖片 (直接置中於卡片中)
        val imageView = ImageView(context).apply {
            val size = dpToPx(260)
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = android.view.Gravity.CENTER
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            // 使用原本 fragment_shop.xml 中定義的 Demo 圖片
            setImageResource(R.drawable.line_qr)
        }
        contentCard.addView(imageView)

        dialog.setContentView(rootLayout)

        // 4️⃣ 設置 Dialog 視窗參數
        dialog.window?.let { window ->
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            // 拔除系統預設的變暗效果，統一由我們的 rootLayout 背景色來控管
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setWindowAnimations(android.R.style.Animation_Dialog)

            // 🌟 關鍵修正 3：開啟沉浸式模式，將導覽列與狀態列隱藏，徹底消滅邊緣切線問題
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }

        // 5️⃣ 設置點擊邏輯：點擊卡片以外任意位置（即半透明暗色區域）關閉彈窗
        rootLayout.setOnClickListener {
            dialog.dismiss()
        }

        // 顯示 Dialog
        dialog.show()
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

        // 利用節點名稱 (nodeKey) 是否包含 "x" 或 "X" 來過濾商品
        val singleBoardItems = shopItems.filter { !it.first.contains("x", ignoreCase = true) }
        val splitBoardItems = shopItems.filter { it.first.contains("x", ignoreCase = true) }

        // 顯示單一版面區塊
        if (singleBoardItems.isNotEmpty()) {
            addCategoryToContainer(container, "單一版面：", singleBoardItems)
        }

        // 如果有分割版面的商品，接著顯示分割版面區塊
        if (splitBoardItems.isNotEmpty()) {
            addCategoryToContainer(container, "分割版面：", splitBoardItems)
        }

        updateTotalAmount()
        displayPurchaseList()
    }

    private fun addCategoryToContainer(
        container: LinearLayout,
        title: String,
        items: List<Pair<String, ShopItem>>
    ) {
        val titleTextView = TextView(requireContext()).apply {
            text = title
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), android.R.color.black))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // 設定標題上下的留白
                setMargins(0, 8.toPx(), 0, 8.toPx())
            }
        }
        container.addView(titleTextView)

        val itemsPerRow = 5
        var rowLayout: LinearLayout? = null

        val totalItems = items.size
        val emptySlots = if (totalItems % itemsPerRow != 0) itemsPerRow - (totalItems % itemsPerRow) else 0

        (0 until totalItems + emptySlots).forEachIndexed { index, _ ->
            // 每 5 個商品建立一個新的水平 LinearLayout (Row)
            if (index % itemsPerRow == 0) {
                rowLayout = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ) // 外層 Row 不設定 Margin，交給裡面的商品卡片去互相推開
                }
                container.addView(rowLayout)
            }

            if (index < totalItems) {
                val (nodeKey, item) = items[index]
                val itemLayout = LayoutInflater.from(requireContext())
                    .inflate(R.layout.shop_item_template, rowLayout, false)

                // 🌟 關鍵修改：在這裡透過程式碼強制設定卡片的 Margin
                itemLayout.layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    // 設定 8dp 的外距 (左右相鄰的卡片會被推開 16dp 的空隙)
                    // 如果覺得不夠開，可以把 8 改成 10 或 12
                    val margin = 8.toPx()
                    setMargins(margin, margin, margin, margin)
                }

                val productNameTextView: TextView = itemLayout.findViewById(R.id.product_name_textview)
                val priceTextView: TextView = itemLayout.findViewById(R.id.price_textview)
                val quantityEditText: EditText = itemLayout.findViewById(R.id.quantity_edittext)
                val decreaseButton: Button = itemLayout.findViewById(R.id.decrease_quantity_button)
                val increaseButton: Button = itemLayout.findViewById(R.id.increase_quantity_button)

                val name = item.productName ?: ""
                productNameTextView.text = name
                priceTextView.text = "價格：${item.price}點"

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
                // 🌟 關鍵修改：用來排版佔位的透明空 View，也要設定一模一樣的 Margin，否則排版會歪掉
                rowLayout?.addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply {
                        val margin = 8.toPx()
                        setMargins(margin, margin, margin, margin)
                    }
                })
            }
        }
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