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
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.champion.king.ScratchBoardPreviewFragment
import com.champion.king.constants.ScratchCardConstants
import com.champion.king.databinding.FragmentSettingsBinding
import com.champion.king.model.NumberConfiguration
import com.champion.king.model.ScratchCard
import com.champion.king.util.ToastManager

class SettingsUIManager(
    private val binding: FragmentSettingsBinding,
    private val context: Context,
    private val childFragmentManager: FragmentManager,
    private val showToast: (String) -> Unit
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

    // ✅ 顯示特獎數字鍵盤對話框（單一數字，不允許逗號）
    fun showSpecialPrizeKeyboard(
        currentValue: String?,
        currentScratchType: Int,
        onConfirm: (String) -> Unit
    ) {
        showPrizeKeyboard(
            title = "輸入特獎數字",
            currentValue = currentValue,
            hint = "${currentScratchType}刮｜特獎只能設定 1 個",
            allowComma = false,
            validator = { input ->
                validatePrizeInput(input, currentScratchType, isSpecialPrize = true)
            },
            onConfirm = onConfirm
        )
    }


    // ✅ 顯示大獎數字鍵盤對話框（可多個，允許逗號）
    fun showGrandPrizeKeyboard(
        currentValue: String?,
        currentScratchType: Int,
        onConfirm: (String) -> Unit
    ) {
        val grandLimit = GRAND_LIMITS[currentScratchType] ?: 20
        showPrizeKeyboard(
            title = "輸入大獎數字",
            currentValue = currentValue,
            hint = "${currentScratchType}刮｜大獎最多可設定 ${grandLimit} 個",
            allowComma = true,
            validator = { input ->
                validatePrizeInput(input, currentScratchType, isSpecialPrize = false)
            },
            onConfirm = onConfirm
        )
    }

    // ✅ 新增：消費門檻（元）鍵盤：只允許 0 或正整數；不允許逗號
    fun showShoppingThresholdKeyboard(
        currentValue: String?,
        onConfirm: (Int) -> Unit
    ) {
        showPrizeKeyboard(
            title = "輸入消費金額（元）",
            currentValue = currentValue,
            hint = "請輸入整數元（0以上）",
            allowComma = false,
            validator = { input ->
                val t = input.trim()
                val v = if (t.isEmpty()) 0 else t.toIntOrNull()
                if (v == null || v < 0) {
                    ValidationResult(false, "請輸入 0 或正整數")
                } else {
                    ValidationResult(true, "")
                }
            },
            onConfirm = { raw ->
                val t = raw.trim()
                val v = if (t.isEmpty()) 0 else t.toIntOrNull() ?: 0
                onConfirm(v)
            }
        )
    }

    // ✅ 通用的數字鍵盤對話框（可注入 hint / validator / 是否允許逗號）
    private fun showPrizeKeyboard(
        title: String,
        currentValue: String?,
        hint: String,
        allowComma: Boolean,
        validator: (String) -> ValidationResult,
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

        // ⭐ 上方提示文字
        val topHintText = dialogView.findViewById<TextView>(com.champion.king.R.id.dialog_number_top_hint)
        topHintText.text = hint

        // ✅ 有逗點鍵 -> 才顯示提示（例如大獎可用逗點分隔）
        // ⭐ 下方提示文字
        var bottomHintText = dialogView.findViewById<TextView>(com.champion.king.R.id.dialog_number_bottom_hint)
        bottomHintText.visibility = if (allowComma) View.VISIBLE else View.GONE

        // 禁用系統鍵盤
        dialogEditText.showSoftInputOnFocus = false

        // ✅ 交換：逗點鍵的位置改成「清除」；清除鍵的位置改成「逗點」
        btnComma.text = "清除"
        btnClear.text = ","

        // 逗點是否允許：不允許時就把「清除原位置(現在是逗點鍵)」隱藏，避免怪空格出現在逗點鍵位置
        btnClear.visibility = if (allowComma) View.VISIBLE else View.GONE

        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton("確定", null) // 先設 null，onShow 再綁 click（才能控制不關閉）
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            ToastManager.setHostWindow(dialog.window)

            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val inputValue = dialogEditText.text.toString().trim()
                val result = validator(inputValue)

                if (result.isValid) {
                    onConfirm(inputValue)
                    dialog.dismiss()
                } else {
                    showToast(result.errorMessage)
                }
            }
        }

        dialog.setOnDismissListener {
            ToastManager.clearHostWindow()
        }

        // --- 下面「數字按鈕插入」的邏輯：保留你原本那套（不要動） ---
        val numberClickListener = View.OnClickListener { view ->
            val button = view as Button
            val number = button.text.toString()

            val editable = dialogEditText.text ?: return@OnClickListener

            var start = dialogEditText.selectionStart
            var end = dialogEditText.selectionEnd

            if (start == -1 || end == -1) {
                editable.append(number)
                dialogEditText.setSelection(editable.length)
                return@OnClickListener
            }

            if (start != end) {
                editable.replace(start, end, number)
                start += number.length
            } else {
                editable.insert(start, number)
                start += number.length
            }
            dialogEditText.setSelection(start)
        }

        listOf(btn0, btn1, btn2, btn3, btn4, btn5, btn6, btn7, btn8, btn9).forEach {
            it.setOnClickListener(numberClickListener)
        }

        // ✅ 清除按鈕（放到逗點鍵的位置）
        btnComma.setOnClickListener {
            dialogEditText.setText("")
            dialogEditText.setSelection(0)
        }

        btnDelete.setOnClickListener {
            val editable = dialogEditText.text ?: return@setOnClickListener
            val start = dialogEditText.selectionStart
            val end = dialogEditText.selectionEnd
            if (start == -1 || end == -1) return@setOnClickListener

            if (start != end) {
                editable.delete(start, end)
                dialogEditText.setSelection(start)
            } else if (start > 0) {
                editable.delete(start - 1, start)
                dialogEditText.setSelection(start - 1)
            }
        }

        btnClear.setOnClickListener {
            if (!allowComma) return@setOnClickListener
            val editable = dialogEditText.text ?: return@setOnClickListener
            val start = dialogEditText.selectionStart
            val end = dialogEditText.selectionEnd
            if (start == -1 || end == -1) return@setOnClickListener

            val insert = ","
            if (start != end) {
                editable.replace(start, end, insert)
                dialogEditText.setSelection(start + insert.length)
            } else {
                editable.insert(start, insert)
                dialogEditText.setSelection(start + insert.length)
            }
        }

        dialog.show()
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