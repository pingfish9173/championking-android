package com.champion.king

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Layout
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
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
import android.text.SpannableStringBuilder
import android.text.method.ScrollingMovementMethod
import com.champion.king.util.ToastManager

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

    // ViewModel（✅ 改成 activity scope：避免按 HOME/多工回來後草稿消失）
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

    // ✅ 新增：readonly 狀態下「夾出/消費贈送」純文字顯示容器
    private var pitchRuleReadonlyContainer: LinearLayout? = null

    // 新增：標記是否正在進行儲存操作
    private var isSavingInProgress = false

    // ✅ 用來記住「切換前」的板位，避免 ShelfManager 點擊後 selectedShelfOrder 已變成新板位
    private var lastSelectedShelfOrder: Int? = null

    // ✅ 避免「程式碼 setSelection」後，Spinner 延遲觸發 onItemSelected 又把預覽 random 掉
    private var suppressNextScratchTypeSelectionEvent: Boolean = false

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

        binding.radioGroupPitchType.setOnCheckedChangeListener { _, checkedId ->
            applyPitchTypeUi(isShopping = (checkedId == R.id.radioPitchShopping), syncValues = true)
        }
        applyPitchTypeUi(isShopping = binding.radioPitchShopping.isChecked, syncValues = false)

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
        uiManager =  SettingsUIManager(
            binding = binding,
            context = requireContext(),
            childFragmentManager = childFragmentManager
        ) { message ->
            activity?.let {
                ToastManager.show(it, message)
            }
        }

        // 使用配置的閾值創建 ActionHandler
        actionHandler = SettingsActionHandler(
            viewModel,
            requireContext(),
            SCRATCH_SWITCH_THRESHOLD
        ) { message ->
            activity?.let {
                ToastManager.show(it, message)
            }
        }
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

        // 初始化：記住目前板位（避免第一次切換存不到）
        if (lastSelectedShelfOrder == null) {
            lastSelectedShelfOrder = shelfManager.selectedShelfOrder
        }

        shelfManager.setOnShelfClickListener { order ->
            // ✅ 先把「切換前」板位的草稿存起來（用 lastSelectedShelfOrder，不會存到新板位）
            lastSelectedShelfOrder?.let { prevOrder ->
                saveDraftIfNeeded(prevOrder)
            }

            // ✅ 更新為新板位
            lastSelectedShelfOrder = order

            // ✅ 切換顯示
            val selectedCard = viewModel.cards.value[order]
            if (selectedCard == null) {
                showUnsetShelfState() // 這裡會自動優先還原草稿（下面 2-6 會改）
            } else {
                showSetShelfState(selectedCard)
            }

            updateRemainingScratchesInfo(viewModel.cards.value)
        }
    }

    private fun setupClickListeners() {
        binding.buttonSaveSettings.setOnClickListener { handleSaveClick() }
        binding.buttonToggleInuse.setOnClickListener { handleToggleInUseClick() }
        binding.buttonReturnSelected.setOnClickListener { handleReturnClick() }
        binding.buttonDeleteSelected.setOnClickListener { handleDeleteClick() }
        binding.buttonRefreshScratch.setOnClickListener { handleRefreshScratchClick() }
        binding.buttonAutoScratch.setOnClickListener { handleAutoScratchClick() }

        // ✅ 新增：消費贈送模式下，點「消費X元」input → 跳客製化數字鍵盤
        binding.editClawsCount.setOnClickListener {
            // 保險：只在 shopping 模式響應
            if (!binding.radioPitchShopping.isChecked) return@setOnClickListener

            uiManager.showShoppingThresholdKeyboard(
                currentValue = binding.editClawsCount.text?.toString()
            ) { value ->
                binding.editClawsCount.setText(value.toString())
            }
        }

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

        // ✅ 特獎鉛筆圖標和輸入框點擊事件
        binding.buttonSpecialPrizeKeyboard.setOnClickListener {
            handleSpecialPrizeKeyboardClick()
        }
        binding.editTextSpecialPrize.setOnClickListener {
            handleSpecialPrizeKeyboardClick()
        }

        // ✅ 大獎鉛筆圖標和輸入框點擊事件
        binding.buttonGrandPrizeKeyboard.setOnClickListener {
            handleGrandPrizeKeyboardClick()
        }
        binding.editTextGrandPrize.setOnClickListener {
            handleGrandPrizeKeyboardClick()
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
                    // ★ 如果這次是「程式還原草稿」造成的 onItemSelected，就忽略（避免預覽被重建 random）
                    if (suppressNextScratchTypeSelectionEvent) {
                        suppressNextScratchTypeSelectionEvent = false
                        return
                    }

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

                        // ✅ 初始版位尚未選完前，避免 observeViewModel 把畫面先套到預設 1號版造成閃一下錯狀態
                        if (!isInitialSelectionComplete) {
                            return@collect
                        }

                        // ✅ 無論是否正在儲存，都先更新「剩餘刮數」顯示
                        updateRemainingScratchesInfo(cards)

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

    /**
     * 在「設置介面」左側面板底部的 TextView 顯示：
     * - 依目前選取的版位（selectedShelfOrder）顯示該版位的「剩餘/總數」
     * - 若該版位未設置或資料異常 → 清空並隱藏
     *
     * 注意：玩家頁面/台主首頁要顯示「使用中版位」的剩餘刮數，請由各自首頁的邏輯處理；
     *       此函式只負責「設置介面」的顯示規則。
     */
    private fun updateRemainingScratchesInfo(cards: Map<Int, ScratchCard>) {
        val activity = activity as? MainActivity ?: return
        val remainingView = activity.findViewById<TextView>(R.id.remaining_scratches_text_view) ?: return

        // ✅ 設置頁：以「目前選取的版位」為準
        val selectedOrder = shelfManager.selectedShelfOrder
        val card = cards[selectedOrder]

        if (card == null) {
            remainingView.text = ""
            remainingView.visibility = View.GONE
            return
        }

        val configs = card.numberConfigurations
        val total = configs?.size ?: card.scratchesType ?: 0

        if (total <= 0 || configs.isNullOrEmpty()) {
            remainingView.text = ""
            remainingView.visibility = View.GONE
            return
        }

        val remaining = configs.count { !it.scratched }
        remainingView.text = "$remaining/$total"
        remainingView.visibility = View.VISIBLE
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
            val display = arr.sorted().joinToString(", ")

            val spannable = SpannableStringBuilder(display)

            binding.editTextGrandPrize.apply {
                setText(spannable, TextView.BufferType.SPANNABLE)
                setHorizontallyScrolling(false)
                isSingleLine = false
                maxLines = 3
                // ✅ Android 6 以上都有：設定高品質換行策略
                breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                movementMethod = ScrollingMovementMethod.getInstance()
            }
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

    /** 顯示未設置狀態的預覽與按鈕狀態 **/
    private fun showUnsetShelfState() {
        val order = shelfManager.selectedShelfOrder
        val draft = viewModel.getDraft(order)

        // ✅ 未設置就是未設置：不要因為有草稿就改成 false
        isShowingUnsetState = true

        showEditableFields()
        showScratchTypeSpinner()

        if (draft != null && draft.scratchType != null) {
            // ✅ 有草稿：直接還原草稿（不要先清掉預覽）
            setScratchTypeSpinnerSelection(draft.scratchType)

            // 先建預覽（草稿有 configs 就帶入）
            displayScratchBoardPreview(draft.scratchType, draft.numberConfigurations)
            setPrizeControlsEnabled(true)

            // 還原文字
            binding.editTextSpecialPrize.setText(draft.specialPrize.orEmpty())
            binding.editTextGrandPrize.setText(draft.grandPrize.orEmpty())

            // ✅ 還原規則 UI（scratch / shopping）
            val isShopping = (draft.pitchType == "shopping")
            if (isShopping) {
                binding.radioPitchShopping.isChecked = true
                applyPitchTypeUi(isShopping = true, syncValues = false)

                // shopping：claws 當「消費門檻（元）」
                val spend = draft.claws ?: 0
                binding.editClawsCount.setText(spend.toString())
            } else {
                binding.radioPitchScratch.isChecked = true
                applyPitchTypeUi(isShopping = false, syncValues = false)

                // scratch：claws 當「夾出門檻（1~5）」
                val catchCount = (draft.claws ?: 1).coerceIn(1, 5)
                setSpinnerSelection(binding.spinnerClawsCount, catchCount)
            }

            // ✅ giveaway 永遠是 spinner（1~5）
            val give = (draft.giveaway ?: 1).coerceIn(1, 5)
            setSpinnerSelection(binding.spinnerGiveawayCount, give)

            // 預覽同步顯示選取（特獎/大獎）
            currentPreviewFragment?.setSelectedNumber(draft.specialPrize?.toIntOrNull())
            val gp = draft.grandPrize
                ?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
            currentPreviewFragment?.setGrandSelectedNumbers(gp)

        } else {
            // ✅ 沒草稿：才真的顯示「未設置」畫面並清空欄位
            showPreviewUnset()
            clearTextFieldsOnly()
            clearSpinnerSelection()
            setPrizeControlsEnabled(false)

            // ✅ 同時把規則 UI 回到預設（避免上一個板位的 shopping 狀態殘留）
            binding.radioPitchScratch.isChecked = true
            applyPitchTypeUi(isShopping = false, syncValues = false)
        }

        setButtonsEnabled(save = true, toggleInUse = false, autoScratch = false, returnBtn = false, delete = false)
        uiManager.updateInUseButtonUI(null)
        uiManager.updateActionButtonsUI(null)
        updateRefreshButtonVisibility()
    }

    /** 🔘 根據目前狀態顯示／隱藏重新整理圖示 **/
    private fun updateRefreshButtonVisibility() {
        val order = shelfManager.selectedShelfOrder
        val hasCard = viewModel.cards.value[order] != null

        // ✅ 沒設置卡片（未設置狀態）就顯示刷新按鈕；有卡片就隱藏
        binding.buttonRefreshScratch.visibility = if (hasCard) View.GONE else View.VISIBLE
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
                // 使用中：不允許保存、返回、刪除，但可以切換使用狀態、自動刮開
                setButtonsEnabled(save = false, toggleInUse = true, autoScratch = true, returnBtn = false, delete = false)
            } else {
                // 已被刮過但非使用中：不允許保存、返回，刪除需要額外檢查，可以自動刮開
                setButtonsEnabled(save = false, toggleInUse = true, autoScratch = true, returnBtn = false, delete = true)
            }
        } else {
            displayScratchCardDetails(selectedCard)
            setButtonsEnabled(save = true, toggleInUse = true, autoScratch = true, returnBtn = true, delete = true)
        }

        showScratchTypeLabel(selectedCard.scratchesType)
        uiManager.updateInUseButtonUI(selectedCard)
        uiManager.updateActionButtonsUI(selectedCard)
        updateRefreshButtonVisibility()
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
        val isShopping = binding.radioPitchShopping.isChecked
        val pitchType = if (isShopping) "shopping" else "scratch"

        // ✅ claws 的來源依模式決定：
        // - scratch：spinner 1~5
        // - shopping：editClawsCount（0以上整數，空視為0）
        val clawsValue: Int? = if (isShopping) {
            val t = binding.editClawsCount.text?.toString()?.trim().orEmpty()
            if (t.isEmpty()) 0 else t.toIntOrNull()  // 若不是數字，先回 null，後面存檔前可再擋
        } else {
            binding.spinnerClawsCount.selectedItem?.toString()?.toIntOrNull()
        }

        return SaveData(
            order = shelfManager.selectedShelfOrder,
            scratchType = scratchType,
            specialPrize = binding.editTextSpecialPrize.text?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() },
            grandPrize = binding.editTextGrandPrize.text?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() },

            pitchType = pitchType,
            claws = clawsValue,

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

        val pitchType: String,   // ✅ 新增

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
                            // ✅ 儲存成功（已經變成正式資料）→ 清掉該板位草稿
                            viewModel.clearDraft(data.order)

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
                    // ✅ 儲存成功（正式資料已存在）→ 也清掉草稿（保險）
                    viewModel.clearDraft(data.order)

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
            keepInUsed = existingCard?.inUsed ?: false,
            pitchType = data.pitchType // ✅ 新增
        )
    }

    private fun handleToggleInUseClick() {
        actionHandler.handleToggleInUse(shelfManager.selectedShelfOrder, viewModel.cards.value)
    }

    private fun handleReturnClick() {
        actionHandler.handleReturn(shelfManager.selectedShelfOrder, viewModel.cards.value)
    }

    private fun handleDeleteClick() {
        val order = shelfManager.selectedShelfOrder

        // ✅ 刪除前先清掉草稿（保險：避免 UI 還原草稿造成誤判）
        viewModel.clearDraft(order)

        actionHandler.handleDelete(order, viewModel.cards.value)
    }

    /** 🔄 刮數重新整理按鈕邏輯 **/
    private fun handleRefreshScratchClick() {
        val selectedItem = binding.spinnerScratchesCount.selectedItem as? ScratchTypeItem
        if (selectedItem == null) {
            showToast("請先選擇刮數")
            return
        }

        val scratchType = selectedItem.getScratchType()
        val stock = selectedItem.stock

        if (stock <= 0) {
            showToast("此刮數無庫存，無法重新整理")
            return
        }

        showToast("重新生成 ${scratchType}刮 配置中…")

        // 重新建立新的隨機預覽板
        currentPreviewFragment = ScratchBoardPreviewFragment.newInstance(
            "${scratchType}刮 (${getScratchDimensions(scratchType)})"
        )

        // 更新預覽區域
        childFragmentManager.beginTransaction()
            .replace(binding.scratchBoardArea.id, currentPreviewFragment!!)
            .commitAllowingStateLoss()

        // 清空特獎與大獎欄位
        binding.editTextSpecialPrize.text?.clear()
        binding.editTextGrandPrize.text?.clear()

        setPrizeControlsEnabled(true)
        // ✅ 刷新後立刻把新配置寫進草稿，避免回來又用舊的
        saveDraftIfNeeded(shelfManager.selectedShelfOrder)
    }

    /** 統一設定特獎、大獎按鈕與鍵盤按鈕的啟用 / 透明度 **/
    private fun setPrizeControlsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1.0f else 0.5f

        // 特獎 & 大獎主按鈕
        binding.buttonPickSpecialPrize.isEnabled = enabled
        binding.buttonPickGrandPrize.isEnabled = enabled
        binding.buttonPickSpecialPrize.alpha = alpha
        binding.buttonPickGrandPrize.alpha = alpha

        // 鉛筆（鍵盤）按鈕
        binding.buttonSpecialPrizeKeyboard.isEnabled = enabled
        binding.buttonGrandPrizeKeyboard.isEnabled = enabled
        binding.buttonSpecialPrizeKeyboard.alpha = alpha
        binding.buttonGrandPrizeKeyboard.alpha = alpha

        // 編輯框（只有未設置時才 disable，因此跟隨 enabled）
        binding.editTextSpecialPrize.isEnabled = enabled
        binding.editTextGrandPrize.isEnabled = enabled
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

            // ✅ 關鍵：由 pitchType 決定 claws 門檻要套到 spinner 還是 editText
            // ✅ giveaway 永遠套到 spinner
            applySavedPitchRule(card)

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
            // ✅ 顯示只讀的標籤（特獎/大獎）
            showReadonlyFields(card)

            // 預覽區保持顯示
            displayScratchBoardPreview(card.scratchesType, card.numberConfigurations)

            // 預覽建立後，顯示當前的特獎和大獎標記
            currentPreviewFragment?.setSelectedNumber(card.specialPrize?.toIntOrNull())
            val grandList = card.grandPrize?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
            currentPreviewFragment?.setGrandSelectedNumbers(grandList)
        }
    }

    // 顯示可編輯欄位
    private fun showEditableFields() {
        // 移除只讀標籤（特獎/大獎那塊）
        removeReadonlyLabels()

        // ✅ 回到可編輯時，把 pitch readonly 文案隱藏
        hidePitchRuleReadonly()

        // 顯示原有的編輯容器
        showEditableContainers()

        // ✅ 保持你目前 radio 切換的 UI 狀態（spinner / edit）
        val isShopping = binding.radioPitchShopping.isChecked
        applyPitchTypeUi(isShopping = isShopping, syncValues = false)
    }

    // 顯示可編輯的容器
    private fun showEditableContainers() {
        // 找到特獎和大獎的整個容器並顯示
        val specialPrizeContainer = findViewContaining(binding.buttonPickSpecialPrize)
        val grandPrizeContainer = findViewContaining(binding.buttonPickGrandPrize)

        specialPrizeContainer?.visibility = View.VISIBLE
        grandPrizeContainer?.visibility = View.VISIBLE

        // 確保按鈕和編輯框是可用狀態
        setPrizeControlsEnabled(true)
        binding.editTextSpecialPrize.visibility = View.VISIBLE
        binding.editTextGrandPrize.visibility = View.VISIBLE
    }

    // 顯示只讀欄位
    private fun showReadonlyFields(card: ScratchCard) {
        // 完全隱藏特獎/大獎的可編輯容器
        hideEditableContainers()

        // 創建並顯示只讀標籤（特獎/大獎那塊）
        createReadonlyLabels(card)

        // ✅ 顯示 pitch 規則 readonly（這就是你現在跑位的那段，改用 XML 佔位顯示）
        showPitchRuleReadonly(card)
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

    private fun removePitchRuleReadonlyContainer() {
        pitchRuleReadonlyContainer?.let { container ->
            (container.parent as? ViewGroup)?.removeView(container)
        }
        pitchRuleReadonlyContainer = null
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
        val selectedCard = viewModel.cards.value[shelfManager.selectedShelfOrder]

        // ✅ 只有「未設置卡片」時才會改預覽（已設置卡片一律不動）
        if (selectedCard != null) return

        Log.d("SettingsFragment", "未設置板位：立即更新預覽為 ${scratchType}刮")

        // ✅ 這裡傳 null 代表「新生成」（符合你調刮數就要立刻看到的需求）
        displayScratchBoardPreview(scratchType, null)
        setPrizeControlsEnabled(true)

        // ✅ 立刻把新生成的 numberConfigurations 存進草稿，避免切換板位後被重置
        saveDraftIfNeeded(shelfManager.selectedShelfOrder)
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

    private fun showPitchRuleReadonly(card: ScratchCard) {
        val isShopping = (card.pitchType == "shopping")
        val threshold = card.clawsCount ?: 0
        val giveaway = card.giveawayCount ?: 0

        // ✅ 顯示 readonly 文字（固定顯示在 XML 的正確位置）
        binding.textPitchRuleReadonly.text = if (isShopping) {
            "消費 $threshold 刮 $giveaway"
        } else {
            "夾 $threshold 刮 $giveaway"
        }
        binding.textPitchRuleReadonly.visibility = View.VISIBLE

        // ✅ readonly 規則：隱藏 radio 區塊
        binding.radioGroupPitchType.visibility = View.GONE

        // ✅ readonly 規則：把可編輯 X（spinner/edit）都隱藏，避免誤會可修改
        binding.spinnerClawsCount.visibility = View.GONE
        binding.editClawsCount.visibility = View.GONE
        binding.spinnerGiveawayCount.visibility = View.GONE

        // ✅ 另外：把「夾出/消費」「樣/元」「贈送」「刮」這些 label 也隱藏（避免留空）
        // 注意：下面這幾個 id 你如果命名不同，請換成你實際的 binding 名稱
        binding.textClawsPrefix.visibility = View.GONE
        binding.textClawsUnit.visibility = View.GONE
        binding.textGiveawayPrefix.visibility = View.GONE
        binding.textGiveawayUnit.visibility = View.GONE
    }

    private fun hidePitchRuleReadonly() {
        binding.textPitchRuleReadonly.visibility = View.GONE

        // 回到可編輯狀態：label 先打開（接著 applyPitchTypeUi 會決定顯示 spinner 或 edit）
        binding.textClawsPrefix.visibility = View.VISIBLE
        binding.textClawsUnit.visibility = View.VISIBLE
        binding.textGiveawayPrefix.visibility = View.VISIBLE
        binding.textGiveawayUnit.visibility = View.VISIBLE

        binding.radioGroupPitchType.visibility = View.VISIBLE
        binding.spinnerGiveawayCount.visibility = View.VISIBLE

        // claws 的 spinner / edit 由你既有的 applyPitchTypeUi(isShopping=...) 控制
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
                return createCustomTextView(position, convertView, parent) {
                    super.getView(position, convertView, parent)
                }
            }

            override fun getDropDownView(
                position: Int,
                convertView: View?,
                parent: ViewGroup
            ): View {
                return createCustomTextView(position, convertView, parent) {
                    super.getDropDownView(position, convertView, parent)
                }
            }

            /** 🔹 自訂每一行的文字樣式（縮小字體、防裁切） **/
            private fun createCustomTextView(
                position: Int,
                convertView: View?,
                parent: ViewGroup,
                defaultView: () -> View
            ): View {
                return try {
                    val view = defaultView() as TextView
                    val enabled = isEnabled(position)
                    view.setTextColor(if (enabled) Color.BLACK else Color.GRAY)
                    view.textSize = 13f            // ✅ 調小字體
                    view.setPadding(12, 6, 12, 6)  // ✅ 減少內邊距
                    view.isSingleLine = true       // ✅ 單行顯示
                    view.ellipsize = android.text.TextUtils.TruncateAt.END // ✅ 超出用…
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

    // ✅ 新增：處理特獎數字鍵盤點擊
    private fun handleSpecialPrizeKeyboardClick() {
        val selectedScratchType = getCurrentScratchType()
        if (selectedScratchType == null) {
            showToast("請先選擇刮數")
            return
        }

        val currentValue = binding.editTextSpecialPrize.text.toString()

        uiManager.showSpecialPrizeKeyboard(
            currentValue = if (currentValue.isEmpty()) null else currentValue,
            currentScratchType = selectedScratchType,
            onConfirm = { validatedInput ->

                // 將特獎輸入去掉前導 0
                val specialPrizeNumber = validatedInput.toIntOrNull()
                if (specialPrizeNumber == null) {
                    showToast("無效的特獎數字")
                    return@showSpecialPrizeKeyboard
                }

                val cleaned = specialPrizeNumber.toString()  // 移除前導 0

                // === ⭐ 驗證：特獎不可與大獎重複 ===
                val grandText = binding.editTextGrandPrize.text.toString()
                if (grandText.isNotEmpty()) {
                    val grandList = grandText.split(",")
                        .map { it.trim() }
                        .mapNotNull { it.toIntOrNull() }

                    if (grandList.contains(specialPrizeNumber)) {
                        showToast("特獎不能與大獎重複！")
                        return@showSpecialPrizeKeyboard  // ❗ 重要：視窗不關閉，輸入不清空
                    }
                }

                // === 以上驗證全部通過才會執行以下更新 ===
                binding.editTextSpecialPrize.setText(cleaned)

                currentPreviewFragment?.setSelectedNumber(specialPrizeNumber)

                showToast("特獎已設定：$cleaned")
            }
        )
    }

    // ✅ 新增：處理大獎數字鍵盤點擊
    private fun handleGrandPrizeKeyboardClick() {
        val selectedScratchType = getCurrentScratchType()
        if (selectedScratchType == null) {
            showToast("請先選擇刮數")
            return
        }

        val currentValue = binding.editTextGrandPrize.text.toString()

        uiManager.showGrandPrizeKeyboard(
            currentValue = if (currentValue.isEmpty()) null else currentValue,
            currentScratchType = selectedScratchType,
            onConfirm = { validatedInput ->

                // === 清洗：拆分、去空白、移除前導零 ===
                val cleanedList = validatedInput.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { token -> token.toIntOrNull()?.toString() }

                if (cleanedList.isEmpty()) {
                    showToast("無效的大獎數字")
                    return@showGrandPrizeKeyboard
                }

                // 轉成 Int 並排序
                val sortedList = cleanedList.mapNotNull { it.toIntOrNull() }.sorted()

                // === ⭐ 驗證：大獎不可包含特獎 ===
                val specialText = binding.editTextSpecialPrize.text.toString()
                val specialNumber = specialText.toIntOrNull()

                if (specialNumber != null && sortedList.contains(specialNumber)) {
                    showToast("大獎不能包含特獎數字！")
                    return@showGrandPrizeKeyboard  // ❗ 重要：視窗不關閉，輸入不清空
                }

                // === 全驗證通過 → 更新 ===
                val sortedText = sortedList.joinToString(", ")
                binding.editTextGrandPrize.setText(sortedText)

                currentPreviewFragment?.setGrandSelectedNumbers(sortedList)

                showToast("大獎已設定：$sortedText")
            }
        )
    }

    // ===========================================
    // ✅ 自動刮開
    // ===========================================

    private fun handleAutoScratchClick() {
        val order = shelfManager.selectedShelfOrder
        val selectedCard = viewModel.cards.value[order]
        if (selectedCard == null) {
            showToast("此板位尚未設置刮板")
            return
        }

        val configs = selectedCard.numberConfigurations
        if (configs.isNullOrEmpty()) {
            showToast("此刮板沒有數字配置，無法自動刮開")
            return
        }

        // 計算可刮的最大數量（扣掉：已刮 + 特獎 + 大獎 的聯集）
        val maxX = calcAutoScratchMaxX(selectedCard)
        if (maxX <= 0) {
            showToast("沒有可刮開的格子（已刮/特獎/大獎皆已占滿）")
            return
        }

        showAutoScratchInputDialog(maxX = maxX) { x ->
            showAutoScratchConfirmDialog(x) {
                performAutoScratch(selectedCard, x)
            }
        }
    }

    /**
     * ✅ X 上限規則：
     * maxX = 可刮格子數量（排除：已刮 + 特獎 + 大獎 的聯集）
     */
    private fun calcAutoScratchMaxX(card: ScratchCard): Int {
        val configs = card.numberConfigurations ?: return 0

        val special = card.specialPrize?.toIntOrNull()
        val grandSet = (card.grandPrize ?: "")
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()

        // 不可刮集合（聯集）：已刮 + 特獎 + 大獎
        val unavailableNumbers = mutableSetOf<Int>()
        configs.filter { it.scratched }.forEach { unavailableNumbers.add(it.number) }
        if (special != null) unavailableNumbers.add(special)
        unavailableNumbers.addAll(grandSet)

        // ✅ 真正可刮的格子：尚未 scratched 且 number 不在 unavailableNumbers
        val eligible = configs.filter { !it.scratched && !unavailableNumbers.contains(it.number) }
        return eligible.size
    }

    /**
     * ✅ 參考商城的自訂數字鍵盤（dialog_quantity_input）
     * - 輸入時允許 0 / 空字串（方便刪掉重打）
     * - 按確定才檢查：必須在 1..maxX，否則 Toast 告警且不關閉 dialog
     */
    private fun showAutoScratchInputDialog(
        maxX: Int,
        onConfirm: (Int) -> Unit
    ) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_quantity_input, null)

        val edit = dialogView.findViewById<EditText>(R.id.dialog_quantity_edit)

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

        // ✅ 預設空白（你也可改成 "0"）
        edit.setText("")
        edit.setSelection(edit.text.length)

        // ✅ 禁用系統鍵盤
        edit.showSoftInputOnFocus = false

        fun getText(): String = edit.text?.toString() ?: ""
        fun setText(t: String) {
            edit.setText(t)
            edit.setSelection(edit.text.length)
        }

        fun currentValue(): Int = getText().toIntOrNull() ?: 0

        // ✅ 允許 0..maxX（編輯中不強迫最小=1）
        fun setValue(v: Int) {
            val value = v.coerceIn(0, maxX)
            setText(value.toString())
        }

        // +/-：允許到 0
        btnMinus.setOnClickListener { setValue(currentValue() - 1) }
        btnPlus.setOnClickListener { setValue(currentValue() + 1) }

        // 清除：清成空字串（完全可重打）
        btnClear.setOnClickListener {
            setText("")
        }

        // 退格：允許刪到空
        btnDelete.setOnClickListener {
            val t = getText()
            val newText = if (t.isNotEmpty()) t.dropLast(1) else ""
            setText(newText)
        }

        // 0~9：採「在尾端追加」模式（因為你禁用了系統鍵盤）
        val numberClickListener = View.OnClickListener { v ->
            val digit = (v as Button).text.toString()
            val current = getText()

            // 讓輸入更順手：避免前導 0 一直堆疊（例如 0005 → 5）
            val merged = (current + digit)
            val normalized = merged.trimStart('0')
            val finalText = if (normalized.isEmpty()) "0" else normalized

            val value = finalText.toIntOrNull() ?: 0
            if (value > maxX) {
                setValue(maxX)
            } else {
                setText(finalText)
            }
        }

        listOf(btn0, btn1, btn2, btn3, btn4, btn5, btn6, btn7, btn8, btn9)
            .forEach { it.setOnClickListener(numberClickListener) }

        val dlg = AlertDialog.Builder(requireContext())
            .setTitle("自動刮開設定（0～$maxX）")
            .setView(dialogView)
            .setPositiveButton("確定", null) // ✅ 攔截：不要自動關閉
            .setNegativeButton("取消", null)
            .create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val x = currentValue()

                // ✅ 按確定才防呆
                if (x <= 0) {
                    showToast("請輸入 1～$maxX")
                    return@setOnClickListener
                }
                if (x > maxX) {
                    showToast("最大可刮開數量為 $maxX")
                    return@setOnClickListener
                }

                onConfirm(x)
                dlg.dismiss()
            }
        }

        dlg.show()
    }

    /**
     * ✅ 二次確認視窗
     */
    private fun showAutoScratchConfirmDialog(x: Int, onConfirm: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle("確認刮開")
            .setMessage("系統將隨機刮開 $x 刮（不會刮開特獎及大獎），是否確定刮開？")
            .setPositiveButton("確定") { d, _ ->
                onConfirm()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * ✅ 真正執行自動刮開：
     * 1) 隨機挑 eligible 的 X 格 → 設 scratched=true
     * 2) 預覽區立即刷新
     * 3) 寫回 Firebase（透過 viewModel.upsertCard）
     */
    private fun performAutoScratch(card: ScratchCard, x: Int) {
        val order = shelfManager.selectedShelfOrder
        val configs = card.numberConfigurations?.map { it.copy() }?.toMutableList() ?: run {
            showToast("數字配置讀取失敗")
            return
        }

        val special = card.specialPrize?.toIntOrNull()
        val grandSet = (card.grandPrize ?: "")
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()

        val unavailableNumbers = mutableSetOf<Int>()
        configs.filter { it.scratched }.forEach { unavailableNumbers.add(it.number) }
        if (special != null) unavailableNumbers.add(special)
        unavailableNumbers.addAll(grandSet)

        val eligibleIdx = configs
            .mapIndexedNotNull { idx, cfg ->
                if (!cfg.scratched && !unavailableNumbers.contains(cfg.number)) idx else null
            }
            .shuffled()
            .take(x)

        if (eligibleIdx.isEmpty()) {
            showToast("沒有可刮開的格子")
            return
        }

        val scratchedNumbers = mutableSetOf<Int>()
        eligibleIdx.forEach { idx ->
            configs[idx].scratched = true
            scratchedNumbers.add(configs[idx].number)
        }

        // ✅ 讓預覽區立刻顯示刮開
        currentPreviewFragment?.scratchNumbers(scratchedNumbers)

        // ✅ 寫回資料（保留原本欄位）
        viewModel.upsertCard(
            order = order,
            scratchesType = card.scratchesType ?: getCurrentScratchType() ?: 0,
            specialPrize = card.specialPrize,
            grandPrize = card.grandPrize,
            clawsCount = card.clawsCount,
            giveawayCount = card.giveawayCount,
            numberConfigurations = configs,
            existingSerial = card.serialNumber,
            keepInUsed = card.inUsed
        )

        // ✅ 立即更新「剩餘刮數」顯示（不等 viewModel 回推）
        val tempCards = viewModel.cards.value.toMutableMap()
        tempCards[order] = card.copy(numberConfigurations = configs)
        updateRemainingScratchesInfo(tempCards)

        showToast("已自動刮開 ${eligibleIdx.size} 格")
    }

    // ✅ 新增：獲取當前選擇的刮數
    private fun getCurrentScratchType(): Int? {
        return try {
            val selectedItem = binding.spinnerScratchesCount.selectedItem

            // 處理 ScratchTypeItem 類型（從你現有的代碼中）
            when (selectedItem) {
                is ScratchTypeItem -> selectedItem.getScratchType()
                is String -> {
                    // 從字符串中提取刮數（例如 "10刮 (剩5)" -> 10）
                    val regex = Regex("(\\d+)刮")
                    val match = regex.find(selectedItem)
                    match?.groupValues?.get(1)?.toInt()
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e("SettingsFragment", "無法獲取當前刮數", e)
            null
        }
    }

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
        autoScratch: Boolean = true,
        returnBtn: Boolean = true,
        delete: Boolean = true
    ) {
        binding.buttonSaveSettings.isEnabled = save
        binding.buttonToggleInuse.isEnabled = toggleInUse
        binding.buttonAutoScratch.isEnabled = autoScratch
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
        activity?.let {
            ToastManager.show(it, message)
        }
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

    override fun onStop() {
        super.onStop()
        // ✅ 按 HOME / 多工鍵離開時：把目前板位草稿存起來
        saveDraftIfNeeded(shelfManager.selectedShelfOrder)
    }

    // ✅ 只在「未設置狀態 / 尚未儲存」時保存草稿（避免覆蓋已設置卡片的正式資料）
    private fun saveDraftIfNeeded(order: Int) {
        val hasCard = viewModel.cards.value[order] != null
        if (hasCard) return

        // ✅ 對應該板位的預覽：若剛好沒有 preview（例如尚未選刮數），configs 就留 null
        val configs = currentPreviewFragment?.getGeneratedNumberConfigurations()

        val selectedItem = binding.spinnerScratchesCount.selectedItem as? ScratchTypeItem
        val scratchType = selectedItem?.getScratchType()

        // ✅ 草稿也要記住目前規則
        val isShopping = binding.radioPitchShopping.isChecked
        val pitchType = if (isShopping) "shopping" else "scratch"

        // ✅ claws 的來源依模式決定：
        // - scratch：spinner 1~5
        // - shopping：editClawsCount（0以上整數，空視為0）
        val clawsValue: Int? = if (isShopping) {
            val t = binding.editClawsCount.text?.toString()?.trim().orEmpty()
            if (t.isEmpty()) 0 else t.toIntOrNull()
        } else {
            binding.spinnerClawsCount.selectedItem?.toString()?.toIntOrNull()
        }

        val draft = SettingsViewModel.SettingsDraft(
            scratchType = scratchType,
            specialPrize = binding.editTextSpecialPrize.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            grandPrize = binding.editTextGrandPrize.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            claws = clawsValue,
            giveaway = binding.spinnerGiveawayCount.selectedItem?.toString()?.toIntOrNull(),
            numberConfigurations = configs,
            pitchType = pitchType
        )

        viewModel.saveDraft(order, draft)
    }

    // ✅ 依 scratchType（Int）把 spinner 指到對應項目（adapter 是 ScratchTypeItem）
    private fun setScratchTypeSpinnerSelection(scratchType: Int) {
        isUpdatingSpinner = true
        try {
            val adapter = binding.spinnerScratchesCount.adapter ?: return
            val currentPos = binding.spinnerScratchesCount.selectedItemPosition

            var targetPos: Int? = null
            for (i in 0 until adapter.count) {
                val item = adapter.getItem(i) as? ScratchTypeItem ?: continue
                if (item.getScratchType() == scratchType) {
                    targetPos = i
                    break
                }
            }
            if (targetPos == null) return

            // ✅ 只有「真的會變更選擇」才 suppress 下一次事件
            if (targetPos != currentPos) {
                suppressNextScratchTypeSelectionEvent = true
                binding.spinnerScratchesCount.setSelection(targetPos)
            } else {
                // 同一個 selection，不要 suppress，避免卡住下一次使用者操作
                suppressNextScratchTypeSelectionEvent = false
            }
        } finally {
            isUpdatingSpinner = false
        }
    }

    private fun applyPitchTypeUi(isShopping: Boolean, syncValues: Boolean = true) {

        // ✅ 文字切換：夾出/樣 ↔ 消費/元
        binding.textClawsPrefix.text = if (isShopping) "消費" else "夾出"
        binding.textClawsUnit.text = if (isShopping) "元" else "樣"

        if (isShopping) {
            // shopping：只切換「觸發門檻」(claws)
            binding.spinnerClawsCount.visibility = View.GONE
            binding.editClawsCount.visibility = View.VISIBLE

            // 贈送永遠用 spinner（不切換）
            binding.spinnerGiveawayCount.visibility = View.VISIBLE

            if (syncValues) {
                // spinner -> input（只同步 claws）
                val claws = binding.spinnerClawsCount.selectedItem?.toString()?.toIntOrNull() ?: 1
                binding.editClawsCount.setText(claws.toString())
            }
        } else {
            // scratch：只切換「觸發門檻」(claws)
            binding.spinnerClawsCount.visibility = View.VISIBLE
            binding.editClawsCount.visibility = View.GONE

            // 贈送永遠用 spinner（不切換）
            binding.spinnerGiveawayCount.visibility = View.VISIBLE

            if (syncValues) {
                // input -> spinner（spinner 只有 1-5，所以壓回 1..5）
                val claws = binding.editClawsCount.text?.toString()?.toIntOrNull() ?: 1
                setSpinnerSelection(binding.spinnerClawsCount, claws.coerceIn(1, 5))
            }
        }
    }

    private fun applySavedPitchRule(card: ScratchCard?) {
        // 沒卡片/沒資料：維持預設（夾出贈送 + spinner）
        if (card == null) {
            binding.radioPitchScratch.isChecked = true
            applyPitchTypeUi(isShopping = false, syncValues = false)
            return
        }

        val isShopping = (card.pitchType == "shopping")
        if (isShopping) {
            binding.radioPitchShopping.isChecked = true
            applyPitchTypeUi(isShopping = true, syncValues = false)

            // clawsCount 在 shopping 模式代表「消費門檻（元）」
            val v = card.clawsCount ?: 0
            binding.editClawsCount.setText(v.toString())
        } else {
            binding.radioPitchScratch.isChecked = true
            applyPitchTypeUi(isShopping = false, syncValues = false)

            // clawsCount 在 scratch 模式代表「夾出門檻（1-5）」
            val v = (card.clawsCount ?: 1).coerceIn(1, 5)
            setSpinnerSelection(binding.spinnerClawsCount, v)
        }

        // giveaway 永遠是 spinner（1-5）
        val give = (card.giveawayCount ?: 1).coerceIn(1, 5)
        setSpinnerSelection(binding.spinnerGiveawayCount, give)
    }

}