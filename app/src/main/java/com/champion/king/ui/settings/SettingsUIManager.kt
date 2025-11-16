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

        // 數字按鈕點擊事件
        val numberClickListener = View.OnClickListener { view ->
            val button = view as Button
            val number = button.text.toString()
            val currentText = dialogEditText.text.toString()

            // 添加數字
            dialogEditText.setText("$currentText$number")
            dialogEditText.setSelection(dialogEditText.text.length)
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

        // 逗號按鈕
        btnComma.setOnClickListener {
            val currentText = dialogEditText.text.toString()
            // 只有在特獎時禁用逗號,因為特獎只能有一個數字
            if (isSpecialPrize) {
                Toast.makeText(context, "特獎只能輸入一個數字", Toast.LENGTH_SHORT).show()
            } else {
                // 防止連續逗號或開頭就是逗號
                if (currentText.isNotEmpty() && !currentText.endsWith(",")) {
                    dialogEditText.setText("$currentText,")
                    dialogEditText.setSelection(dialogEditText.text.length)
                }
            }
        }

        // 清除按鈕
        btnClear.setOnClickListener {
            dialogEditText.setText("")
        }

        // 退格按鈕
        btnDelete.setOnClickListener {
            val currentText = dialogEditText.text.toString()
            if (currentText.isNotEmpty()) {
                val newText = currentText.substring(0, currentText.length - 1)
                dialogEditText.setText(newText)
                dialogEditText.setSelection(dialogEditText.text.length)
            }
        }

        dialog.show()

        // 點擊 EditText 時也禁用系統鍵盤
        dialogEditText.setOnClickListener {
            // 不做任何事,阻止系統鍵盤彈出
        }
    }

    // ✅ 新增：驗證獎項輸入
    private fun validatePrizeInput(
        input: String,
        scratchType: Int,
        isSpecialPrize: Boolean
    ): ValidationResult {
        if (input.isEmpty()) {
            return ValidationResult(false, "請輸入數字")
        }

        // 解析輸入的數字
        val numbers = input.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toIntOrNull() }

        if (numbers.isEmpty()) {
            return ValidationResult(false, "請輸入有效的數字")
        }

        // 驗證1：特獎只能有一個數字
        if (isSpecialPrize && numbers.size > 1) {
            return ValidationResult(false, "特獎只能輸入一個數字")
        }

        // 驗證2：檢查數字是否在有效範圍內 (1 到 scratchType)
        val invalidNumbers = numbers.filter { it < 1 || it > scratchType }
        if (invalidNumbers.isNotEmpty()) {
            return ValidationResult(
                false,
                "數字必須在 1 到 $scratchType 之間\n無效的數字：${invalidNumbers.joinToString(", ")}"
            )
        }

        // 驗證3：大獎數量不能超過上限
        if (!isSpecialPrize) {
            val maxGrandPrizes = GRAND_LIMITS[scratchType] ?: 3
            if (numbers.size > maxGrandPrizes) {
                return ValidationResult(
                    false,
                    "此刮數的大獎數量上限為 $maxGrandPrizes 個\n您輸入了 ${numbers.size} 個"
                )
            }
        }

        // 驗證4：檢查是否有重複數字
        if (numbers.size != numbers.distinct().size) {
            return ValidationResult(false, "不能輸入重複的數字")
        }

        return ValidationResult(true, "")
    }

    // ✅ 新增：驗證結果數據類
    data class ValidationResult(
        val isValid: Boolean,
        val errorMessage: String
    )
}