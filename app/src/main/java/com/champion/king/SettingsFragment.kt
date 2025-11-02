package com.champion.king

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.champion.king.constants.ScratchCardConstants
import com.champion.king.core.config.AppConfig
import com.champion.king.data.FirebaseRepository
import com.champion.king.databinding.FragmentSettingsBinding
import com.champion.king.model.NumberConfiguration
import com.champion.king.model.ScratchCard
import com.champion.king.model.User
import com.champion.king.ui.settings.SettingsActionHandler
import com.champion.king.ui.settings.SettingsUIManager
import com.champion.king.ui.settings.ShelfManager
import com.google.firebase.database.*
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    companion object {
        // 刮板切換閾值配置：當刮取進度超過此比例且特獎未出時，不允許切換
        private const val SCRATCH_SWITCH_THRESHOLD = 0.5  // 50%
    }

    // 大獎數量限制表
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

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    // 管理器
    private lateinit var shelfManager: ShelfManager
    private lateinit var uiManager: SettingsUIManager
    private lateinit var actionHandler: SettingsActionHandler

    // ViewModel
    private val viewModel: SettingsViewModel by viewModels {
        val database = FirebaseDatabase.getInstance(AppConfig.DB_URL).reference
        val repo = FirebaseRepository(database)
        val userKey = (requireActivity() as UserSessionProvider).getCurrentUserFirebaseKey()
            ?: throw IllegalStateException("請先登入！")
        SettingsViewModel.Factory(repo, userKey)
    }

    // 特獎、大獎挑選模式狀態
    private var isPickingSpecialPrize: Boolean = false
    private var isPickingGrandPrize: Boolean = false

    private var isFocusMode: Boolean = false

    private enum class FocusTarget { SPECIAL, GRAND }

    private var currentFocusTarget: FocusTarget? = null

    // 資料類別
    private data class ScratchTypeItem(val type: Int, val stock: Int) {
        override fun toString(): String = if (stock > 0) {
            "${type}刮 (剩${stock})"
        } else {
            "${type}刮 (無庫存)"
        }

        fun getScratchType(): Int = type
    }

    // 常數和狀態變數
    private val scratchOrder = listOf(10, 20, 25, 30, 40, 50, 60, 80, 100, 120, 160, 200, 240)
    private var backpackListener: ValueEventListener? = null
    private var userReference: DatabaseReference? = null
    private var currentPreviewFragment: ScratchBoardPreviewFragment? = null
    private var isUpdatingSpinner = false
    private var isShowingUnsetState = false
    private var scratchTypeLabel: TextView? = null

    // 新增：動態創建的只讀標籤
    private var specialPrizeLabel: TextView? = null
    private var grandPrizeLabel: TextView? = null

    // 新增：標記是否正在進行儲存操作
    private var isSavingInProgress = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        super.onViewCreated(v, savedInstanceState)
        initializeComponents()
        setupListenersAndObservers()
        initializeData()
        setupNumberPickResultListener()

        // 設置初始選中的版位
        setupInitialShelfSelection()
    }

    // 新增：追蹤是否已完成初始化
    private var isInitialSelectionComplete = false

    // 新增：設置初始版位選擇邏輯
    private fun setupInitialShelfSelection() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.cards.collect { cards ->
                if (cards.isNotEmpty() && !isInitialSelectionComplete) {
                    val initialOrder = determineInitialShelfSelection(cards)
                    shelfManager.selectShelf(initialOrder)

                    val selectedCard = cards[initialOrder]
                    if (selectedCard == null) {
                        showUnsetShelfState()
                    } else {
                        showSetShelfState(selectedCard)
                    }

                    isInitialSelectionComplete = true
                    return@collect // 完成初始化後停止監聽
                }
            }
        }
    }

    // 新增：決定初始版位選擇邏輯
    private fun determineInitialShelfSelection(cards: Map<Int, ScratchCard>): Int {
        // 2-1: 如果有使用中的版位，選擇使用中的版位
        val inUseCard = cards.values.firstOrNull { it.inUsed }
        if (inUseCard != null) {
            Log.d("SettingsFragment", "發現使用中版位：${inUseCard.order}")
            return inUseCard.order ?: ScratchCardConstants.DEFAULT_SHELF_ORDER
        }

        // 2-2: 如果沒有使用中的版位，選擇非「未設置」的最小版位
        val nonEmptyCards = cards.values.filter { it.order != null }.sortedBy { it.order }
        if (nonEmptyCards.isNotEmpty()) {
            val minOrder = nonEmptyCards.first().order!!
            Log.d("SettingsFragment", "選擇最小非空版位：$minOrder")
            return minOrder
        }

        // 如果都是未設置，選擇預設版位
        Log.d("SettingsFragment", "所有版位都未設置，選擇預設版位：${ScratchCardConstants.DEFAULT_SHELF_ORDER}")
        return ScratchCardConstants.DEFAULT_SHELF_ORDER
    }

    // ===========================================
    // 初始化相關方法
    // ===========================================

    private fun initializeComponents() {
        shelfManager = ShelfManager(binding, viewModel)
        uiManager = SettingsUIManager(binding, requireContext(), childFragmentManager)

        // 使用配置的閾值創建 ActionHandler
        actionHandler = SettingsActionHandler(
            viewModel,
            requireContext(),
            SCRATCH_SWITCH_THRESHOLD
        )
    }

    private fun setupListenersAndObservers() {
        setupUI()
        setupClickListeners()
        setupSpinnerListeners()
        observeViewModel()
    }

    private fun initializeData() {
        initSpinnerWithPlaceholder()
        setupBackpackListener()
    }

    // ===========================================
    // UI 設置相關方法
    // ===========================================

    private fun setupUI() {
        shelfManager.initShelfViews()
        shelfManager.setOnShelfClickListener { order ->
            val selectedCard = viewModel.cards.value[order]
            if (selectedCard == null) {
                showUnsetShelfState()
            } else {
                showSetShelfState(selectedCard)
            }
        }
    }

    private fun setupClickListeners() {
        binding.buttonSaveSettings.setOnClickListener { handleSaveClick() }
        binding.buttonToggleInuse.setOnClickListener { handleToggleInUseClick() }
        binding.buttonReturnSelected.setOnClickListener { handleReturnClick() }
        binding.buttonDeleteSelected.setOnClickListener { handleDeleteClick() }

        // 新增：「特獎」按鈕 → 進入/退出 單選挑選模式
        binding.buttonPickSpecialPrize.setOnClickListener {
            if (!isPickingSpecialPrize) {
                enterSpecialPrizePickMode()
            } else {
                exitSpecialPrizePickMode()
            }
        }

        // 新增：「大獎」按鈕 → 進入/退出 多選挑選模式
        binding.buttonPickGrandPrize.setOnClickListener {
            if (!isPickingGrandPrize) {
                enterGrandPrizePickMode()
            } else {
                exitGrandPrizePickMode()
            }
        }
    }

    private fun setupSpinnerListeners() {
        binding.spinnerScratchesCount.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    // ★ 如果正在更新 Spinner 或正在儲存，直接返回
                    if (isUpdatingSpinner || isSavingInProgress) return

                    val selectedItem =
                        binding.spinnerScratchesCount.selectedItem as? ScratchTypeItem
                    selectedItem?.let { item ->
                        val scratchType = item.getScratchType()
                        Log.d(
                            "SettingsFragment",
                            "用戶選擇了刮數: ${scratchType}刮, 庫存: ${item.stock}"
                        )

                        if (item.stock > 0) {
                            val selectedCard =
                                viewModel.cards.value[shelfManager.selectedShelfOrder]
                            if (selectedCard == null) {
                                Log.d(
                                    "SettingsFragment",
                                    "清除未設置狀態，顯示 ${scratchType}刮 預覽"
                                )
                                isShowingUnsetState = false
                                updatePreviewForScratchType(scratchType)
                            }
                        } else {
                            Log.w("SettingsFragment", "${scratchType}刮 庫存為 0，無法選擇")
                        }
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.cards.collect { cards ->
                        // 只更新架上列表（這個不會造成閃爍）
                        shelfManager.updateShelfUI(cards)

                        // ★ 如果正在儲存，跳過詳細資料的更新，避免預覽區重建
                        if (isSavingInProgress) {
                            return@collect
                        }

                        val selectedCard = cards[shelfManager.selectedShelfOrder]
                        if (selectedCard == null) {
                            showUnsetShelfState()
                        } else {
                            showSetShelfState(selectedCard)
                        }
                    }
                }
                launch { viewModel.events.collect { /* toast 等由 ActionHandler 處理 */ } }
            }
        }
    }

    // ===========================================
    // Focus聚焦相關方法
    // ===========================================

    // 進出聚焦模式
    private fun updateFocusMode(enabled: Boolean, target: FocusTarget?) {
        isFocusMode = enabled
        currentFocusTarget = target
        applyFocusMode()
    }

    // 實作聚焦效果（只允許預覽區 + 目標按鈕）
    private fun applyFocusMode() {
        if (!isAdded || _binding == null) return

        val allowedButton = when (currentFocusTarget) {
            FocusTarget.SPECIAL -> binding.buttonPickSpecialPrize
            FocusTarget.GRAND -> binding.buttonPickGrandPrize
            else -> null
        }

        val allowedViews = mutableSetOf<View>(
            binding.scratchBoardArea // 預覽區
        ).apply {
            allowedButton?.let { btn ->
                add(btn)
                (btn.parent as? View)?.let { add(it) }  // 該按鈕所在那一行（label + 欄位）
            }
        }

        // 先全部恢復
        restoreAllInteractive()

        if (!isFocusMode) return

        // 1) 禁用 + 降低透明度：上方架上列表整區
        setEnabledRecursively(binding.onShelfListContainer, false)
        binding.onShelfListContainer.alpha = 0.35f

        // 2) 參數設定區：除了「目標按鈕所在那行」以外，全部禁用 + dim
        val params = binding.settingParametersContainer
        for (i in 0 until params.childCount) {
            val child = params.getChildAt(i)
            if (!child.containsAnyOf(allowedViews)) {
                setEnabledRecursively(child, false)
                child.alpha = 0.35f
            } else {
                child.alpha = 1f
            }
        }

        // 3) 預覽區保持可用並高亮（可選：略微提高透明度讓更醒目）
        binding.scratchBoardArea.alpha = 1f

        // 4) 其他零散按鈕雙保險（Save / InUse / Return / Delete）
        listOf(
            binding.buttonSaveSettings,
            binding.buttonToggleInuse,
            binding.buttonReturnSelected,
            binding.buttonDeleteSelected
        ).forEach { v ->
            if (v !in allowedViews) {
                v.isEnabled = false
                v.alpha = 0.35f
            }
        }
    }

    // 將整個畫面互動性恢復
    private fun restoreAllInteractive() {
        fun restore(v: View) {
            v.isEnabled = true
            v.alpha = 1f
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) restore(v.getChildAt(i))
            }
        }
        restore(binding.root)
    }

    // 工具：非遞禁用樹
    private fun setEnabledRecursively(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setEnabledRecursively(view.getChildAt(i), enabled)
            }
        }
    }

    // 工具：某個容器是否包含 allowed 視圖（含其後代）
    private fun View.containsAnyOf(targets: Set<View>): Boolean {
        if (this in targets) return true
        if (this is ViewGroup) {
            for (i in 0 until childCount) {
                if (getChildAt(i).containsAnyOf(targets)) return true
            }
        }
        return false
    }

    // ===========================================
    // 特獎挑選模式
    // ===========================================

    // 特獎：進入挑選
    private fun enterSpecialPrizePickMode() {
        isPickingSpecialPrize = true
        binding.buttonPickSpecialPrize.isPressed = true
        binding.buttonPickSpecialPrize.text = "特獎（選取中…）"
        showToast("請在左側刮板預覽區點選一個數字")
        currentPreviewFragment?.setSinglePickEnabled(true)

        // ☆ 只允許「預覽區 + 特獎按鈕」
        updateFocusMode(true, FocusTarget.SPECIAL)
    }

    // 特獎：退出挑選
    private fun exitSpecialPrizePickMode() {
        isPickingSpecialPrize = false
        binding.buttonPickSpecialPrize.isPressed = false
        binding.buttonPickSpecialPrize.text = "特獎"
        currentPreviewFragment?.setSinglePickEnabled(false)

        // ☆ 解除聚焦
        updateFocusMode(false, null)
    }

    // 大獎：進入多選
    private fun enterGrandPrizePickMode() {
        isPickingGrandPrize = true
        binding.buttonPickGrandPrize.isPressed = true
        binding.buttonPickGrandPrize.text = "大獎（多選中…）"
        showToast("請在左側預覽區多選數字（再點可取消）")
        currentPreviewFragment?.setMultiPickEnabled(true)

        // ☆ 只允許「預覽區 + 大獎按鈕」
        updateFocusMode(true, FocusTarget.GRAND)
    }

    // 大獎：退出多選
    private fun exitGrandPrizePickMode() {
        isPickingGrandPrize = false
        binding.buttonPickGrandPrize.isPressed = false
        binding.buttonPickGrandPrize.text = "大獎"
        currentPreviewFragment?.setMultiPickEnabled(false)

        // ☆ 解除聚焦
        updateFocusMode(false, null)
    }

    /**
     * 監聽預覽區回傳被點選的數字：
     * 請在 ScratchBoardPreviewFragment 內於使用者點擊某個數字時觸發：
     *   setFragmentResult("scratch_number_selected", bundleOf("number" to 選到的數字))
     */
    private fun setupNumberPickResultListener() {
        childFragmentManager.setFragmentResultListener(
            "scratch_number_selected",
            viewLifecycleOwner
        ) { _, result ->
            val picked = result.getInt("number", -1)
            if (picked > 0 && isPickingSpecialPrize) {
                binding.editTextSpecialPrize.setText(picked.toString())
                currentPreviewFragment?.setSelectedNumber(picked)
            }
        }

        // 新增：多選大獎監聽
        childFragmentManager.setFragmentResultListener(
            "grand_numbers_changed",
            viewLifecycleOwner
        ) { _, result ->
            val arr = result.getIntArray("numbers") ?: intArrayOf()
            // 在每個逗號後面插入零寬空白字元 (U+200B)，讓系統只在逗號後可換行
            val display = arr.sorted().joinToString(", \u200B")
            binding.editTextGrandPrize.setText(display)
        }
    }

    /** 儲存前的完整驗證：通過回傳 true，否則顯示原因並回傳 false */
    private fun validateBeforeSave(data: SaveData): Boolean {
        // 盤面範圍（從預覽區取得格子總數；拿不到時就不做範圍驗證）
        val totalCells = currentPreviewFragment?.getGeneratedNumberConfigurations()?.size ?: 0

        // --- 特獎：必填 + 單一 + 數字 + 範圍 ---
        val spStr = data.specialPrize?.trim() ?: ""
        if (spStr.isEmpty()) {
            showToast("請先選擇特獎（必填）")
            return false
        }
        val sp = spStr.toIntOrNull()
        if (sp == null) {
            showToast("特獎格式錯誤，請重新選擇")
            return false
        }
        if (totalCells > 0 && (sp < 1 || sp > totalCells)) {
            showToast("特獎超出範圍（1 ~ $totalCells）")
            return false
        }

        // --- 大獎：0/1/多個，逗號分隔、數字、不可重複、範圍 ---
        val gpStr = data.grandPrize?.trim().orEmpty()
        val gpList: List<Int> =
            if (gpStr.isEmpty()) emptyList()
            else {
                val tokens = gpStr.split(",").map { it.trim() }
                if (tokens.any { it.isEmpty() }) {
                    showToast("大獎格式錯誤，請以半形逗號分隔（例如：3,12,25）")
                    return false
                }
                val nums = mutableListOf<Int>()
                for (t in tokens) {
                    val n = t.toIntOrNull()
                    if (n == null) {
                        showToast("大獎包含非數字項目「$t」，請重新選取")
                        return false
                    }
                    if (totalCells > 0 && (n < 1 || n > totalCells)) {
                        showToast("大獎數字 $n 超出範圍（1 ~ $totalCells）")
                        return false
                    }
                    nums.add(n)
                }
                if (nums.toSet().size != nums.size) {
                    showToast("大獎有重複數字，請調整")
                    return false
                }
                nums
            }

        // --- 互斥：特獎不可同時為大獎 ---
        if (gpList.contains(sp)) {
            showToast("無法儲存：特獎不可同時為大獎，請調整選取")
            // 視覺上保留現況（雙保險）
            currentPreviewFragment?.setSelectedNumber(sp)
            currentPreviewFragment?.setGrandSelectedNumbers(gpList)
            return false
        }

        return true
    }

    // ===========================================
    // 狀態管理相關方法
    // ===========================================

    // 移除重複定義，因為已經在上面定義過了

    private fun showUnsetShelfState() {
        isShowingUnsetState = true
        showPreviewUnset()

        // 確保顯示可編輯的欄位（修復從使用中版位切換過來的問題）
        showEditableFields()
        clearTextFieldsOnly()

        showScratchTypeSpinner()
        clearSpinnerSelection()
        setButtonsEnabled(save = true, toggleInUse = false, returnBtn = false, delete = false)
        uiManager.updateInUseButtonUI(null)
        uiManager.updateActionButtonsUI(null)
    }

    // 檢查刮板是否已被刮過（1刮含以上）
    private fun hasBeenScratched(card: ScratchCard): Boolean {
        val configurations = card.numberConfigurations
        if (configurations.isNullOrEmpty()) {
            Log.w("SettingsFragment", "刮板沒有數字配置，視為未刮過")
            return false
        }

        val scratchedCount = configurations.count { it.scratched }
        Log.d("SettingsFragment", "刮板${card.order}已刮數量: $scratchedCount")

        return scratchedCount >= 1
    }

    private fun showSetShelfState(selectedCard: ScratchCard) {
        isShowingUnsetState = false
        restorePreviewContainer()

        // 檢查是否應該顯示只讀模式：使用中 OR 已被刮過
        val shouldShowReadonly = selectedCard.inUsed || hasBeenScratched(selectedCard)

        if (shouldShowReadonly) {
            displayScratchCardDetailsReadonly(selectedCard)
            // 根據不同狀態設置按鈕權限
            if (selectedCard.inUsed) {
                // 使用中：不允許保存、返回、刪除，但可以切換使用狀態
                setButtonsEnabled(save = false, toggleInUse = true, returnBtn = false, delete = false)
            } else {
                // 已被刮過但非使用中：不允許保存、返回，刪除需要額外檢查
                setButtonsEnabled(save = false, toggleInUse = true, returnBtn = false, delete = true)
            }
        } else {
            displayScratchCardDetails(selectedCard)
            setButtonsEnabled(save = true, toggleInUse = true, returnBtn = true, delete = true)
        }

        showScratchTypeLabel(selectedCard.scratchesType)
        uiManager.updateInUseButtonUI(selectedCard)
        uiManager.updateActionButtonsUI(selectedCard)
    }

    // 移除 handleCardsUpdate 方法，因為已經在 observeViewModel 中直接處理

    // ===========================================
    // 點擊事件處理方法
    // ===========================================

    private fun handleSaveClick() {
        val selectedCard = viewModel.cards.value[shelfManager.selectedShelfOrder]
        val scratchType = if (selectedCard != null) {
            selectedCard.scratchesType
        } else {
            val selectedItem = binding.spinnerScratchesCount.selectedItem as? ScratchTypeItem
            selectedItem?.getScratchType() ?: return
        }

        val saveData = extractSaveData(scratchType)

        // ☆☆☆ 先把目前輸入的特獎數字標到預覽（立刻可見）
        currentPreviewFragment?.setSelectedNumber(
            binding.editTextSpecialPrize.text?.toString()?.toIntOrNull()
        )

        val gp = binding.editTextGrandPrize.text?.toString()
            ?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        currentPreviewFragment?.setGrandSelectedNumbers(gp)

        handleSaveSettings(saveData)
    }

    private fun extractSaveData(scratchType: Int): SaveData {
        return SaveData(
            order = shelfManager.selectedShelfOrder,
            scratchType = scratchType,
            specialPrize = binding.editTextSpecialPrize.text?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() },
            grandPrize = binding.editTextGrandPrize.text?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() },
            claws = binding.spinnerClawsCount.selectedItem?.toString()?.toIntOrNull(),
            giveaway = binding.spinnerGiveawayCount.selectedItem?.toString()?.toIntOrNull(),
            numberConfigurations = currentPreviewFragment?.getGeneratedNumberConfigurations(),
            currentCards = viewModel.cards.value
        )
    }

    private data class SaveData(
        val order: Int,
        val scratchType: Int,
        val specialPrize: String?,
        val grandPrize: String?,
        val claws: Int?,
        val giveaway: Int?,
        val numberConfigurations: List<NumberConfiguration>?,
        val currentCards: Map<Int, ScratchCard>
    )

    private fun handleSaveSettings(data: SaveData) {
        val limit = GRAND_LIMITS[data.scratchType] ?: 0
        val gpList = data.grandPrize?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        if (limit > 0 && gpList.size > limit) {
            showToast("${data.scratchType}刮的大獎數量限制為 ${limit} 個")
            return
        }
        if (!validateBeforeSave(data)) return
        val sp = data.specialPrize?.toIntOrNull()
        if (sp != null && gpList.contains(sp)) {
            showToast("無法儲存：特獎不可同時為大獎，請調整選取")
            currentPreviewFragment?.setSelectedNumber(sp)
            currentPreviewFragment?.setGrandSelectedNumbers(gpList)
            return
        }

        if (data.numberConfigurations.isNullOrEmpty()) {
            showToast("數字配置為空，無法儲存")
            return
        }

        val existingCard = data.currentCards[data.order]
        val isNewCard = existingCard == null

        isSavingInProgress = true

        Log.d("SettingsFragment", "準備儲存: isNewCard=$isNewCard, scratchType=${data.scratchType}")

        if (isNewCard) {
            Log.d("SettingsFragment", "新建版位，準備扣減庫存")
            deductScratchTypeStock(data.scratchType) { success ->
                if (success) {
                    Log.d("SettingsFragment", "庫存扣減成功，開始創建卡片")
                    upsertCardWithData(data, existingCard)

                    // ★ 延遲後重置標記並手動更新 UI
                    viewLifecycleOwner.lifecycleScope.launch {
                        kotlinx.coroutines.delay(500)
                        isSavingInProgress = false

                        // ★ 手動觸發 UI 更新
                        val updatedCard = viewModel.cards.value[data.order]
                        if (updatedCard != null) {
                            Log.d("SettingsFragment", "儲存完成，手動更新 UI")
                            showSetShelfState(updatedCard)
                        } else {
                            Log.w("SettingsFragment", "儲存完成但找不到卡片")
                            showUnsetShelfState()
                        }
                    }
                } else {
                    isSavingInProgress = false
                    showToast("庫存不足或扣減失敗")
                }
            }
        } else {
            Log.d("SettingsFragment", "更新現有版位，直接儲存")
            upsertCardWithData(data, existingCard)

            // ★ 延遲後重置標記並手動更新 UI
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(500)
                isSavingInProgress = false

                // ★ 手動觸發 UI 更新
                val updatedCard = viewModel.cards.value[data.order]
                if (updatedCard != null) {
                    Log.d("SettingsFragment", "儲存完成，手動更新 UI")
                    showSetShelfState(updatedCard)
                } else {
                    Log.w("SettingsFragment", "儲存完成但找不到卡片")
                    showUnsetShelfState()
                }
            }
        }
    }

    private fun upsertCardWithData(data: SaveData, existingCard: ScratchCard?) {
        viewModel.upsertCard(
            order = data.order,
            scratchesType = data.scratchType,
            specialPrize = data.specialPrize,
            grandPrize = data.grandPrize,
            clawsCount = data.claws,
            giveawayCount = data.giveaway,
            numberConfigurations = data.numberConfigurations!!,
            existingSerial = existingCard?.serialNumber,
            keepInUsed = existingCard?.inUsed ?: false
        )
    }

    private fun handleToggleInUseClick() {
        actionHandler.handleToggleInUse(shelfManager.selectedShelfOrder, viewModel.cards.value)
    }

    private fun handleReturnClick() {
        actionHandler.handleReturn(shelfManager.selectedShelfOrder, viewModel.cards.value)
    }

    private fun handleDeleteClick() {
        actionHandler.handleDelete(shelfManager.selectedShelfOrder, viewModel.cards.value)
    }

    // ===========================================
    // UI 顯示相關方法
    // ===========================================

    // 原有的顯示方法（非使用中版位用）
    private fun displayScratchCardDetails(card: ScratchCard) {
        safeExecute("顯示可編輯卡片詳情") {
            // 顯示可編輯的輸入框
            showEditableFields()

            binding.editTextSpecialPrize.setText(card.specialPrize ?: "")
            binding.editTextGrandPrize.setText(card.grandPrize ?: "")

            setSpinnerSelection(binding.spinnerClawsCount, card.clawsCount)
            setSpinnerSelection(binding.spinnerGiveawayCount, card.giveawayCount)

            displayScratchBoardPreview(card.scratchesType, card.numberConfigurations)

            // 預覽建立後，依卡片的特獎數字加上金色標記
            currentPreviewFragment?.setSelectedNumber(card.specialPrize?.toIntOrNull())

            val grandList = card.grandPrize?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() }
            currentPreviewFragment?.setGrandSelectedNumbers(grandList)
        }
    }

    // 新增：使用中版位的只讀顯示方法
    private fun displayScratchCardDetailsReadonly(card: ScratchCard) {
        safeExecute("顯示只讀卡片詳情") {
            // 顯示只讀的標籤
            showReadonlyFields(card)

            // Spinner 設定（這些保持可編輯，因為不影響遊戲核心）
            setSpinnerSelection(binding.spinnerClawsCount, card.clawsCount)
            setSpinnerSelection(binding.spinnerGiveawayCount, card.giveawayCount)

            displayScratchBoardPreview(card.scratchesType, card.numberConfigurations)

            // 預覽建立後，顯示當前的特獎和大獎標記
            currentPreviewFragment?.setSelectedNumber(card.specialPrize?.toIntOrNull())

            val grandList = card.grandPrize?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() }
            currentPreviewFragment?.setGrandSelectedNumbers(grandList)
        }
    }

    // 顯示可編輯欄位
    private fun showEditableFields() {
        // 移除只讀標籤
        removeReadonlyLabels()

        // 顯示原有的編輯容器
        showEditableContainers()
    }

    // 顯示可編輯的容器
    private fun showEditableContainers() {
        // 找到特獎和大獎的整個容器並顯示
        val specialPrizeContainer = findViewContaining(binding.buttonPickSpecialPrize)
        val grandPrizeContainer = findViewContaining(binding.buttonPickGrandPrize)

        specialPrizeContainer?.visibility = View.VISIBLE
        grandPrizeContainer?.visibility = View.VISIBLE

        // 確保按鈕和編輯框是可用狀態
        binding.buttonPickSpecialPrize.isEnabled = true
        binding.buttonPickGrandPrize.isEnabled = true
        binding.buttonPickSpecialPrize.alpha = 1.0f
        binding.buttonPickGrandPrize.alpha = 1.0f
        binding.editTextSpecialPrize.visibility = View.VISIBLE
        binding.editTextGrandPrize.visibility = View.VISIBLE
    }

    // 顯示只讀欄位
    private fun showReadonlyFields(card: ScratchCard) {
        // 完全隱藏按鈕和編輯框的整個容器
        hideEditableContainers()

        // 創建並顯示只讀標籤
        createReadonlyLabels(card)
    }

    // 隱藏可編輯的容器
    private fun hideEditableContainers() {
        // 找到特獎和大獎的整個容器並隱藏
        val specialPrizeContainer = findViewContaining(binding.buttonPickSpecialPrize)
        val grandPrizeContainer = findViewContaining(binding.buttonPickGrandPrize)

        specialPrizeContainer?.visibility = View.GONE
        grandPrizeContainer?.visibility = View.GONE
    }

    // 創建只讀標籤
    private fun createReadonlyLabels(card: ScratchCard) {
        removeReadonlyLabels() // 先清除舊的

        val context = requireContext()

        // 創建特獎標籤容器
        val specialPrizeContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 6)
            }
        }

        // 特獎標題
        val specialPrizeTitle = TextView(context).apply {
            text = "特獎："
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(0, 8, 8, 8)
            gravity = Gravity.CENTER_VERTICAL
        }

        // 特獎值標籤
        specialPrizeLabel = TextView(context).apply {
            text = card.specialPrize ?: "未設定"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.special_prize_gold))
            setPadding(12, 8, 12, 8)
            background = ContextCompat.getDrawable(context, R.drawable.readonly_label_background)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            minWidth = 80

            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
            }
        }

        specialPrizeContainer.addView(specialPrizeTitle)
        specialPrizeContainer.addView(specialPrizeLabel)

        // 創建大獎標籤容器
        val grandPrizeContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 6)
            }
        }

        // 大獎標題
        val grandPrizeTitle = TextView(context).apply {
            text = "大獎："
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(0, 8, 8, 8)
            gravity = Gravity.CENTER_VERTICAL
        }

        // 大獎值標籤
        grandPrizeLabel = TextView(context).apply {
            text = card.grandPrize ?: "未設定"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.grand_prize_green))
            setPadding(12, 8, 12, 8)
            background = ContextCompat.getDrawable(context, R.drawable.readonly_label_background)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD

            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
            }
        }

        grandPrizeContainer.addView(grandPrizeTitle)
        grandPrizeContainer.addView(grandPrizeLabel)

        // 將容器插入到設定區域
        insertReadonlyContainers(specialPrizeContainer, grandPrizeContainer)
    }

    // 插入只讀容器到布局中
    private fun insertReadonlyContainers(specialContainer: LinearLayout, grandContainer: LinearLayout) {
        val settingsContainer = binding.settingParametersContainer

        // 找到原本特獎和大獎容器的位置
        val originalSpecialContainer = findViewContaining(binding.buttonPickSpecialPrize)
        val originalGrandContainer = findViewContaining(binding.buttonPickGrandPrize)

        // 在原本特獎容器位置插入只讀特獎容器
        originalSpecialContainer?.let { container ->
            val index = settingsContainer.indexOfChild(container)
            if (index != -1) {
                settingsContainer.addView(specialContainer, index)
            }
        }

        // 在原本大獎容器位置插入只讀大獎容器
        originalGrandContainer?.let { container ->
            val index = settingsContainer.indexOfChild(container)
            // 需要考慮已經插入的特獎容器
            val adjustedIndex = if (specialContainer.parent != null) index + 1 else index
            if (adjustedIndex <= settingsContainer.childCount) {
                settingsContainer.addView(grandContainer, adjustedIndex)
            }
        }
    }

    // 找到包含指定View的父容器
    private fun findViewContaining(targetView: View): ViewGroup? {
        var parent = targetView.parent
        while (parent != null && parent != binding.settingParametersContainer) {
            parent = parent.parent
        }
        return if (parent == binding.settingParametersContainer) {
            targetView.parent as? ViewGroup
        } else null
    }

    // 移除只讀標籤
    private fun removeReadonlyLabels() {
        // 移除特獎標籤（現在是容器的一部分）
        specialPrizeLabel?.let { label ->
            val container = label.parent as? ViewGroup
            val parentContainer = container?.parent as? ViewGroup
            parentContainer?.removeView(container)
        }

        // 移除大獎標籤（現在是容器的一部分）
        grandPrizeLabel?.let { label ->
            val container = label.parent as? ViewGroup
            val parentContainer = container?.parent as? ViewGroup
            parentContainer?.removeView(container)
        }

        specialPrizeLabel = null
        grandPrizeLabel = null
    }

    private fun showScratchTypeSpinner() {
        binding.spinnerScratchesCount.visibility = View.VISIBLE
        scratchTypeLabel?.visibility = View.GONE
    }

    private fun showScratchTypeLabel(scratchType: Int) {
        binding.spinnerScratchesCount.visibility = View.GONE

        if (scratchTypeLabel == null) {
            scratchTypeLabel = TextView(requireContext()).apply {
                textSize = 16f
                setTextColor(Color.BLACK)
                background = null
                setPadding(0, 0, 0, 0)
                layoutParams = binding.spinnerScratchesCount.layoutParams
            }

            val parent = binding.spinnerScratchesCount.parent as ViewGroup
            val spinnerIndex = parent.indexOfChild(binding.spinnerScratchesCount)
            parent.addView(scratchTypeLabel, spinnerIndex + 1)
        }

        scratchTypeLabel?.text = "${scratchType}刮"
        scratchTypeLabel?.visibility = View.VISIBLE
    }

    private fun updatePreviewForScratchType(scratchType: Int) {
        if (isShowingUnsetState) {
            Log.d("SettingsFragment", "未設置狀態中，拒絕更新預覽為 ${scratchType}刮")
            return
        }

        val selectedCard = viewModel.cards.value[shelfManager.selectedShelfOrder]
        if (selectedCard == null) {
            Log.d("SettingsFragment", "更新預覽，刮數類型: ${scratchType}刮")
            displayScratchBoardPreview(scratchType, null)
        }
    }

    // ② 顯示/重建預覽時，確保挑選模式狀態馬上套用
    private fun displayScratchBoardPreview(
        scratchType: Int,
        existingConfigs: List<NumberConfiguration>?
    ) {
        safeExecute("顯示刮板預覽") {
            currentPreviewFragment?.let { fragment ->
                childFragmentManager.beginTransaction()
                    .remove(fragment)
                    .commitNowAllowingStateLoss()
            }

            val scratchesTypeString = "${scratchType}刮 (${getScratchDimensions(scratchType)})"

            currentPreviewFragment = if (existingConfigs != null) {
                ScratchBoardPreviewFragment.newInstance(scratchesTypeString, existingConfigs)
            } else {
                ScratchBoardPreviewFragment.newInstance(scratchesTypeString)
            }

            // 維持挑選模式狀態（如有）
            currentPreviewFragment?.arguments?.putBoolean(
                "enable_single_pick",
                isPickingSpecialPrize
            )

            childFragmentManager.beginTransaction()
                .replace(binding.scratchBoardArea.id, currentPreviewFragment!!)
                .commitAllowingStateLoss()

            // ☆ 立即同步挑選模式（原本就有）
            currentPreviewFragment?.setSinglePickEnabled(isPickingSpecialPrize)
            currentPreviewFragment?.setMultiPickEnabled(isPickingGrandPrize)

            // ☆☆☆ 立即把「目前特獎數字」標回金色（重點）
            childFragmentManager.executePendingTransactions() // 確保 view 都建好

            // 特獎金色
            val pickedSpecial = binding.editTextSpecialPrize.text?.toString()?.toIntOrNull()
            currentPreviewFragment?.setSelectedNumber(pickedSpecial)

            // 大獎綠色
            val grandList = binding.editTextGrandPrize.text?.toString()
                ?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
            currentPreviewFragment?.setGrandSelectedNumbers(grandList)
        }
    }

    private fun showPreviewUnset() {
        safeExecute("顯示未設置預覽") {
            currentPreviewFragment?.let { fragment ->
                childFragmentManager.beginTransaction().remove(fragment)
                    .commitNowAllowingStateLoss()
                currentPreviewFragment = null
            }

            binding.scratchBoardArea.removeAllViews()
            val tv = TextView(requireContext()).apply {
                text = "未設置"
                textSize = 20f
                setTextColor(Color.DKGRAY)
                gravity = android.view.Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            binding.scratchBoardArea.addView(tv)
            Log.d("SettingsFragment", "預覽區已設置為「未設置」狀態")
        }
    }

    // ===========================================
    // Firebase 相關方法
    // ===========================================

    private fun deductScratchTypeStock(scratchType: Int, onComplete: (Boolean) -> Unit) {
        val userRef = getUserFirebaseReference()
        if (userRef == null) {
            onComplete(false)
            return
        }

        val stockFieldName = "scratchType_$scratchType"

        userRef.child(stockFieldName).get()
            .addOnSuccessListener { snapshot ->
                val currentStock = snapshot.getValue(Int::class.java) ?: 0
                if (currentStock > 0) {
                    userRef.child(stockFieldName).setValue(currentStock - 1)
                        .addOnSuccessListener {
                            Log.d(
                                "SettingsFragment",
                                "${scratchType}刮 庫存已扣減1，剩餘: ${currentStock - 1}"
                            )
                            onComplete(true)
                        }
                        .addOnFailureListener { e ->
                            Log.e(
                                "SettingsFragment",
                                "扣減${scratchType}刮庫存失敗: ${e.message}",
                                e
                            )
                            onComplete(false)
                        }
                } else {
                    Log.w("SettingsFragment", "${scratchType}刮 庫存不足，無法扣減")
                    onComplete(false)
                }
            }
            .addOnFailureListener { e ->
                Log.e("SettingsFragment", "讀取${scratchType}刮庫存失敗: ${e.message}", e)
                onComplete(false)
            }
    }

    private fun setupBackpackListener() {
        val userRef = getUserFirebaseReference()
        if (userRef == null) {
            Log.w("SettingsFragment", "用戶未登入，無法載入背包資料")
            return
        }

        backpackListener?.let { userReference?.removeEventListener(it) }
        userReference = userRef

        backpackListener = createBackpackValueEventListener()
        userRef.addValueEventListener(backpackListener!!)
    }

    private fun createBackpackValueEventListener(): ValueEventListener {
        return object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                safeExecute("處理背包資料更新") {
                    if (!isAdded || _binding == null) return@safeExecute

                    val user = snapshot.getValue(User::class.java) ?: return@safeExecute
                    updateSpinnerWithStockData(user)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("SettingsFragment", "載入背包資料失敗: ${error.message}", error.toException())
            }
        }
    }

    private fun updateSpinnerWithStockData(user: User) {
        val stockMap = createStockMap(user)
        val items = scratchOrder.map { ScratchTypeItem(it, stockMap[it] ?: 0) }
        val currentSelection = binding.spinnerScratchesCount.selectedItemPosition

        isUpdatingSpinner = true
        val adapter = buildStockAwareAdapter(items)
        binding.spinnerScratchesCount.adapter = adapter

        if (currentSelection >= 0 && currentSelection < adapter.count) {
            binding.spinnerScratchesCount.setSelection(currentSelection)
        }

        // ★ 延遲重置標記，確保 setSelection 的回調完成
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(100)
            isUpdatingSpinner = false
        }
    }

    private fun createStockMap(user: User): Map<Int, Int> {
        return mapOf(
            10 to (user.scratchType_10 ?: 0),
            20 to (user.scratchType_20 ?: 0),
            25 to (user.scratchType_25 ?: 0),
            30 to (user.scratchType_30 ?: 0),
            40 to (user.scratchType_40 ?: 0),
            50 to (user.scratchType_50 ?: 0),
            60 to (user.scratchType_60 ?: 0),
            80 to (user.scratchType_80 ?: 0),
            100 to (user.scratchType_100 ?: 0),
            120 to (user.scratchType_120 ?: 0),
            160 to (user.scratchType_160 ?: 0),
            200 to (user.scratchType_200 ?: 0),
            240 to (user.scratchType_240 ?: 0)
        )
    }

    // ===========================================
    // Spinner 和適配器相關方法
    // ===========================================

    private fun initSpinnerWithPlaceholder() {
        safeExecute("初始化 Spinner") {
            val items = scratchOrder.map { ScratchTypeItem(it, stock = 1) }
            val adapter = buildStockAwareAdapter(items)
            binding.spinnerScratchesCount.adapter = adapter
        }
    }

    private fun buildStockAwareAdapter(items: List<ScratchTypeItem>): ArrayAdapter<ScratchTypeItem> {
        return object : ArrayAdapter<ScratchTypeItem>(
            requireContext(),
            android.R.layout.simple_spinner_item,
            items
        ) {
            init {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

            override fun isEnabled(position: Int): Boolean {
                return try {
                    val item = getItem(position)
                    (item?.stock ?: 0) > 0
                } catch (e: Exception) {
                    false
                }
            }

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return createTextView(position, convertView, parent) {
                    super.getView(
                        position,
                        convertView,
                        parent
                    )
                }
            }

            override fun getDropDownView(
                position: Int,
                convertView: View?,
                parent: ViewGroup
            ): View {
                return createTextView(position, convertView, parent) {
                    super.getDropDownView(
                        position,
                        convertView,
                        parent
                    )
                }
            }

            private fun createTextView(
                position: Int,
                convertView: View?,
                parent: ViewGroup,
                defaultView: () -> View
            ): View {
                return try {
                    val view = defaultView() as TextView
                    val enabled = isEnabled(position)
                    view.setTextColor(if (enabled) Color.BLACK else Color.GRAY)
                    view
                } catch (e: Exception) {
                    TextView(requireContext()).apply {
                        text = "錯誤"
                        setTextColor(Color.RED)
                        setPadding(16, 8, 16, 8)
                    }
                }
            }
        }
    }

    // ===========================================
    // 工具方法
    // ===========================================

    private inline fun <T> safeExecute(
        operation: String,
        defaultValue: T? = null,
        action: () -> T
    ): T? {
        return try {
            action()
        } catch (e: Exception) {
            Log.e("SettingsFragment", "$operation 時發生錯誤: ${e.message}", e)
            defaultValue
        }
    }

    private fun setButtonsEnabled(
        save: Boolean = true,
        toggleInUse: Boolean = true,
        returnBtn: Boolean = true,
        delete: Boolean = true
    ) {
        binding.buttonSaveSettings.isEnabled = save
        binding.buttonToggleInuse.isEnabled = toggleInUse
        binding.buttonReturnSelected.isEnabled = returnBtn
        binding.buttonDeleteSelected.isEnabled = delete
    }

    private fun setSpinnerSelection(spinner: Spinner, targetValue: Int?) {
        if (targetValue == null) return

        safeExecute("設置 Spinner 選擇") {
            val adapter = spinner.adapter
            for (i in 0 until adapter.count) {
                if (adapter.getItem(i).toString().toIntOrNull() == targetValue) {
                    spinner.setSelection(i)
                    break
                }
            }
        }
    }

    private fun getUserFirebaseReference(): DatabaseReference? {
        val userKey = (requireActivity() as UserSessionProvider).getCurrentUserFirebaseKey()
        return if (userKey != null) {
            FirebaseDatabase.getInstance(AppConfig.DB_URL).reference.child("users").child(userKey)
        } else {
            Log.e("SettingsFragment", "無法取得用戶Key")
            null
        }
    }

    private fun clearSpinnerSelection() {
        safeExecute("清空 Spinner 選擇") {
            isUpdatingSpinner = true
            binding.spinnerScratchesCount.onItemSelectedListener = null

            if (binding.spinnerScratchesCount.adapter != null && binding.spinnerScratchesCount.adapter.count > 0) {
                binding.spinnerScratchesCount.setSelection(0)
            }

            setupSpinnerListeners()
            isUpdatingSpinner = false
        } ?: run {
            isUpdatingSpinner = false
            setupSpinnerListeners()
        }
    }

    private fun restorePreviewContainer() {
        safeExecute("恢復預覽容器") {
            binding.scratchBoardArea.removeAllViews()
        }
    }

    private fun clearTextFieldsOnly() {
        safeExecute("清空文字欄位") {
            binding.editTextSpecialPrize.setText("")
            binding.editTextGrandPrize.setText("")
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun getScratchDimensions(scratchType: Int): String {
        return when (scratchType) {
            10 -> "2x5"
            20 -> "4x5"
            25 -> "5x5"
            30 -> "5x6"
            40 -> "5x8"
            50 -> "5x10"
            60 -> "6x10"
            80 -> "8x10"
            100 -> "10x10"
            120 -> "10x12"
            160 -> "10x16"
            200 -> "10x20"
            240 -> "12x20"
            else -> "未知"
        }
    }

    override fun onDestroyView() {
        safeExecute("銷毀視圖") {
            removeReadonlyLabels()
            backpackListener?.let { listener ->
                userReference?.removeEventListener(listener)
            }
            backpackListener = null
            userReference = null
            currentPreviewFragment = null
            scratchTypeLabel = null
            _binding = null
        }
        super.onDestroyView()
    }
}