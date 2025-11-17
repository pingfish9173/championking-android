package com.champion.king.ui.settings

import android.R
import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import com.champion.king.ScratchBoardPreviewFragment
import com.champion.king.constants.ScratchCardConstants
import com.champion.king.databinding.FragmentSettingsBinding
import com.champion.king.model.NumberConfiguration
import com.champion.king.model.ScratchCard

class SettingsUIManager(
    private val binding: FragmentSettingsBinding,
    private val context: Context,
    private val childFragmentManager: FragmentManager
) {
    var currentScratchBoardPreviewFragment: ScratchBoardPreviewFragment? = null
        private set

    private var isSpinnerProgrammaticChange = false

    // ✅ 新增：大獎數量限制表
    private val GRAND_LIMITS = mapOf(
        10 to 3,
        20 to 5,
        25 to 6,
        30 to 6,
        40 to 8,
        50 to 8,
        60 to 10,
        80 to 10,
        100 to 10,
        120 to 12,
        160 to 12,
        200 to 15,
        240 to 15
    )

    fun setupSpinners() {
        setupScratchTypesSpinner()
        setupNumberSpinners()
    }

    private fun setupScratchTypesSpinner() {
        binding.spinnerScratchesCount.adapter = ArrayAdapter(
            context,
            R.layout.simple_spinner_item,
            ScratchCardConstants.SCRATCH_TYPES_LIST
        ).apply {
            setDropDownViewResource(R.layout.simple_spinner_dropdown_item)
        }

        binding.spinnerScratchesCount.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val selectedType = ScratchCardConstants.SCRATCH_TYPES_LIST[pos]
                if (!isSpinnerProgrammaticChange) {
                    loadScratchBoardPreview(selectedType, null)
                } else {
                    isSpinnerProgrammaticChange = false
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupNumberSpinners() {
        val numberAdapter = ArrayAdapter(
            context,
            R.layout.simple_spinner_item,
            ScratchCardConstants.NUMBER_OPTIONS
        ).apply {
            setDropDownViewResource(R.layout.simple_spinner_dropdown_item)
        }

        binding.spinnerClawsCount.adapter = numberAdapter
        binding.spinnerGiveawayCount.adapter = numberAdapter
    }

    fun displayScratchCardDetails(card: ScratchCard?) {
        if (card != null) {
            populateUIWithCardData(card)
            loadScratchBoardPreview(
                ScratchCardConstants.SCRATCH_COUNT_MAP[card.scratchesType] ?: ScratchCardConstants.DEFAULT_SCRATCH_TYPE,
                card.numberConfigurations
            )
        } else {
            clearUI()
            loadScratchBoardPreview(ScratchCardConstants.DEFAULT_SCRATCH_TYPE, null)
        }
    }

    private fun populateUIWithCardData(card: ScratchCard) {
        val typeString = ScratchCardConstants.SCRATCH_COUNT_MAP[card.scratchesType] ?: ScratchCardConstants.DEFAULT_SCRATCH_TYPE

        isSpinnerProgrammaticChange = true
        binding.spinnerScratchesCount.setSelection(
            ScratchCardConstants.SCRATCH_TYPES_LIST.indexOf(typeString).coerceAtLeast(0)
        )

        binding.editTextSpecialPrize.setText(card.specialPrize ?: "")
        binding.editTextGrandPrize.setText(card.grandPrize ?: "")

        binding.spinnerClawsCount.setSelection(
            ScratchCardConstants.NUMBER_OPTIONS.indexOf(card.clawsCount?.toString() ?: "1").coerceAtLeast(0)
        )
        binding.spinnerGiveawayCount.setSelection(
            ScratchCardConstants.NUMBER_OPTIONS.indexOf(card.giveawayCount?.toString() ?: "1").coerceAtLeast(0)
        )
    }

    private fun clearUI() {
        isSpinnerProgrammaticChange = true
        binding.spinnerScratchesCount.setSelection(
            ScratchCardConstants.SCRATCH_TYPES_LIST.indexOf(ScratchCardConstants.DEFAULT_SCRATCH_TYPE)
        )
        binding.editTextSpecialPrize.setText("")
        binding.editTextGrandPrize.setText("")
        binding.spinnerClawsCount.setSelection(0)
        binding.spinnerGiveawayCount.setSelection(0)
    }

    fun loadScratchBoardPreview(
        scratchesType: String,
        numberConfigurations: List<NumberConfiguration>?
    ) {
        val fragment = if (!numberConfigurations.isNullOrEmpty()) {
            ScratchBoardPreviewFragment.newInstance(scratchesType, numberConfigurations)
        } else {
            ScratchBoardPreviewFragment.newInstance(scratchesType)
        }

        currentScratchBoardPreviewFragment = fragment
        childFragmentManager.beginTransaction()
            .replace(binding.scratchBoardArea.id, fragment)
            .commitAllowingStateLoss()
    }

    fun updateInUseButtonUI(card: ScratchCard?) {
        if (card == null) {
            binding.buttonToggleInuse.isEnabled = false
            binding.buttonToggleInuse.text = "無資料可設定"
            return
        }

        binding.buttonToggleInuse.isEnabled = true
        binding.buttonToggleInuse.text = if (card.inUsed) "改為未使用" else "設為使用中（★）"
    }

    fun updateActionButtonsUI(card: ScratchCard?) {
        val hasData = card?.serialNumber != null
        val notInUse = card?.inUsed != true
        binding.buttonReturnSelected.isEnabled = hasData && notInUse
        binding.buttonDeleteSelected.isEnabled = hasData && notInUse
    }

    // ✅ 新增：顯示特獎數字鍵盤對話框
    fun showSpecialPrizeKeyboard(
        currentValue: String?,
        currentScratchType: Int,
        onConfirm: (String) -> Unit
    ) {
        showPrizeKeyboard(
            title = "輸入特獎數字",
            currentValue = currentValue,
            currentScratchType = currentScratchType,
            isSpecialPrize = true,
            onConfirm = onConfirm
        )
    }

    // ✅ 新增：顯示大獎數字鍵盤對話框
    fun showGrandPrizeKeyboard(
        currentValue: String?,
        currentScratchType: Int,
        onConfirm: (String) -> Unit
    ) {
        showPrizeKeyboard(
            title = "輸入大獎數字",
            currentValue = currentValue,
            currentScratchType = currentScratchType,
            isSpecialPrize = false,
            onConfirm = onConfirm
        )
    }

    // ✅ 新增：通用的獎項數字鍵盤對話框
    private fun showPrizeKeyboard(
        title: String,
        currentValue: String?,
        currentScratchType: Int,
        isSpecialPrize: Boolean,
        onConfirm: (String) -> Unit
    ) {
        val dialogView = LayoutInflater.from(context)
            .inflate(com.champion.king.R.layout.dialog_prize_number_input, null)

        val dialogEditText = dialogView.findViewById<EditText>(com.champion.king.R.id.dialog_prize_edit)
        val btnClear = dialogView.findViewById<Button>(com.champion.king.R.id.dialog_prize_btn_clear)
        val btnDelete = dialogView.findViewById<Button>(com.champion.king.R.id.dialog_prize_btn_delete)
        val btnComma = dialogView.findViewById<Button>(com.champion.king.R.id.dialog_prize_btn_comma)

        // 數字按鈕 0-9
        val btn0 = dialogView.findViewById<Button>(com.champion.king.R.id.dialog_prize_btn_0)
        val btn1 = dialogView.findViewById<Button>(com.champion.king.R.id.dialog_prize_btn_1)
        val btn2 = dialogView.findViewById<Button>(com.champion.king.R.id.dialog_prize_btn_2)
        val btn3 = dialogView.findViewById<Button>(com.champion.king.R.id.dialog_prize_btn_3)
        val btn4 = dialogView.findViewById<Button>(com.champion.king.R.id.dialog_prize_btn_4)
        val btn5 = dialogView.findViewById<Button>(com.champion.king.R.id.dialog_prize_btn_5)
        val btn6 = dialogView.findViewById<Button>(com.champion.king.R.id.dialog_prize_btn_6)
        val btn7 = dialogView.findViewById<Button>(com.champion.king.R.id.dialog_prize_btn_7)
        val btn8 = dialogView.findViewById<Button>(com.champion.king.R.id.dialog_prize_btn_8)
        val btn9 = dialogView.findViewById<Button>(com.champion.king.R.id.dialog_prize_btn_9)

        // 設置當前值
        dialogEditText.setText(currentValue ?: "")
        dialogEditText.setSelection(dialogEditText.text.length)

        // 禁用系統鍵盤
        dialogEditText.showSoftInputOnFocus = false

        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton("確定", null)  // ✅ 先設為 null，稍後手動設置
            .setNegativeButton("取消", null)
            .create()

        // ✅ 關鍵：在對話框顯示後手動設置「確定」按鈕的點擊事件
        // 這樣可以控制是否關閉對話框
        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val inputValue = dialogEditText.text.toString().trim()

                // 驗證輸入
                val validationResult = validatePrizeInput(
                    inputValue,
                    currentScratchType,
                    isSpecialPrize
                )

                if (validationResult.isValid) {
                    // ✅ 驗證通過：執行回調並關閉對話框
                    onConfirm(inputValue)
                    dialog.dismiss()
                } else {
                    // ✅ 驗證失敗：顯示錯誤訊息但不關閉對話框
                    Toast.makeText(context, validationResult.errorMessage, Toast.LENGTH_LONG).show()
                    // 不調用 dialog.dismiss()，對話框保持開啟
                    // 輸入內容也不會被清空，用戶可以繼續修改
                }
            }
        }

        // 數字按鈕點擊事件：依游標位置插入數字（若有選取則取代選取範圍）
        val numberClickListener = View.OnClickListener { view ->
            val button = view as Button
            val number = button.text.toString()

            val editable = dialogEditText.text
            if (editable == null) return@OnClickListener

            var start = dialogEditText.selectionStart
            var end = dialogEditText.selectionEnd

            // 若游標位置不合法，退回到「加在最後面」的行為
            if (start == -1 || end == -1) {
                editable.append(number)
                dialogEditText.setSelection(editable.length)
                return@OnClickListener
            }

            if (start != end) {
                // 有選取範圍：直接用數字取代選取區間
                editable.replace(start, end, number)
                val newPos = (start + number.length).coerceAtMost(editable.length)
                dialogEditText.setSelection(newPos)
            } else {
                // 沒選取：在游標位置插入數字
                editable.insert(start, number)
                val newPos = (start + number.length).coerceAtMost(editable.length)
                dialogEditText.setSelection(newPos)
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

        // 逗號按鈕：依游標位置插入 ","（若有選取則覆蓋選取區）
// ⭐ 已加入：大獎最大數量限制檢查（依目前刮數）
        btnComma.setOnClickListener {

            if (isSpecialPrize) {
                Toast.makeText(context, "特獎只能輸入一個數字", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val editable = dialogEditText.text
            if (editable == null) return@setOnClickListener

            // 取得目前已輸入的大獎（過濾掉空白與非法 token）
            val currentTokens = editable.toString()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            // ⭐ 大獎數量上限（依目前刮數類型）
            val grandLimit = GRAND_LIMITS[currentScratchType] ?: 20

            // 若已達上限 → 阻擋逗號輸入
            if (currentTokens.size >= grandLimit) {
                Toast.makeText(context, "大獎最多只能設定 $grandLimit 個", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            var start = dialogEditText.selectionStart
            var end = dialogEditText.selectionEnd

            // 若游標位置錯誤 → 加在最後（安全 fallback）
            if (start == -1 || end == -1) {
                if (editable.isNotEmpty() && !editable.endsWith(",")) {
                    editable.append(",")
                    dialogEditText.setSelection(editable.length)
                }
                return@setOnClickListener
            }

            // 不允許開頭輸入逗號
            if (start == 0) {
                Toast.makeText(context, "逗號不能放在開頭", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 不允許連續逗號
            if (start > 0 && editable[start - 1] == ',') {
                Toast.makeText(context, "不能連續輸入逗號", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (start != end) {
                // ✔ 覆蓋選取範圍
                editable.replace(start, end, ",")
                val newPos = (start + 1).coerceAtMost(editable.length)
                dialogEditText.setSelection(newPos)

            } else {
                // ✔ 在游標位置插入逗號
                editable.insert(start, ",")
                val newPos = (start + 1).coerceAtMost(editable.length)
                dialogEditText.setSelection(newPos)
            }
        }


        // 清除按鈕
        btnClear.setOnClickListener {
            dialogEditText.setText("")
        }

        // 退格按鈕：從「游標位置」退格，而不是永遠從最後一個字
        btnDelete.setOnClickListener {
            val editable = dialogEditText.text
            if (editable.isNullOrEmpty()) return@setOnClickListener

            val start = dialogEditText.selectionStart
            val end = dialogEditText.selectionEnd

            if (start == -1 || end == -1) {
                // 沒有有效游標位置，就沿用舊行為：從最後一個字刪
                editable.delete(editable.length - 1, editable.length)
                dialogEditText.setSelection(editable.length)
                return@setOnClickListener
            }

            if (start != end) {
                // ✂ 若有選取一段範圍，直接刪掉選取區間
                editable.delete(start, end)
                dialogEditText.setSelection(start.coerceAtMost(editable.length))
            } else if (start > 0) {
                // ✂ 沒有選取，只在中間 -> 刪除「游標前一個字」
                editable.delete(start - 1, start)
                dialogEditText.setSelection((start - 1).coerceAtMost(editable.length))
            }
        }

        dialog.show()

        // 點擊 EditText 時也禁用系統鍵盤
        dialogEditText.setOnClickListener {
            // 不做任何事,阻止系統鍵盤彈出
        }
    }

    // ✅ 新增：驗證獎項輸入
    /**
     * 驗證數字鍵盤輸入的特獎/大獎數字是否有效
     *
     * @param inputValue 使用者輸入的字串（例如 "03, 15, 7"）
     * @param maxNumber  目前刮數設定的最大格子數，例如 240
     * @param isSpecialPrize true=特獎, false=大獎
     * @return ValidationResult(isValid, errorMessage)
     */
    private fun validatePrizeInput(
        inputValue: String,
        maxNumber: Int,
        isSpecialPrize: Boolean
    ): ValidationResult {

        // 1️⃣ 空值
        if (inputValue.isBlank()) {
            return ValidationResult(false, "請輸入數字")
        }

        // 2️⃣ 不可有空 token（避免 "5,,5"）
        val rawTokens = inputValue.split(",")
        if (rawTokens.any { it.trim().isEmpty() }) {
            return ValidationResult(false, "格式錯誤：請勿輸入空白或連續逗號")
        }

        val tokens = rawTokens.map { it.trim() }

        // 3️⃣ 轉數字
        val numbers = mutableListOf<Int>()
        for (t in tokens) {
            val n = t.toIntOrNull()
            if (n == null) {
                return ValidationResult(false, "含有無效數字：$t")
            }
            numbers.add(n)
        }

        // 4️⃣ 特獎必須 1 個
        if (isSpecialPrize && numbers.size != 1) {
            return ValidationResult(false, "特獎只能設定 1 個數字")
        }

        // 5️⃣ 範圍檢查
        for (n in numbers) {
            if (n < 1 || n > maxNumber) {
                return ValidationResult(false, "數字 $n 超出範圍（1 ~ $maxNumber）")
            }
        }

        // 6️⃣ ⭐ 大獎數量限制（依刮數）
        if (!isSpecialPrize) {
            val grandLimit = GRAND_LIMITS[maxNumber] ?: 20
            if (numbers.size > grandLimit) {
                return ValidationResult(false, "超過大獎最大數量（最多 $grandLimit 個）")
            }
        }

        // 7️⃣ 大獎不可重複
        if (!isSpecialPrize && numbers.toSet().size != numbers.size) {
            return ValidationResult(false, "大獎不可重複，請重新輸入")
        }

        // 8️⃣ 特獎與大獎不能相同
        if (isSpecialPrize) {
            val grandText = binding.editTextGrandPrize.text.toString()
            val grandList = grandText.split(",")
                .map { it.trim() }
                .mapNotNull { it.toIntOrNull() }

            if (grandList.contains(numbers[0])) {
                return ValidationResult(false, "特獎不能與大獎相同")
            }

        } else {
            val specialText = binding.editTextSpecialPrize.text.toString()
            val specialValue = specialText.toIntOrNull()
            if (specialValue != null && numbers.contains(specialValue)) {
                return ValidationResult(false, "大獎不能與特獎相同")
            }
        }

        return ValidationResult(true, "")
    }

    // ✅ 新增：驗證結果數據類
    data class ValidationResult(
        val isValid: Boolean,
        val errorMessage: String
    )
}