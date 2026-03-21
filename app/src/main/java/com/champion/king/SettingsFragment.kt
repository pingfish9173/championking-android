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

        // 🌟 分割版面專屬：跨畫面的草稿快取 (依據 order 儲存，確保切換 Fragment 不遺失)
        val splitBoardSpecialPrizesCache = mutableMapOf<Int, MutableMap<String, String>>()
        val splitBoardGrandPrizesCache = mutableMapOf<Int, MutableMap<String, String>>()
        val splitBoardPitchTypesCache = mutableMapOf<Int, MutableMap<String, String>>()
        val splitBoardClawsCountsCache = mutableMapOf<Int, MutableMap<String, String>>()
        val splitBoardGiveawayCountsCache = mutableMapOf<Int, MutableMap<String, Int>>()
        val splitBoardConfigurationsCache = mutableMapOf<Int, MutableMap<String, List<NumberConfiguration>>>()

        // 🌟 清除特定板位的快取
        fun clearSplitDraftCache(order: Int) {
            splitBoardSpecialPrizesCache.remove(order)
            splitBoardGrandPrizesCache.remove(order)
            splitBoardPitchTypesCache.remove(order)
            splitBoardClawsCountsCache.remove(order)
            splitBoardGiveawayCountsCache.remove(order)
            splitBoardConfigurationsCache.remove(order)
        }

        // 🌟 新增：一鍵清空所有快取 (用於登出與關閉APP時)
        fun clearAllSplitDraftCaches() {
            splitBoardSpecialPrizesCache.clear()
            splitBoardGrandPrizesCache.clear()
            splitBoardPitchTypesCache.clear()
            splitBoardClawsCountsCache.clear()
            splitBoardGiveawayCountsCache.clear()
            splitBoardConfigurationsCache.clear()
        }
    }

    // 大獎數量限制表
    private val GRAND_LIMITS = mapOf(
        10 to 3,
        20 to 15,
        25 to 15,
        30 to 15,
        40 to 15,
        50 to 15,
        60 to 15,
        80 to 15,
        100 to 15,
        120 to 15,
        160 to 15,
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

    private data class ScratchTypeItem(
        val typeStr: String,
        val stock: Int,
        val isPlaceholder: Boolean = false,
        /** RENTAL 吃到飽：不顯示庫存字樣 */
        val showStockInfo: Boolean = true,
        /** RENTAL 吃到飽：即使 stock=0 也允許選擇 */
        val selectableWithoutStock: Boolean = false
    ) {
        override fun toString(): String {
            // 🌟 核心修改：如果是有帶文字的 placeholder，直接顯示該文字（例如商城提示）
            if (isPlaceholder) return typeStr.ifEmpty { "請選擇" }

            val displayTypeStr = if (typeStr.contains("x")) {
                val parts = typeStr.split("x")
                if (parts.size == 2) {
                    "${parts[0]}刮x${parts[1]}板"
                } else {
                    "${typeStr}刮" // 防呆兜底
                }
            } else {
                "${typeStr}刮" // 單一版面
            }

            return when {
                !showStockInfo -> displayTypeStr
                stock > 0 -> "$displayTypeStr (剩${stock})"
                else -> "$displayTypeStr (剩0)"
            }
        }

        fun getScratchTypeString(): String? = if (isPlaceholder) null else typeStr
    }

    // 🌟 修改點 2：加入分割版面清單
    private val scratchOrder = listOf("10", "20", "25", "30", "40", "50", "60", "80", "100", "120", "160", "200", "240", "20x4", "20x6", "25x4", "25x6", "30x4", "30x6")

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

    // ✅ 新增：記住目前帳號的計費模式（POINT / RENTAL），用於 UI 規則切換
    private var currentBillingMode: String = "POINT"

    // ==========================================
    // 🌟 分割版面專屬：子板特獎/大獎暫存區與即時監聽
    // ==========================================
    private val splitBoardSpecialPrizes = mutableMapOf<String, String>()
    private val splitBoardGrandPrizes = mutableMapOf<String, String>()

    // 🌟 新增：子板專屬的玩法規則暫存區
    private val splitBoardPitchTypes = mutableMapOf<String, String>()
    private val splitBoardClawsCounts = mutableMapOf<String, String>()
    private val splitBoardGiveawayCounts = mutableMapOf<String, Int>()
    private val splitSpecialPrizeWatcher = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            currentFocusedSubBoardId?.let { boardName ->
                splitBoardSpecialPrizes[boardName] = s.toString()
                renderSubBoardHeaderUI(boardName)
                updateSubBoardCellsUI(boardName) // 🌟 新增：同步更新格子的外圈顏色
            }
        }
    }
    private val splitGrandPrizeWatcher = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            currentFocusedSubBoardId?.let { boardName ->
                splitBoardGrandPrizes[boardName] = s.toString()
                renderSubBoardHeaderUI(boardName)
                updateSubBoardCellsUI(boardName) // 🌟 新增：同步更新格子的外圈顏色
            }
        }
    }
    // 🌟 新增：分割版面專屬的格子數字配置暫存區
    private val splitBoardConfigurations = mutableMapOf<String, List<NumberConfiguration>>()

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

        // ✅ 修正：進入設置頁面第一時間，不要先顯示「1號板參數設定」
        // 因為此時 cards 還沒載入，使用中板位也尚未決定
        updateParametersTitle(null)     // 顯示「板位參數設定」
        hideRightPanel()                // 保險：右側參數區先不要露出錯誤資訊

        // 設置初始選中的版位（等 cards 進來後，會 selectShelf(inUseOrder) 並更新成正確標題）
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

        // 🌟 新增：子板專用返回母板按鈕的點擊事件
        binding.buttonBackToMaster.setOnClickListener { exitSubBoardFocusMode() }

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
                    if (suppressNextScratchTypeSelectionEvent) {
                        suppressNextScratchTypeSelectionEvent = false
                        return
                    }

                    if (isUpdatingSpinner || isSavingInProgress) return

                    val selectedItem =
                        binding.spinnerScratchesCount.selectedItem as? ScratchTypeItem ?: return

                    val scratchTypeStr = selectedItem.getScratchTypeString()

                    if (scratchTypeStr == null) {
                        showUnsetShelfState()
                        return
                    }

                    Log.d("SettingsFragment", "用戶選擇了刮數: ${scratchTypeStr}刮, 庫存: ${selectedItem.stock}")

                    if (currentBillingMode != "RENTAL" && selectedItem.stock <= 0) {
                        showToast("${scratchTypeStr}刮 無庫存，無法選擇")
                        return
                    }

                    showRightPanel()

                    // 🌟 配合新參數，下拉選單未設置狀態必定為可編輯 (isReadonly = false)
                    applySplitModeVisibility(scratchTypeStr.contains("x"), isReadonly = false)

                    val selectedCard = viewModel.cards.value[shelfManager.selectedShelfOrder]
                    if (selectedCard == null) {
                        isShowingUnsetState = false
                        updatePreviewForScratchType(scratchTypeStr)
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

    private fun updateRemainingScratchesInfo(cards: Map<Int, ScratchCard>) {
        val activity = activity as? MainActivity ?: return
        val remainingView = activity.findViewById<TextView>(R.id.remaining_scratches_text_view) ?: return

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

    private fun updateFocusMode(enabled: Boolean, target: FocusTarget?) {
        isFocusMode = enabled
        currentFocusTarget = target
        applyFocusMode()
    }

    private fun applyFocusMode() {
        if (!isAdded || _binding == null) return

        val allowedButton = when (currentFocusTarget) {
            FocusTarget.SPECIAL -> binding.buttonPickSpecialPrize
            FocusTarget.GRAND -> binding.buttonPickGrandPrize
            else -> null
        }

        val allowedViews = mutableSetOf<View>(
            binding.scratchBoardArea
        ).apply {
            allowedButton?.let { btn ->
                add(btn)
                (btn.parent as? View)?.let { add(it) }
            }
        }

        restoreAllInteractive()

        if (!isFocusMode) {
            val order = shelfManager.selectedShelfOrder
            val card = viewModel.cards.value[order]

            if (card == null) {
                setButtonsEnabled(
                    save = true,
                    toggleInUse = false,
                    autoScratch = false,
                    returnBtn = false,
                    delete = false
                )
            } else {
                // 🌟 修正：同步這裡的判斷邏輯，僅刮過才唯讀
                val isReadonly = hasBeenScratched(card)
                if (isReadonly) {
                    if (card.inUsed) {
                        setButtonsEnabled(save = false, toggleInUse = true, autoScratch = true, returnBtn = false, delete = false)
                    } else {
                        setButtonsEnabled(save = false, toggleInUse = true, autoScratch = true, returnBtn = false, delete = true)
                    }
                } else {
                    if (card.inUsed) {
                        setButtonsEnabled(save = true, toggleInUse = true, autoScratch = true, returnBtn = false, delete = false)
                    } else {
                        setButtonsEnabled(save = true, toggleInUse = true, autoScratch = true, returnBtn = true, delete = true)
                    }
                }
            }

            currentFocusedSubBoardId?.let { boardName ->
                updateSubBoardsAlpha(boardName)
                binding.onShelfListContainer.alpha = 0.35f
                setEnabledRecursively(binding.onShelfListContainer, false)
            }
            return
        }

        setEnabledRecursively(binding.onShelfListContainer, false)
        binding.onShelfListContainer.alpha = 0.35f

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

        binding.scratchBoardArea.alpha = 1f

        currentFocusedSubBoardId?.let { boardName ->
            updateSubBoardsAlpha(boardName)
        }

        fun disableView(v: View) {
            v.isEnabled = false
            v.alpha = 0.35f
        }

        when (currentFocusTarget) {
            FocusTarget.SPECIAL -> {
                disableView(binding.buttonPickGrandPrize)
                disableView(binding.buttonGrandPrizeKeyboard)
                disableView(binding.editTextGrandPrize)
            }
            FocusTarget.GRAND -> {
                disableView(binding.buttonPickSpecialPrize)
                disableView(binding.buttonSpecialPrizeKeyboard)
                disableView(binding.editTextSpecialPrize)
            }
            else -> {}
        }

        listOf(
            binding.buttonSaveSettings,
            binding.buttonToggleInuse,
            binding.buttonAutoScratch,
            binding.buttonReturnSelected,
            binding.buttonDeleteSelected,
            binding.buttonBackToMaster
        ).forEach { v ->
            if (v !in allowedViews) {
                v.isEnabled = false
                v.alpha = 0.35f
            }
        }
    }

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

    private fun setEnabledRecursively(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setEnabledRecursively(view.getChildAt(i), enabled)
            }
        }
    }

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

    private fun enterSpecialPrizePickMode() {
        if (isPickingGrandPrize) {
            exitGrandPrizePickMode()
        }

        isPickingSpecialPrize = true
        binding.buttonPickSpecialPrize.isPressed = true
        binding.buttonPickSpecialPrize.text = "特獎（選取中…）"
        showToast("請在左側刮板預覽區點選一個數字")

        currentPreviewFragment?.setSinglePickEnabled(true)
        currentPreviewFragment?.setMultiPickEnabled(false)

        updateFocusMode(true, FocusTarget.SPECIAL)
    }

    private fun exitSpecialPrizePickMode() {
        isPickingSpecialPrize = false
        binding.buttonPickSpecialPrize.isPressed = false
        binding.buttonPickSpecialPrize.text = "特獎"
        currentPreviewFragment?.setSinglePickEnabled(false)

        updateFocusMode(false, null)
    }

    private fun enterGrandPrizePickMode() {
        if (isPickingSpecialPrize) {
            exitSpecialPrizePickMode()
        }

        isPickingGrandPrize = true
        binding.buttonPickGrandPrize.isPressed = true
        binding.buttonPickGrandPrize.text = "大獎\r\n（多選中…）"
        showToast("請在左側預覽區多選數字（再點可取消）")

        currentPreviewFragment?.setMultiPickEnabled(true)
        currentPreviewFragment?.setSinglePickEnabled(false)

        updateFocusMode(true, FocusTarget.GRAND)
    }

    private fun exitGrandPrizePickMode() {
        isPickingGrandPrize = false
        binding.buttonPickGrandPrize.isPressed = false
        binding.buttonPickGrandPrize.text = "大獎"
        currentPreviewFragment?.setMultiPickEnabled(false)

        updateFocusMode(false, null)
    }

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
                breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                movementMethod = ScrollingMovementMethod.getInstance()
            }
        }
    }

    private fun validateBeforeSave(data: SaveData): Boolean {
        val totalCells = currentPreviewFragment?.getGeneratedNumberConfigurations()?.size ?: 0

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

        if (gpList.contains(sp)) {
            showToast("無法儲存：特獎不可同時為大獎，請調整選取")
            currentPreviewFragment?.setSelectedNumber(sp)
            currentPreviewFragment?.setGrandSelectedNumbers(gpList)
            return false
        }

        return true
    }

    // ===========================================
    // 狀態管理相關方法
    // ===========================================

    private fun showUnsetShelfState() {
        val order = shelfManager.selectedShelfOrder
        val draft = viewModel.getDraft(order)
        updateParametersTitle(order)

        isShowingUnsetState = true

        showEditableFields()
        showScratchTypeSpinner()

        if (draft != null && draft.scratchType != null) {
            showRightPanel()

            val rawPitch = draft.pitchType ?: "scratch"
            val realPitchType = rawPitch.substringBefore("|")
            val splitModeStr = rawPitch.substringAfter("|", "")

            val displayScratchTypeStr = if (splitModeStr.isNotEmpty()) {
                splitModeStr
            } else {
                draft.scratchType.toString()
            }

            setScratchTypeSpinnerSelection(displayScratchTypeStr)
            displayScratchBoardPreview(displayScratchTypeStr, draft.numberConfigurations)

            binding.editTextSpecialPrize.setText(draft.specialPrize.orEmpty())
            binding.editTextGrandPrize.setText(draft.grandPrize.orEmpty())

            val isShopping = (realPitchType == "shopping")
            if (isShopping) {
                binding.radioPitchShopping.isChecked = true
                applyPitchTypeUi(isShopping = true, syncValues = false)

                val spend = draft.claws ?: 0
                binding.editClawsCount.setText(spend.toString())
            } else {
                binding.radioPitchScratch.isChecked = true
                applyPitchTypeUi(isShopping = false, syncValues = false)

                val catchCount = (draft.claws ?: 1).coerceIn(1, 5)
                setSpinnerSelection(binding.spinnerClawsCount, catchCount)
            }

            val give = (draft.giveaway ?: 1).coerceIn(1, 5)
            setSpinnerSelection(binding.spinnerGiveawayCount, give)

            currentPreviewFragment?.setSelectedNumber(draft.specialPrize?.toIntOrNull())
            val gp = draft.grandPrize
                ?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
            currentPreviewFragment?.setGrandSelectedNumbers(gp)

            val isSplitDraft = splitModeStr.isNotEmpty()
            applySplitModeVisibility(isSplitMode = isSplitDraft, isReadonly = false)
            setPrizeControlsEnabled(!isSplitDraft)

        } else {
            // 🌟 核心防呆：如果這格沒有草稿（或因登出被清空），確保分割版面的快取也一併清空
            clearSplitDraftCache(order)

            hideRightPanel()
            showPreviewUnset()
            clearTextFieldsOnly()
            clearSpinnerSelection()
            setPrizeControlsEnabled(false)

            binding.radioPitchScratch.isChecked = true
            applyPitchTypeUi(isShopping = false, syncValues = false)
        }

        setButtonsEnabled(save = true, toggleInUse = false, autoScratch = false, returnBtn = false, delete = false)
        uiManager.updateInUseButtonUI(null)
        uiManager.updateActionButtonsUI(null)
        updateRefreshButtonVisibility()
    }

    private fun updateRefreshButtonVisibility() {
        val order = shelfManager.selectedShelfOrder
        val hasCard = viewModel.cards.value[order] != null
        binding.buttonRefreshScratch.visibility = if (hasCard) View.GONE else View.VISIBLE
    }

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
        showRightPanel()
        isShowingUnsetState = false
        restorePreviewContainer()
        updateParametersTitle(shelfManager.selectedShelfOrder)

        // 🌟 修正：只有「已刮過」才強制唯讀，單純「使用中」仍然可以編輯
        val shouldShowReadonly = hasBeenScratched(selectedCard)

        if (shouldShowReadonly) {
            displayScratchCardDetailsReadonly(selectedCard)
            if (selectedCard.inUsed) {
                setButtonsEnabled(save = false, toggleInUse = true, autoScratch = true, returnBtn = false, delete = false)
            } else {
                setButtonsEnabled(save = false, toggleInUse = true, autoScratch = true, returnBtn = false, delete = true)
            }
        } else {
            displayScratchCardDetails(selectedCard)
            if (selectedCard.inUsed) {
                // 🌟 使用中但沒刮過：可儲存、不可刪除返回
                setButtonsEnabled(save = true, toggleInUse = true, autoScratch = true, returnBtn = false, delete = false)
            } else {
                setButtonsEnabled(save = true, toggleInUse = true, autoScratch = true, returnBtn = true, delete = true)
            }
        }

        val scratchTypeStr = if (!selectedCard.splitMode.isNullOrEmpty()) selectedCard.splitMode!! else selectedCard.scratchesType.toString()

        showScratchTypeLabel(scratchTypeStr)
        uiManager.updateInUseButtonUI(selectedCard)
        uiManager.updateActionButtonsUI(selectedCard)
        updateRefreshButtonVisibility()

        // 🌟 修正：將 shouldShowReadonly 狀態傳遞下去，避免 UI 衝突
        applySplitModeVisibility(scratchTypeStr.contains("x"), shouldShowReadonly)
    }

    private fun showRightPanel() {
        binding.rightPanelContainer.visibility = View.VISIBLE
    }

    private fun hideRightPanel() {
        binding.rightPanelContainer.visibility = View.GONE
    }


    // ===========================================
    // 點擊事件處理方法
    // ===========================================

    private fun handleSaveClick() {
        // 分流點：如果目前在「子板聚焦模式」
        if (currentFocusedSubBoardId != null) {
            val boardName = currentFocusedSubBoardId!!

            if (isPickingSpecialPrize || isPickingGrandPrize) {
                showToast("請先點擊按鈕取消選取模式，再儲存")
                return
            }

            val order = shelfManager.selectedShelfOrder
            val selectedCard = viewModel.cards.value[order]

            // 取得目前的刮開狀態
            val configs = splitBoardConfigurations[boardName]
            val isBoardScratched = configs?.any { it.scratched } == true

            // 判斷此刮板是否已經存入資料庫
            if (selectedCard != null && selectedCard.serialNumber != null) {
                if (isBoardScratched) {
                    showToast("此子板已有刮開紀錄，參數為唯讀狀態無法儲存")
                    return
                }

                // 抓取當下 UI 數值
                val specialStr = binding.editTextSpecialPrize.text.toString().trim()
                val grandStr = binding.editTextGrandPrize.text.toString().trim()
                val isShopping = binding.radioPitchShopping.isChecked
                val pitchType = if (isShopping) "shopping" else "scratch"
                val claws = if (isShopping) {
                    binding.editClawsCount.text?.toString() ?: "0"
                } else {
                    binding.spinnerClawsCount.selectedItem?.toString() ?: "1"
                }
                val giveaway = binding.spinnerGiveawayCount.selectedItem?.toString()?.toIntOrNull() ?: 1

                val dbRef = com.google.firebase.database.FirebaseDatabase.getInstance(AppConfig.DB_URL).reference
                val userKey = (requireActivity() as UserSessionProvider).getCurrentUserFirebaseKey()

                if (userKey != null) {
                    val updates = mapOf(
                        "specialPrize" to specialStr,
                        "grandPrize" to grandStr,
                        "pitchType" to pitchType,
                        "clawsCount" to (claws.toIntOrNull() ?: 1),
                        "giveawayCount" to giveaway
                    )

                    // 🌟 核心防閃爍：開啟寫入鎖定，防止 Firebase 同步時引發全域畫面重繪
                    isSavingInProgress = true

                    // 直接更新該子板的資料
                    dbRef.child("users").child(userKey).child("scratchCards")
                        .child(selectedCard.serialNumber!!).child("boards").child(boardName)
                        .updateChildren(updates).addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                showToast("${boardName}板參數已直接更新至資料庫！")
                            } else {
                                showToast("寫入資料庫失敗：${task.exception?.message}")
                            }
                            // 延遲解除鎖定，讓過渡動畫平順完成
                            viewLifecycleOwner.lifecycleScope.launch {
                                kotlinx.coroutines.delay(500)
                                isSavingInProgress = false
                            }
                        }
                }

                // 執行退回母板並更新暫存 UI
                exitSubBoardFocusMode()
                return
            } else {
                exitSubBoardFocusMode()
                showToast("${boardName}板參數已暫存！全部子板設定完畢後，請點擊母板的儲存按鈕寫入資料庫")
                return
            }
        }

        // --- 以下為原本的母板儲存邏輯 ---
        val selectedOrder = shelfManager.selectedShelfOrder
        val selectedCard = viewModel.cards.value[selectedOrder]

        if (selectedCard != null && hasBeenScratched(selectedCard)) {
            showToast("此刮板已刮開，無法儲存參數")
            return
        }

        val scratchTypeStr = if (selectedCard != null) {
            if (!selectedCard.splitMode.isNullOrEmpty()) selectedCard.splitMode!! else selectedCard.scratchesType.toString()
        } else {
            val selectedItem = binding.spinnerScratchesCount.selectedItem as? ScratchTypeItem
            selectedItem?.getScratchTypeString() ?: return
        }

        if (scratchTypeStr.contains("x")) {
            handleSaveSplitMode(scratchTypeStr, selectedCard)
            return
        }

        val saveData = extractSaveData(scratchTypeStr)

        currentPreviewFragment?.setSelectedNumber(
            binding.editTextSpecialPrize.text?.toString()?.toIntOrNull()
        )

        val gp = binding.editTextGrandPrize.text?.toString()
            ?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        currentPreviewFragment?.setGrandSelectedNumbers(gp)

        handleSaveSettings(saveData)
    }

    private fun handleSaveSplitMode(splitModeStr: String, existingCard: ScratchCard?) {
        val order = shelfManager.selectedShelfOrder

        val boardsData = mutableMapOf<String, Any>()
        val subBoardCount = splitModeStr.substringBefore("x").toIntOrNull() ?: 20
        val boards = splitModeStr.substringAfter("x").toIntOrNull() ?: 4
        val totalScratches = subBoardCount * boards

        val boardNames = listOf("A", "B", "C", "D").take(boards)

        // 1. 組裝各子板資料
        for ((index, boardName) in boardNames.withIndex()) {
            val sp = splitBoardSpecialPrizes[boardName]
            if (sp.isNullOrBlank()) {
                showToast("儲存失敗：請先點擊 ${boardName}板 確認預設參數")
                return
            }

            val gp = splitBoardGrandPrizes[boardName] ?: ""
            val gpList = gp.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (gpList.contains(sp.toIntOrNull())) {
                showToast("無法儲存：${boardName}板 的特獎不可與大獎重複")
                return
            }
            if (gpList.size > 2) {
                showToast("無法儲存：${boardName}板 的大獎數量限制為 2 個")
                return
            }

            val configs = splitBoardConfigurations[boardName]
            if (configs == null) {
                showToast("發生錯誤：找不到 ${boardName}板 的數字配置，請點擊重新整理按鈕")
                return
            }

            val boardData = mapOf(
                "id" to boardName,
                "order" to (index + 1),
                "specialPrize" to sp,
                "grandPrize" to gp,
                "pitchType" to (splitBoardPitchTypes[boardName] ?: "scratch"),
                "clawsCount" to (splitBoardClawsCounts[boardName]?.toIntOrNull() ?: 1),
                "giveawayCount" to (splitBoardGiveawayCounts[boardName] ?: 1),
                "numberConfigurations" to configs.map {
                    mapOf("id" to it.id, "number" to it.number, "scratched" to it.scratched)
                }
            )
            boardsData[boardName] = boardData
        }

        val isNewCard = existingCard == null
        isSavingInProgress = true

        // 2. 組裝母板外層屬性，完美符合您上傳的 JSON 結構
        val cardData = mapOf(
            "order" to order,
            "scratchesType" to totalScratches, // 例如 20x4 會存 80
            "splitMode" to splitModeStr,
            "inUsed" to (existingCard?.inUsed ?: false),
            "boards" to boardsData
        )

        fun finishAndRefreshUI() {
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(500)
                isSavingInProgress = false
                val updatedCard = viewModel.cards.value[order]
                if (updatedCard != null) {
                    viewModel.clearDraft(order)
                    clearSplitDraftCache(order) // 🌟 儲存成功，清除快取
                    showSetShelfState(updatedCard)
                } else {
                    showUnsetShelfState()
                }
            }
        }

        // 3. 儲存與扣庫存
        if (isNewCard) {
            if (currentBillingMode == "RENTAL") {
                viewModel.saveSplitCardDirectly(existingCard?.serialNumber, cardData) { finishAndRefreshUI() }
            } else {
                deductSplitStock(splitModeStr) { success ->
                    if (success) {
                        viewModel.saveSplitCardDirectly(existingCard?.serialNumber, cardData) { finishAndRefreshUI() }
                    } else {
                        isSavingInProgress = false
                        showToast("庫存不足或扣減失敗")
                    }
                }
            }
        } else {
            viewModel.saveSplitCardDirectly(existingCard?.serialNumber, cardData) { finishAndRefreshUI() }
        }
    }

    private fun deductSplitStock(scratchTypeStr: String, onComplete: (Boolean) -> Unit) {
        if (currentBillingMode == "RENTAL") {
            onComplete(true)
            return
        }

        val userRef = getUserFirebaseReference()
        if (userRef == null) {
            onComplete(false)
            return
        }

        val stockFieldName = "scratchType_$scratchTypeStr" // 例如 scratchType_20x4

        userRef.child(stockFieldName).get()
            .addOnSuccessListener { snapshot ->
                val currentStock = snapshot.getValue(Int::class.java) ?: 0
                if (currentStock > 0) {
                    userRef.child(stockFieldName).setValue(currentStock - 1)
                        .addOnSuccessListener {
                            Log.d("SettingsFragment", "${scratchTypeStr}版型 庫存已扣減1，剩餘: ${currentStock - 1}")
                            onComplete(true)
                        }
                        .addOnFailureListener { onComplete(false) }
                } else {
                    onComplete(false)
                }
            }
            .addOnFailureListener { onComplete(false) }
    }

    // 🌟 修改點 8：接收字串並轉為 Int 存入 data 類 (因為 SaveData 目前維持 Int)
    private fun extractSaveData(scratchTypeStr: String): SaveData {
        val isShopping = binding.radioPitchShopping.isChecked
        val pitchType = if (isShopping) "shopping" else "scratch"

        val clawsValue: Int? = if (isShopping) {
            val t = binding.editClawsCount.text?.toString()?.trim().orEmpty()
            if (t.isEmpty()) 0 else t.toIntOrNull()
        } else {
            binding.spinnerClawsCount.selectedItem?.toString()?.toIntOrNull()
        }

        return SaveData(
            order = shelfManager.selectedShelfOrder,
            scratchType = scratchTypeStr.toIntOrNull() ?: 0, // 分割版面會先轉成 0，這是漸進式過渡
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
        val pitchType: String,
        val claws: Int?,
        val giveaway: Int?,
        val numberConfigurations: List<NumberConfiguration>?,
        val currentCards: Map<Int, ScratchCard>
    )

    private fun handleSaveSettings(data: SaveData) {
        val selectedScratchTypeStr = getCurrentScratchType() ?: ""
        val isSplitMode = selectedScratchTypeStr.contains("x")

        // 🌟 更新防呆：確保未定義的單一版型預設上限為 15
        val limit = if (isSplitMode) 2 else (GRAND_LIMITS[data.scratchType] ?: 15)

        val gpList = data.grandPrize?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        if (gpList.size > limit) {
            val limitMsg = if (isSplitMode) "分割版面的大獎數量限制為 2 個" else "${data.scratchType}刮的大獎數量限制為 ${limit} 個"
            showToast(limitMsg)
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

        Log.d("SettingsFragment", "準備儲存: isNewCard=$isNewCard, scratchType=${data.scratchType}, billingMode=$currentBillingMode")

        fun finishAndRefreshUI() {
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(500)
                isSavingInProgress = false

                val updatedCard = viewModel.cards.value[data.order]
                if (updatedCard != null) {
                    viewModel.clearDraft(data.order)

                    Log.d("SettingsFragment", "儲存完成，手動更新 UI")
                    showSetShelfState(updatedCard)
                } else {
                    Log.w("SettingsFragment", "儲存完成但找不到卡片")
                    showUnsetShelfState()
                }
            }
        }

        if (isNewCard) {
            Log.d("SettingsFragment", "新建版位流程")

            if (currentBillingMode == "RENTAL") {
                Log.d("SettingsFragment", "RENTAL 模式：跳過扣庫存，直接創建卡片")
                upsertCardWithData(data, existingCard)
                finishAndRefreshUI()
                return
            }

            Log.d("SettingsFragment", "POINT 模式：準備扣減庫存")
            deductScratchTypeStock(data.scratchType) { success ->
                if (success) {
                    Log.d("SettingsFragment", "庫存扣減成功，開始創建卡片")
                    upsertCardWithData(data, existingCard)
                    finishAndRefreshUI()
                } else {
                    isSavingInProgress = false
                    showToast("庫存不足或扣減失敗")
                }
            }
        } else {
            Log.d("SettingsFragment", "更新現有版位，直接儲存")
            upsertCardWithData(data, existingCard)
            finishAndRefreshUI()
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
            pitchType = data.pitchType
        )
    }

    private fun handleToggleInUseClick() {
        actionHandler.handleToggleInUse(shelfManager.selectedShelfOrder, viewModel.cards.value)
    }

    private fun handleReturnClick() {
        if (currentBillingMode == "RENTAL") {
            return
        }
        actionHandler.handleReturn(shelfManager.selectedShelfOrder, viewModel.cards.value)
    }

    private fun handleDeleteClick() {
        val order = shelfManager.selectedShelfOrder
        viewModel.clearDraft(order)
        clearSplitDraftCache(order) // 🌟 刪除時，同步清除快取
        actionHandler.handleDelete(order, viewModel.cards.value)
    }

    private fun handleRefreshScratchClick() {
        val selectedItem = binding.spinnerScratchesCount.selectedItem as? ScratchTypeItem
        if (selectedItem == null) {
            showToast("請先選擇刮數")
            return
        }

        val scratchTypeStr = selectedItem.getScratchTypeString()
        if (scratchTypeStr == null) {
            showToast("請先選擇刮數")
            return
        }
        val stock = selectedItem.stock

        if (currentBillingMode != "RENTAL" && stock <= 0) {
            showToast("此刮數無庫存，無法重新整理")
            return
        }

        showToast("重新生成 ${scratchTypeStr}版型 配置中…")

        if (scratchTypeStr.contains("x")) {
            splitBoardSpecialPrizes.clear()
            splitBoardGrandPrizes.clear()
            splitBoardPitchTypes.clear()
            splitBoardClawsCounts.clear()
            splitBoardGiveawayCounts.clear()
            splitBoardConfigurations.clear()

            // 🌟 同步清空快取，確保重新生成全新的亂數版面
            val order = shelfManager.selectedShelfOrder
            clearSplitDraftCache(order)
        }

        displayScratchBoardPreview(scratchTypeStr, null)

        binding.editTextSpecialPrize.text?.clear()
        binding.editTextGrandPrize.text?.clear()

        setPrizeControlsEnabled(true)
        saveDraftIfNeeded(shelfManager.selectedShelfOrder)
    }

    private fun setPrizeControlsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1.0f else 0.5f

        binding.buttonPickSpecialPrize.isEnabled = enabled
        binding.buttonPickGrandPrize.isEnabled = enabled
        binding.buttonPickSpecialPrize.alpha = alpha
        binding.buttonPickGrandPrize.alpha = alpha

        binding.buttonSpecialPrizeKeyboard.isEnabled = enabled
        binding.buttonGrandPrizeKeyboard.isEnabled = enabled
        binding.buttonSpecialPrizeKeyboard.alpha = alpha
        binding.buttonGrandPrizeKeyboard.alpha = alpha

        binding.editTextSpecialPrize.isEnabled = enabled
        binding.editTextGrandPrize.isEnabled = enabled
    }


    // ===========================================
    // UI 顯示相關方法
    // ===========================================

    private fun displayScratchCardDetails(card: ScratchCard) {
        safeExecute("顯示可編輯卡片詳情") {
            showEditableFields()

            binding.editTextSpecialPrize.setText(card.specialPrize ?: "")
            binding.editTextGrandPrize.setText(card.grandPrize ?: "")

            applySavedPitchRule(card)

            // 🌟 核心修改：優先讀取分割版面字串，並把 card 整包傳進去給預覽函式解壓縮
            val scratchTypeStr = if (!card.splitMode.isNullOrEmpty()) card.splitMode!! else card.scratchesType.toString()
            displayScratchBoardPreview(scratchTypeStr, card.numberConfigurations, card)

            currentPreviewFragment?.setSelectedNumber(card.specialPrize?.toIntOrNull())

            val grandList = card.grandPrize?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() }
            currentPreviewFragment?.setGrandSelectedNumbers(grandList)
        }
    }

    private fun displayScratchCardDetailsReadonly(card: ScratchCard) {
        safeExecute("顯示只讀卡片詳情") {
            showReadonlyFields(card)

            // 🌟 核心修改：優先讀取分割版面字串，並把 card 整包傳進去給預覽函式解壓縮
            val scratchTypeStr = if (!card.splitMode.isNullOrEmpty()) card.splitMode!! else card.scratchesType.toString()
            displayScratchBoardPreview(scratchTypeStr, card.numberConfigurations, card)

            currentPreviewFragment?.setSelectedNumber(card.specialPrize?.toIntOrNull())
            val grandList = card.grandPrize?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
            currentPreviewFragment?.setGrandSelectedNumbers(grandList)
        }
    }

    private fun showEditableFields() {
        removeReadonlyLabels()
        hidePitchRuleReadonly()
        showEditableContainers()

        val isShopping = binding.radioPitchShopping.isChecked
        applyPitchTypeUi(isShopping = isShopping, syncValues = false)
    }

    private fun showEditableContainers() {
        val specialPrizeContainer = findViewContaining(binding.buttonPickSpecialPrize)
        val grandPrizeContainer = findViewContaining(binding.buttonPickGrandPrize)

        specialPrizeContainer?.visibility = View.VISIBLE
        grandPrizeContainer?.visibility = View.VISIBLE

        setPrizeControlsEnabled(true)
        binding.editTextSpecialPrize.visibility = View.VISIBLE
        binding.editTextGrandPrize.visibility = View.VISIBLE
    }

    private fun showReadonlyFields(card: ScratchCard) {
        hideEditableContainers()
        createReadonlyLabels(card)
        showPitchRuleReadonly(card)
    }

    private fun hideEditableContainers() {
        val specialPrizeContainer = findViewContaining(binding.buttonPickSpecialPrize)
        val grandPrizeContainer = findViewContaining(binding.buttonPickGrandPrize)

        specialPrizeContainer?.visibility = View.GONE
        grandPrizeContainer?.visibility = View.GONE
    }

    private fun createReadonlyLabels(card: ScratchCard) {
        // 🌟 核心修改：改為呼叫新的通用方法
        setupReadonlyLabels(card.specialPrize, card.grandPrize)
    }

    // 🌟 新增：抽離出來的通用建立唯讀標籤方法，讓子板也能重複使用
    private fun setupReadonlyLabels(specialPrizeStr: String?, grandPrizeStr: String?) {
        removeReadonlyLabels()

        val context = requireContext()

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

        val specialPrizeTitle = TextView(context).apply {
            text = "特獎："
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(0, 8, 8, 8)
            gravity = Gravity.CENTER_VERTICAL
        }

        specialPrizeLabel = TextView(context).apply {
            text = if (specialPrizeStr.isNullOrEmpty()) "未設定" else specialPrizeStr
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

        val grandPrizeTitle = TextView(context).apply {
            text = "大獎："
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(0, 8, 8, 8)
            gravity = Gravity.CENTER_VERTICAL
        }

        grandPrizeLabel = TextView(context).apply {
            text = if (grandPrizeStr.isNullOrEmpty()) "未設定" else grandPrizeStr
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

        insertReadonlyContainers(specialPrizeContainer, grandPrizeContainer)
    }

    private fun insertReadonlyContainers(specialContainer: LinearLayout, grandContainer: LinearLayout) {
        val settingsContainer = binding.rightPanelContainer

        val originalSpecialContainer = findViewContaining(binding.buttonPickSpecialPrize)
        val originalGrandContainer = findViewContaining(binding.buttonPickGrandPrize)

        originalSpecialContainer?.let { container ->
            val index = settingsContainer.indexOfChild(container)
            if (index != -1) {
                settingsContainer.addView(specialContainer, index)
            }
        }

        originalGrandContainer?.let { container ->
            val index = settingsContainer.indexOfChild(container)
            val adjustedIndex = if (specialContainer.parent != null) index + 1 else index
            if (adjustedIndex <= settingsContainer.childCount) {
                settingsContainer.addView(grandContainer, adjustedIndex)
            }
        }
    }

    private fun findViewContaining(targetView: View): ViewGroup? {
        val root = binding.rightPanelContainer

        var current: View = targetView
        var parent = targetView.parent as? ViewGroup

        while (parent != null && parent != root) {
            current = parent
            parent = parent.parent as? ViewGroup
        }

        return if (parent == root) current as? ViewGroup else null
    }

    private fun removeReadonlyLabels() {
        specialPrizeLabel?.let { label ->
            val container = label.parent as? ViewGroup
            val parentContainer = container?.parent as? ViewGroup
            parentContainer?.removeView(container)
        }

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

    // 🌟 修改點 12：接收字串並顯示
    private fun showScratchTypeLabel(scratchTypeStr: String) {
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

        scratchTypeLabel?.text = "${scratchTypeStr}刮"
        scratchTypeLabel?.visibility = View.VISIBLE
    }

    // 🌟 修改點 13：傳入字串
    private fun updatePreviewForScratchType(scratchTypeStr: String) {
        val selectedCard = viewModel.cards.value[shelfManager.selectedShelfOrder]

        if (selectedCard != null) return

        Log.d("SettingsFragment", "未設置板位：立即更新預覽為 ${scratchTypeStr}刮")

        displayScratchBoardPreview(scratchTypeStr, null)
        setPrizeControlsEnabled(true)

        saveDraftIfNeeded(shelfManager.selectedShelfOrder)
    }

    private fun updateParametersTitle(order: Int?) {
        val title = if (order != null && order in 1..6) {
            "${order}號板參數設定"
        } else {
            "板位參數設定"
        }
        binding.textParametersTitle.text = title
    }

    private fun displayScratchBoardPreview(
        scratchTypeStr: String,
        existingConfigs: List<NumberConfiguration>?,
        savedCard: ScratchCard? = null
    ) {
        safeExecute("顯示刮板預覽") {
            currentPreviewFragment?.let { fragment ->
                childFragmentManager.beginTransaction()
                    .remove(fragment)
                    .commitNowAllowingStateLoss()
            }

            if (scratchTypeStr.contains("x")) {
                val subBoardScratchCount = scratchTypeStr.substringBefore("x").toIntOrNull() ?: 20
                val boardsCount = scratchTypeStr.substringAfter("x").toIntOrNull() ?: 4
                val boardNames = listOf("A", "B", "C", "D").take(boardsCount)
                val splitNumbers = mutableMapOf<String, List<Int>>()
                val order = shelfManager.selectedShelfOrder

                // 🌟 情況 A：正在載入已儲存的分割版面
                if (savedCard != null && savedCard.splitMode == scratchTypeStr) {

                    val isSameCardRendered = binding.scratchBoardArea.tag == savedCard.serialNumber
                    val hasViews = binding.scratchBoardArea.childCount > 0

                    splitBoardSpecialPrizes.clear()
                    splitBoardGrandPrizes.clear()
                    splitBoardPitchTypes.clear()
                    splitBoardClawsCounts.clear()
                    splitBoardGiveawayCounts.clear()
                    splitBoardConfigurations.clear()

                    val boardsMap = try {
                        savedCard.javaClass.getMethod("getBoards").invoke(savedCard) as? Map<*, *>
                    } catch (e: Exception) {
                        try {
                            val field = savedCard.javaClass.getDeclaredField("boards")
                            field.isAccessible = true
                            field.get(savedCard) as? Map<*, *>
                        } catch (ex: Exception) { null }
                    }

                    boardNames.forEach { boardName ->
                        val rawBoard = boardsMap?.get(boardName)
                        var sp = ""
                        var gp = ""
                        var pt = "scratch"
                        var cc = "1"
                        var gc = 1
                        var configs: List<NumberConfiguration>? = null

                        if (rawBoard is com.champion.king.model.Board) {
                            sp = rawBoard.specialPrize ?: ""
                            gp = rawBoard.grandPrize ?: ""
                            pt = rawBoard.pitchType
                            cc = rawBoard.clawsCount?.toString() ?: "1"
                            gc = rawBoard.giveawayCount ?: 1
                            configs = rawBoard.numberConfigurations
                        } else if (rawBoard is Map<*, *>) {
                            sp = rawBoard["specialPrize"]?.toString() ?: ""
                            gp = rawBoard["grandPrize"]?.toString() ?: ""
                            pt = rawBoard["pitchType"]?.toString() ?: "scratch"
                            cc = rawBoard["clawsCount"]?.toString() ?: "1"
                            gc = (rawBoard["giveawayCount"] as? Number)?.toInt() ?: 1

                            val configsList = rawBoard["numberConfigurations"] as? List<*>
                            if (configsList != null) {
                                configs = configsList.mapNotNull { item ->
                                    if (item is Map<*, *>) {
                                        NumberConfiguration(
                                            id = (item["id"] as? Number)?.toInt() ?: 0,
                                            number = (item["number"] as? Number)?.toInt() ?: 0,
                                            scratched = item["scratched"] as? Boolean ?: false
                                        )
                                    } else if (item is NumberConfiguration) {
                                        item
                                    } else null
                                }
                            }
                        }

                        splitBoardSpecialPrizes[boardName] = sp
                        splitBoardGrandPrizes[boardName] = gp
                        splitBoardPitchTypes[boardName] = pt
                        splitBoardClawsCounts[boardName] = cc
                        splitBoardGiveawayCounts[boardName] = gc

                        if (configs != null && configs.isNotEmpty()) {
                            splitBoardConfigurations[boardName] = configs
                            splitNumbers[boardName] = configs.map { it.number }
                        } else {
                            val shuffled = (1..subBoardScratchCount).toList().shuffled()
                            splitNumbers[boardName] = shuffled
                            splitBoardConfigurations[boardName] = shuffled.mapIndexed { idx, num ->
                                NumberConfiguration(id = idx + 1, number = num, scratched = false)
                            }
                            if (sp.isEmpty()) splitBoardSpecialPrizes[boardName] = shuffled.random().toString()
                        }
                    }

                    if (isSameCardRendered && hasViews) {
                        boardNames.forEach { boardName ->
                            updateSubBoardCellsUI(boardName)
                            renderSubBoardHeaderUI(boardName)
                        }
                        return@safeExecute
                    }

                    binding.scratchBoardArea.tag = savedCard.serialNumber
                    buildAllSplitPreviews(binding.scratchBoardArea, splitNumbers)
                    return@safeExecute
                }
                // 🌟 情況 B：發現草稿暫存！(使用者從背包或商城切換回來)
                else if (splitBoardConfigurationsCache.containsKey(order)) {
                    val isSameDraftRendered = binding.scratchBoardArea.tag == "draft_$order"
                    val hasViews = binding.scratchBoardArea.childCount > 0

                    splitBoardSpecialPrizes.clear()
                    splitBoardSpecialPrizes.putAll(splitBoardSpecialPrizesCache[order] ?: emptyMap())

                    splitBoardGrandPrizes.clear()
                    splitBoardGrandPrizes.putAll(splitBoardGrandPrizesCache[order] ?: emptyMap())

                    splitBoardPitchTypes.clear()
                    splitBoardPitchTypes.putAll(splitBoardPitchTypesCache[order] ?: emptyMap())

                    splitBoardClawsCounts.clear()
                    splitBoardClawsCounts.putAll(splitBoardClawsCountsCache[order] ?: emptyMap())

                    splitBoardGiveawayCounts.clear()
                    splitBoardGiveawayCounts.putAll(splitBoardGiveawayCountsCache[order] ?: emptyMap())

                    splitBoardConfigurations.clear()
                    splitBoardConfigurations.putAll(splitBoardConfigurationsCache[order] ?: emptyMap())

                    boardNames.forEach { boardName ->
                        splitNumbers[boardName] = splitBoardConfigurations[boardName]?.map { it.number } ?: emptyList()
                    }

                    // 效能優化：如果視圖已存在，只更新 UI 即可
                    if (isSameDraftRendered && hasViews) {
                        boardNames.forEach { boardName ->
                            updateSubBoardCellsUI(boardName)
                            renderSubBoardHeaderUI(boardName)
                        }
                        return@safeExecute
                    }

                    binding.scratchBoardArea.tag = "draft_$order"
                    buildAllSplitPreviews(binding.scratchBoardArea, splitNumbers)
                    return@safeExecute
                }
                // 🌟 情況 C：全新生成 (選擇下拉選單)
                else if (existingConfigs == null) {
                    binding.scratchBoardArea.tag = null
                    splitBoardSpecialPrizes.clear()
                    splitBoardGrandPrizes.clear()
                    splitBoardPitchTypes.clear()
                    splitBoardClawsCounts.clear()
                    splitBoardGiveawayCounts.clear()
                    splitBoardConfigurations.clear()

                    boardNames.forEach { boardName ->
                        val shuffled = (1..subBoardScratchCount).toList().shuffled()
                        splitNumbers[boardName] = shuffled

                        splitBoardSpecialPrizes[boardName] = shuffled.random().toString()
                        splitBoardGrandPrizes[boardName] = ""
                        splitBoardPitchTypes[boardName] = "scratch"
                        splitBoardClawsCounts[boardName] = "1"
                        splitBoardGiveawayCounts[boardName] = 1

                        splitBoardConfigurations[boardName] = shuffled.mapIndexed { index, num ->
                            NumberConfiguration(id = index + 1, number = num, scratched = false)
                        }
                    }
                    buildAllSplitPreviews(binding.scratchBoardArea, splitNumbers)
                    return@safeExecute
                }
                // 🌟 情況 D：防呆兜底
                else {
                    binding.scratchBoardArea.tag = null
                    boardNames.forEach { boardName ->
                        splitNumbers[boardName] = (1..subBoardScratchCount).toList().shuffled()
                    }
                    buildAllSplitPreviews(binding.scratchBoardArea, splitNumbers)
                    return@safeExecute
                }
            }

            // --- 單一版面原有邏輯 ---
            binding.scratchBoardArea.tag = null
            val scratchesTypeString = "${scratchTypeStr}刮 (${getScratchDimensions(scratchTypeStr)})"

            currentPreviewFragment = if (existingConfigs != null) {
                ScratchBoardPreviewFragment.newInstance(scratchesTypeString, existingConfigs)
            } else {
                ScratchBoardPreviewFragment.newInstance(scratchesTypeString)
            }

            currentPreviewFragment?.arguments?.putBoolean("enable_single_pick", isPickingSpecialPrize)

            childFragmentManager.beginTransaction()
                .replace(binding.scratchBoardArea.id, currentPreviewFragment!!)
                .commitAllowingStateLoss()

            currentPreviewFragment?.setSinglePickEnabled(isPickingSpecialPrize)
            currentPreviewFragment?.setMultiPickEnabled(isPickingGrandPrize)

            childFragmentManager.executePendingTransactions()

            val pickedSpecial = binding.editTextSpecialPrize.text?.toString()?.toIntOrNull()
            currentPreviewFragment?.setSelectedNumber(pickedSpecial)

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

        binding.textPitchRuleReadonly.text = if (isShopping) {
            "消費 $threshold 刮 $giveaway"
        } else {
            "夾 $threshold 刮 $giveaway"
        }
        binding.textPitchRuleReadonly.visibility = View.VISIBLE

        binding.radioGroupPitchType.visibility = View.GONE
        binding.spinnerClawsCount.visibility = View.GONE
        binding.editClawsCount.visibility = View.GONE
        binding.spinnerGiveawayCount.visibility = View.GONE
        binding.textClawsPrefix.visibility = View.GONE
        binding.textClawsUnit.visibility = View.GONE
        binding.textGiveawayPrefix.visibility = View.GONE
        binding.textGiveawayUnit.visibility = View.GONE
    }

    private fun hidePitchRuleReadonly() {
        binding.textPitchRuleReadonly.visibility = View.GONE

        binding.textClawsPrefix.visibility = View.VISIBLE
        binding.textClawsUnit.visibility = View.VISIBLE
        binding.textGiveawayPrefix.visibility = View.VISIBLE
        binding.textGiveawayUnit.visibility = View.VISIBLE

        binding.radioGroupPitchType.visibility = View.VISIBLE
        binding.spinnerGiveawayCount.visibility = View.VISIBLE
    }

    private fun applySplitModeVisibility(isSplitMode: Boolean, isReadonly: Boolean) {
        val specialPrizeContainer = findViewContaining(binding.buttonPickSpecialPrize)
        val grandPrizeContainer = findViewContaining(binding.buttonPickGrandPrize)

        if (isSplitMode) {
            specialPrizeContainer?.visibility = View.GONE
            grandPrizeContainer?.visibility = View.GONE

            specialPrizeLabel?.let { (it.parent as? View)?.visibility = View.GONE }
            grandPrizeLabel?.let { (it.parent as? View)?.visibility = View.GONE }
            binding.textPitchRuleReadonly.visibility = View.GONE

            binding.radioGroupPitchType.visibility = View.GONE
            binding.spinnerGiveawayCount.visibility = View.GONE
            binding.textClawsPrefix.visibility = View.GONE
            binding.textClawsUnit.visibility = View.GONE
            binding.textGiveawayPrefix.visibility = View.GONE
            binding.textGiveawayUnit.visibility = View.GONE

            binding.spinnerClawsCount.visibility = View.GONE
            binding.editClawsCount.visibility = View.GONE

            // 🌟 核心修改：如果是分割版面的「母板」模式，隱藏自動刮開按鈕
            binding.buttonAutoScratch.visibility = View.GONE

            (binding.radioGroupPitchType.parent as? ViewGroup)?.let { parent ->
                for (i in 0 until parent.childCount) {
                    val child = parent.getChildAt(i)
                    if (child is TextView && child.text.toString().contains("玩法規則設定")) {
                        child.visibility = View.GONE
                    }
                }
            }
        } else {
            // 🌟 單一版面維持原樣顯示自動刮開按鈕
            binding.buttonAutoScratch.visibility = View.VISIBLE

            if (isReadonly) {
                specialPrizeContainer?.visibility = View.GONE
                grandPrizeContainer?.visibility = View.GONE
                specialPrizeLabel?.let { (it.parent as? View)?.visibility = View.VISIBLE }
                grandPrizeLabel?.let { (it.parent as? View)?.visibility = View.VISIBLE }
                binding.textPitchRuleReadonly.visibility = View.VISIBLE

                binding.radioGroupPitchType.visibility = View.GONE
                binding.spinnerGiveawayCount.visibility = View.GONE
                binding.textClawsPrefix.visibility = View.GONE
                binding.textClawsUnit.visibility = View.GONE
                binding.textGiveawayPrefix.visibility = View.GONE
                binding.textGiveawayUnit.visibility = View.GONE
            } else {
                specialPrizeContainer?.visibility = View.VISIBLE
                grandPrizeContainer?.visibility = View.VISIBLE
                specialPrizeLabel?.let { (it.parent as? View)?.visibility = View.GONE }
                grandPrizeLabel?.let { (it.parent as? View)?.visibility = View.GONE }
                binding.textPitchRuleReadonly.visibility = View.GONE

                binding.radioGroupPitchType.visibility = View.VISIBLE
                binding.spinnerGiveawayCount.visibility = View.VISIBLE
                binding.textClawsPrefix.visibility = View.VISIBLE
                binding.textClawsUnit.visibility = View.VISIBLE
                binding.textGiveawayPrefix.visibility = View.VISIBLE
                binding.textGiveawayUnit.visibility = View.VISIBLE

                val isShopping = binding.radioPitchShopping.isChecked
                applyPitchTypeUi(isShopping = isShopping, syncValues = false)
            }

            (binding.radioGroupPitchType.parent as? ViewGroup)?.let { parent ->
                for (i in 0 until parent.childCount) {
                    val child = parent.getChildAt(i)
                    if (child is TextView && child.text.toString().contains("玩法規則設定")) {
                        child.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    // ===========================================
    // Firebase 相關方法
    // ===========================================

    private fun deductScratchTypeStock(scratchType: Int, onComplete: (Boolean) -> Unit) {
        if (currentBillingMode == "RENTAL") {
            onComplete(true)
            return
        }

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

    // 🌟 修改點 15：利用 DataSnapshot 避免強依賴 User.kt
    private fun createBackpackValueEventListener(): ValueEventListener {
        return object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                safeExecute("處理背包資料更新") {
                    if (!isAdded || _binding == null) return@safeExecute

                    val user = snapshot.getValue(User::class.java) ?: return@safeExecute

                    currentBillingMode = user.billingMode ?: "POINT"

                    if (currentBillingMode == "RENTAL") {
                        updateSpinnerForRentalMode()
                    } else {
                        // 🌟 改傳 snapshot，而不是 user 實體
                        updateSpinnerWithStockData(snapshot)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("SettingsFragment", "載入背包資料失敗: ${error.message}", error.toException())
            }
        }
    }

    private fun updateSpinnerForRentalMode() {
        val items = listOf(
            ScratchTypeItem(typeStr = "", stock = 0, isPlaceholder = true, showStockInfo = false)
        ) + scratchOrder.map {
            ScratchTypeItem(
                typeStr = it,
                stock = Int.MAX_VALUE,
                isPlaceholder = false,
                showStockInfo = false,
                selectableWithoutStock = true
            )
        }

        val currentSelection = binding.spinnerScratchesCount.selectedItemPosition

        isUpdatingSpinner = true
        val adapter = buildStockAwareAdapter(items)
        binding.spinnerScratchesCount.adapter = adapter

        if (currentSelection in 0 until adapter.count) {
            binding.spinnerScratchesCount.setSelection(currentSelection)
        } else {
            suppressNextScratchTypeSelectionEvent = true
            binding.spinnerScratchesCount.setSelection(0)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(100)
            isUpdatingSpinner = false
        }
    }

    // 🌟 修改點 16：接收 DataSnapshot 動態取值，並過濾無庫存的選項
    private fun updateSpinnerWithStockData(snapshot: DataSnapshot) {
        val stockMap = createStockMap(snapshot)

        // 🌟 1. 過濾出庫存大於 0 的版型
        val availableTypes = scratchOrder.filter { (stockMap[it] ?: 0) > 0 }

        // 🌟 2. 判斷是否有庫存，動態組裝選單
        val items = if (availableTypes.isEmpty()) {
            // 完全沒庫存：只顯示商城引導文字
            listOf(ScratchTypeItem(typeStr = "您目前尚無可用庫存，請至商城購買", stock = 0, isPlaceholder = true))
        } else {
            // 正常顯示：預設「請選擇」 + 那些有庫存的選項
            listOf(ScratchTypeItem(typeStr = "", stock = 0, isPlaceholder = true)) +
                    availableTypes.map { ScratchTypeItem(it, stockMap[it] ?: 0) }
        }

        val currentSelectionStr = try {
            val currentItem = binding.spinnerScratchesCount.selectedItem as? ScratchTypeItem
            currentItem?.getScratchTypeString()
        } catch (e: Exception) { null }

        isUpdatingSpinner = true
        val adapter = buildStockAwareAdapter(items)
        binding.spinnerScratchesCount.adapter = adapter

        // 🌟 3. 嘗試恢復原本選擇的項目
        var targetPos = 0
        if (currentSelectionStr != null) {
            for (i in 0 until adapter.count) {
                val item = adapter.getItem(i)
                if (item?.getScratchTypeString() == currentSelectionStr) {
                    targetPos = i
                    break
                }
            }
        }

        if (targetPos >= 0 && targetPos < adapter.count) {
            // 如果原本選的版型剛好用完被過濾掉了（targetPos 被歸為 0），抑制事件避免預覽區亂跳
            if (targetPos == 0 && currentSelectionStr != null) {
                suppressNextScratchTypeSelectionEvent = true
            }
            binding.spinnerScratchesCount.setSelection(targetPos)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(100)
            isUpdatingSpinner = false
        }
    }

    // 🌟 修改點 17：動態讀取資料庫欄位，不受限於 User.kt
    private fun createStockMap(snapshot: DataSnapshot): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        for (type in scratchOrder) {
            val stock = snapshot.child("scratchType_$type").getValue(Int::class.java) ?: 0
            map[type] = stock
        }
        return map
    }

    // ===========================================
    // Spinner 和適配器相關方法
    // ===========================================

    private fun initSpinnerWithPlaceholder() {
        safeExecute("初始化 Spinner") {
            val items = listOf(ScratchTypeItem(typeStr = "", stock = 0, isPlaceholder = true)) +
                    scratchOrder.map { ScratchTypeItem(it, stock = 1) }

            val adapter = buildStockAwareAdapter(items)
            binding.spinnerScratchesCount.adapter = adapter

            suppressNextScratchTypeSelectionEvent = true
            binding.spinnerScratchesCount.setSelection(0)
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
                    val item = getItem(position) ?: return false
                    if (item.isPlaceholder) return false
                    if (item.selectableWithoutStock) return true
                    item.stock > 0
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

                    // 🌟 恢復穩定的設定，不再區分狀態，統一設定大小與適當的上下留白
                    view.textSize = 12f
                    view.setPadding(4, 12, 4, 12)
                    view.isSingleLine = true
                    view.ellipsize = android.text.TextUtils.TruncateAt.END
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

    private fun handleSpecialPrizeKeyboardClick() {
        val selectedScratchTypeStr = getCurrentScratchType() // 取得如 "20x4" 或 "240"
        if (selectedScratchTypeStr == null) {
            showToast("請先選擇刮數")
            return
        }

        // 🌟 修正：判斷是否為分割版面，若是則抓取 'x' 前面的數字作為子板刮數
        val displayScratchType = if (selectedScratchTypeStr.contains("x")) {
            selectedScratchTypeStr.substringBefore("x").toIntOrNull() ?: 20
        } else {
            selectedScratchTypeStr.toIntOrNull() ?: 240
        }

        val currentValue = binding.editTextSpecialPrize.text.toString()

        uiManager.showSpecialPrizeKeyboard(
            currentValue = if (currentValue.isEmpty()) null else currentValue,
            currentScratchType = displayScratchType, // 傳入解析後的子板刮數 (如 20)
            onConfirm = { validatedInput ->
                val specialPrizeNumber = validatedInput.toIntOrNull()
                if (specialPrizeNumber == null) {
                    showToast("無效的特獎數字")
                    return@showSpecialPrizeKeyboard
                }

                val cleaned = specialPrizeNumber.toString()
                val grandText = binding.editTextGrandPrize.text.toString()
                if (grandText.isNotEmpty()) {
                    val grandList = grandText.split(",").map { it.trim() }.mapNotNull { it.toIntOrNull() }
                    if (grandList.contains(specialPrizeNumber)) {
                        showToast("特獎不能與大獎重複！")
                        return@showSpecialPrizeKeyboard
                    }
                }

                binding.editTextSpecialPrize.setText(cleaned)
                currentPreviewFragment?.setSelectedNumber(specialPrizeNumber)
                showToast("特獎已設定：$cleaned")
            }
        )
    }

    private fun handleGrandPrizeKeyboardClick() {
        val selectedScratchTypeStr = getCurrentScratchType()
        if (selectedScratchTypeStr == null) {
            showToast("請先選擇刮數")
            return
        }

        val isSplitMode = selectedScratchTypeStr.contains("x")
        val displayScratchType = if (isSplitMode) {
            selectedScratchTypeStr.substringBefore("x").toIntOrNull() ?: 20
        } else {
            selectedScratchTypeStr.toIntOrNull() ?: 240
        }

        val currentValue = binding.editTextGrandPrize.text.toString()

        uiManager.showGrandPrizeKeyboard(
            currentValue = if (currentValue.isEmpty()) null else currentValue,
            currentScratchType = displayScratchType,
            isSplitMode = isSplitMode,
            onConfirm = { validatedInput ->

                // 🌟 修正：如果回傳的是空字串，代表使用者清空了所有數字，直接視為「0個大獎」
                if (validatedInput.isBlank()) {
                    binding.editTextGrandPrize.setText("")
                    currentPreviewFragment?.setGrandSelectedNumbers(emptyList())
                    showToast("大獎已清空")
                    return@showGrandPrizeKeyboard
                }

                val cleanedList = validatedInput.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { token -> token.toIntOrNull()?.toString() }

                if (cleanedList.isEmpty()) {
                    showToast("無效的大獎數字")
                    return@showGrandPrizeKeyboard
                }

                val sortedList = cleanedList.mapNotNull { it.toIntOrNull() }.sorted()

                // 🌟 新增防呆：加入單一版面大獎數量上限檢查 (10刮為3，其餘為15)
                val limit = if (isSplitMode) 2 else (GRAND_LIMITS[displayScratchType] ?: 15)

                if (sortedList.size > limit) {
                    val limitMsg = if (isSplitMode) "分割版面的大獎數量限制為 2 個" else "${displayScratchType}刮的大獎數量限制為 ${limit} 個"
                    showToast(limitMsg)
                    return@showGrandPrizeKeyboard
                }

                val specialText = binding.editTextSpecialPrize.text.toString()
                val specialNumber = specialText.toIntOrNull()

                if (specialNumber != null && sortedList.contains(specialNumber)) {
                    showToast("大獎不能包含特獎數字！")
                    return@showGrandPrizeKeyboard
                }

                val sortedText = sortedList.joinToString(", ")
                binding.editTextGrandPrize.setText(sortedText)

                currentPreviewFragment?.setGrandSelectedNumbers(sortedList)

                showToast("大獎已設定：$sortedText")
            }
        )
    }

    // ===========================================
    // 自動刮開
    // ===========================================

    private fun handleAutoScratchClick() {
        val order = shelfManager.selectedShelfOrder
        val selectedCard = viewModel.cards.value[order]
        if (selectedCard == null) {
            showToast("此板位尚未設置刮板")
            return
        }

        // 🌟 分流點：如果是子板聚焦模式，執行專屬的自動刮開運算
        if (currentFocusedSubBoardId != null) {
            val boardName = currentFocusedSubBoardId!!

            // 從暫存區抓出該子板目前的配置
            val configs = splitBoardConfigurations[boardName]
            if (configs.isNullOrEmpty()) {
                showToast("此子板沒有數字配置，無法自動刮開")
                return
            }

            // 取得子板的特獎、大獎字串
            val specialStr = splitBoardSpecialPrizes[boardName]
            val grandStr = splitBoardGrandPrizes[boardName] ?: ""

            val maxX = calcSubBoardAutoScratchMaxX(configs, specialStr, grandStr)

            if (maxX <= 0) {
                showToast("沒有可刮開的格子（已刮/特獎/大獎皆已占滿）")
                return
            }

            showAutoScratchInputDialog(maxX = maxX) { x ->
                showAutoScratchConfirmDialog(x) {
                    performSubBoardAutoScratch(boardName, selectedCard, x)
                }
            }
            return
        }

        // --- 以下維持原本單一版面的自動刮開邏輯 ---
        val configs = selectedCard.numberConfigurations
        if (configs.isNullOrEmpty()) {
            showToast("此刮板沒有數字配置，無法自動刮開")
            return
        }

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

    private fun calcAutoScratchMaxX(card: ScratchCard): Int {
        val configs = card.numberConfigurations ?: return 0

        val special = card.specialPrize?.toIntOrNull()
        val grandSet = (card.grandPrize ?: "")
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()

        val unavailableNumbers = mutableSetOf<Int>()
        configs.filter { it.scratched }.forEach { unavailableNumbers.add(it.number) }
        if (special != null) unavailableNumbers.add(special)
        unavailableNumbers.addAll(grandSet)

        val eligible = configs.filter { !it.scratched && !unavailableNumbers.contains(it.number) }
        return eligible.size
    }

    // 🌟 新增：計算子板專屬的「最大可自動刮開數量」
    private fun calcSubBoardAutoScratchMaxX(
        configs: List<NumberConfiguration>,
        specialPrizeStr: String?,
        grandPrizeStr: String
    ): Int {
        val special = specialPrizeStr?.toIntOrNull()
        val grandSet = grandPrizeStr
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()

        val unavailableNumbers = mutableSetOf<Int>()

        // 1. 過濾已經刮開的
        configs.filter { it.scratched }.forEach { unavailableNumbers.add(it.number) }
        // 2. 過濾特獎
        if (special != null) unavailableNumbers.add(special)
        // 3. 過濾大獎
        unavailableNumbers.addAll(grandSet)

        // 符合資格的 = 沒被刮開 且 不在不可刮名單中的
        val eligible = configs.filter { !it.scratched && !unavailableNumbers.contains(it.number) }
        return eligible.size
    }

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

        edit.setText("")
        edit.setSelection(edit.text.length)

        edit.showSoftInputOnFocus = false
        edit.inputType = android.text.InputType.TYPE_NULL

        edit.isCursorVisible = true

        edit.showSoftInputOnFocus = false

        fun getText(): String = edit.text?.toString() ?: ""
        fun setText(t: String) {
            edit.setText(t)
            edit.setSelection(edit.text.length)
        }

        fun currentValue(): Int = getText().toIntOrNull() ?: 0

        fun setValue(v: Int) {
            val value = v.coerceIn(0, maxX)
            setText(value.toString())
        }

        btnMinus.setOnClickListener { setValue(currentValue() - 1) }
        btnPlus.setOnClickListener { setValue(currentValue() + 1) }

        btnClear.setOnClickListener {
            setText("")
        }

        btnDelete.setOnClickListener {
            val t = getText()
            val newText = if (t.isNotEmpty()) t.dropLast(1) else ""
            setText(newText)
        }

        val numberClickListener = View.OnClickListener { v ->
            val digit = (v as Button).text.toString()
            val current = getText()

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
            .setPositiveButton("確定", null)
            .setNegativeButton("取消", null)
            .create()

        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        dlg.setOnShowListener {
            ToastManager.setHostWindow(dlg.window)

            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val x = currentValue()

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

        dlg.setOnDismissListener {
            ToastManager.clearHostWindow()
        }

        dlg.show()
    }

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

        currentPreviewFragment?.scratchNumbers(scratchedNumbers)

        viewModel.upsertCard(
            order = order,
            scratchesType = card.scratchesType ?: getCurrentScratchType()?.toIntOrNull() ?: 0,
            specialPrize = card.specialPrize,
            grandPrize = card.grandPrize,
            clawsCount = card.clawsCount,
            giveawayCount = card.giveawayCount,
            numberConfigurations = configs,
            existingSerial = card.serialNumber,
            keepInUsed = card.inUsed,
            pitchType = card.pitchType // 🌟 核心修復點：把原本的玩法規則原封不動傳回去，防止被預設值覆蓋！
        )

        val updatedCard = card.copy(numberConfigurations = configs)
        val tempCards = viewModel.cards.value.toMutableMap()
        tempCards[order] = updatedCard
        updateRemainingScratchesInfo(tempCards)

        showSetShelfState(updatedCard)

        showToast("已自動刮開 ${eligibleIdx.size} 格")
    }

    private fun performSubBoardAutoScratch(boardName: String, card: ScratchCard, x: Int) {
        val order = shelfManager.selectedShelfOrder

        // 複製一份暫存區的格子資料來改
        val originalConfigs = splitBoardConfigurations[boardName] ?: return
        val configsToUpdate = originalConfigs.map { it.copy() }.toMutableList()

        val specialStr = splitBoardSpecialPrizes[boardName]
        val grandStr = splitBoardGrandPrizes[boardName] ?: ""

        val special = specialStr?.toIntOrNull()
        val grandSet = grandStr.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()

        val unavailableNumbers = mutableSetOf<Int>()
        configsToUpdate.filter { it.scratched }.forEach { unavailableNumbers.add(it.number) }
        if (special != null) unavailableNumbers.add(special)
        unavailableNumbers.addAll(grandSet)

        // 抽出要被刮開的幸運兒
        val eligibleIdx = configsToUpdate
            .mapIndexedNotNull { idx, cfg ->
                if (!cfg.scratched && !unavailableNumbers.contains(cfg.number)) idx else null
            }
            .shuffled()
            .take(x)

        if (eligibleIdx.isEmpty()) return

        // 修改狀態並紀錄 timestamp
        eligibleIdx.forEach { idx ->
            configsToUpdate[idx].scratched = true
            configsToUpdate[idx].scratchedAt = System.currentTimeMillis()
        }

        // 更新暫存區
        splitBoardConfigurations[boardName] = configsToUpdate

        // 立刻更新畫面上的 UI 視覺
        updateSubBoardCellsUI(boardName)

        val dbRef = com.google.firebase.database.FirebaseDatabase.getInstance(AppConfig.DB_URL).reference
        val userKey = (requireActivity() as UserSessionProvider).getCurrentUserFirebaseKey()
        val serial = card.serialNumber

        if (userKey != null && serial != null) {
            val configsMapList = configsToUpdate.map { cfg ->
                val map = mutableMapOf<String, Any>(
                    "id" to cfg.id,
                    "number" to cfg.number,
                    "scratched" to cfg.scratched
                )
                if (cfg.scratchedAt != null) {
                    map["scratchedAt"] = cfg.scratchedAt!!
                }
                map
            }

            // 🌟 核心防閃爍：開啟寫入鎖定，防止 Firebase 同步時引發全域畫面重繪
            isSavingInProgress = true

            // 寫入 Firebase
            dbRef.child("users").child(userKey).child("scratchCards")
                .child(serial).child("boards").child(boardName).child("numberConfigurations")
                .setValue(configsMapList).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        showToast("已自動刮開 ${eligibleIdx.size} 格！")
                        updateRemainingScratchesInfo(viewModel.cards.value)
                    } else {
                        showToast("寫入失敗：${task.exception?.message}")
                    }

                    // 延遲解除鎖定
                    viewLifecycleOwner.lifecycleScope.launch {
                        kotlinx.coroutines.delay(500)
                        isSavingInProgress = false
                    }
                }

            // 🌟 核心修復點：立刻自動退出子板聚焦模式，退回母板
            exitSubBoardFocusMode()
        }
    }

    // 🌟 修正點 18：取得字串，避免 Null 例外，並正確支援分割版面
    private fun getCurrentScratchType(): String? {
        return try {
            val order = shelfManager.selectedShelfOrder
            val card = viewModel.cards.value[order]

            // 1. 優先判斷已儲存的卡片是否為分割版面
            if (card != null) {
                if (!card.splitMode.isNullOrEmpty()) return card.splitMode
                val cardType = card.scratchesType
                if (cardType != null && cardType > 0) return cardType.toString()
            }

            // 2. 若沒有卡片，判斷草稿
            val draft = viewModel.getDraft(order)
            if (draft != null) {
                val splitModeStr = draft.pitchType?.substringAfter("|", "")
                if (!splitModeStr.isNullOrEmpty()) return splitModeStr
                val draftType = draft.scratchType
                if (draftType != null && draftType > 0) return draftType.toString()
            }

            // 3. 最後才看下拉選單
            val selectedItem = binding.spinnerScratchesCount.selectedItem
            when (selectedItem) {
                is ScratchTypeItem -> selectedItem.getScratchTypeString()
                is String -> {
                    val regex = Regex("(\\d+(x\\d+)?)刮")
                    val match = regex.find(selectedItem)
                    match?.groupValues?.get(1)
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
        val colorDisabled = android.graphics.Color.LTGRAY
        val colorBlue = android.graphics.Color.parseColor("#2196F3")
        val colorPurple = android.graphics.Color.parseColor("#6750A4")

        val disabledTint = android.content.res.ColorStateList.valueOf(colorDisabled)
        val blueTint = android.content.res.ColorStateList.valueOf(colorBlue)
        val purpleTint = android.content.res.ColorStateList.valueOf(colorPurple)

        fun apply(button: View, enabled: Boolean) {
            button.isEnabled = enabled
            button.isClickable = enabled
            button.isFocusable = enabled

            if (button is Button) {
                if (enabled) {
                    if (button.id == R.id.button_save_settings) {
                        button.backgroundTintList = blueTint
                    } else {
                        button.backgroundTintList = purpleTint
                    }
                    button.alpha = 1.0f
                } else {
                    button.backgroundTintList = disabledTint
                    button.alpha = 0.7f
                }
            } else {
                button.alpha = if (enabled) 1.0f else 0.35f
            }
        }

        val order = shelfManager.selectedShelfOrder
        val card = viewModel.cards.value[order]
        // 🌟 修正：只有「已刮過」才強制停用儲存，單純「使用中」仍可儲存
        val forceDisableSave = (card != null) && hasBeenScratched(card)

        apply(binding.buttonSaveSettings, if (forceDisableSave) false else save)
        apply(binding.buttonToggleInuse, toggleInUse)
        apply(binding.buttonAutoScratch, autoScratch)
        val returnEnabled = returnBtn && (currentBillingMode != "RENTAL")
        apply(binding.buttonReturnSelected, returnEnabled)
        apply(binding.buttonDeleteSelected, delete)
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

    // 🌟 修改點 19：讓 getScratchDimensions 支援字串
    private fun getScratchDimensions(scratchTypeStr: String): String {
        return when (scratchTypeStr) {
            "10" -> "2x5"
            "20" -> "4x5"
            "25" -> "5x5"
            "30" -> "5x6"
            "40" -> "5x8"
            "50" -> "5x10"
            "60" -> "6x10"
            "80" -> "8x10"
            "100" -> "10x10"
            "120" -> "10x12"
            "160" -> "10x16"
            "200" -> "10x20"
            "240" -> "12x20"
            "20x4", "20x6", "25x4", "25x6", "30x4", "30x6" -> "分割預覽" // 暫時給定文字，不影響目前邏輯
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
        saveDraftIfNeeded(shelfManager.selectedShelfOrder)
    }

    private fun saveDraftIfNeeded(order: Int) {
        // 🌟 登出與關閉防呆：檢查使用者是否正在執行登出
        // 當 MainActivity 點擊登出時，會將 currentUser 設為 null
        val userKey = (activity as? com.champion.king.UserSessionProvider)?.getCurrentUserFirebaseKey()
        if (userKey == null) {
            // 偵測到無使用者狀態，絕對不允許再存入草稿，並強制清空所有快取！
            viewModel.clearDraft(order)
            clearAllSplitDraftCaches()
            return
        }

        val hasCard = viewModel.cards.value[order] != null
        if (hasCard) return

        val configs = currentPreviewFragment?.getGeneratedNumberConfigurations()

        val selectedItem = binding.spinnerScratchesCount.selectedItem as? ScratchTypeItem
        val scratchTypeStr = selectedItem?.getScratchTypeString()

        // 🌟 核心防呆：如果目前是未設置狀態，清空草稿並同步清除快取
        if (scratchTypeStr.isNullOrEmpty()) {
            viewModel.clearDraft(order)
            clearSplitDraftCache(order)
            return
        }

        val isShopping = binding.radioPitchShopping.isChecked
        val basePitchType = if (isShopping) "shopping" else "scratch"

        // 🌟 核心寫入：如果是分割版面，將子板的暫存寫入 Companion 快取中
        val storedPitchType = if (scratchTypeStr.contains("x")) {
            splitBoardSpecialPrizesCache[order] = splitBoardSpecialPrizes.toMutableMap()
            splitBoardGrandPrizesCache[order] = splitBoardGrandPrizes.toMutableMap()
            splitBoardPitchTypesCache[order] = splitBoardPitchTypes.toMutableMap()
            splitBoardClawsCountsCache[order] = splitBoardClawsCounts.toMutableMap()
            splitBoardGiveawayCountsCache[order] = splitBoardGiveawayCounts.toMutableMap()
            splitBoardConfigurationsCache[order] = splitBoardConfigurations.toMutableMap()

            "${basePitchType}|${scratchTypeStr}"
        } else {
            basePitchType
        }

        val clawsValue: Int? = if (isShopping) {
            val t = binding.editClawsCount.text?.toString()?.trim().orEmpty()
            if (t.isEmpty()) 0 else t.toIntOrNull()
        } else {
            binding.spinnerClawsCount.selectedItem?.toString()?.toIntOrNull()
        }

        val draft = SettingsViewModel.SettingsDraft(
            scratchType = scratchTypeStr.toIntOrNull() ?: 0, // 分割版面轉為 0
            specialPrize = binding.editTextSpecialPrize.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            grandPrize = binding.editTextGrandPrize.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            claws = clawsValue,
            giveaway = binding.spinnerGiveawayCount.selectedItem?.toString()?.toIntOrNull(),
            numberConfigurations = configs,
            pitchType = storedPitchType
        )

        viewModel.saveDraft(order, draft)
    }

    // 🌟 修改點 20：比對字串
    private fun setScratchTypeSpinnerSelection(scratchTypeStr: String) {
        isUpdatingSpinner = true
        try {
            val adapter = binding.spinnerScratchesCount.adapter ?: return
            val currentPos = binding.spinnerScratchesCount.selectedItemPosition

            var targetPos: Int? = null
            for (i in 0 until adapter.count) {
                val item = adapter.getItem(i) as? ScratchTypeItem ?: continue
                if (item.getScratchTypeString() == scratchTypeStr) {
                    targetPos = i
                    break
                }
            }
            if (targetPos == null) return

            if (targetPos != currentPos) {
                suppressNextScratchTypeSelectionEvent = true
                binding.spinnerScratchesCount.setSelection(targetPos)
            } else {
                suppressNextScratchTypeSelectionEvent = false
            }
        } finally {
            isUpdatingSpinner = false
        }
    }

    private fun applyPitchTypeUi(isShopping: Boolean, syncValues: Boolean = true) {
        binding.textClawsPrefix.text = if (isShopping) "消費" else "夾出"
        binding.textClawsUnit.text = if (isShopping) "元" else "樣"

        if (isShopping) {
            binding.spinnerClawsCount.visibility = View.GONE
            binding.editClawsCount.visibility = View.VISIBLE

            binding.spinnerGiveawayCount.visibility = View.VISIBLE

            if (syncValues) {
                val claws = binding.spinnerClawsCount.selectedItem?.toString()?.toIntOrNull() ?: 1
                binding.editClawsCount.setText(claws.toString())
            }
        } else {
            binding.spinnerClawsCount.visibility = View.VISIBLE
            binding.editClawsCount.visibility = View.GONE

            binding.spinnerGiveawayCount.visibility = View.VISIBLE

            if (syncValues) {
                val claws = binding.editClawsCount.text?.toString()?.toIntOrNull() ?: 1
                setSpinnerSelection(binding.spinnerClawsCount, claws.coerceIn(1, 5))
            }
        }
    }

    private fun applySavedPitchRule(card: ScratchCard?) {
        if (card == null) {
            binding.radioPitchScratch.isChecked = true
            applyPitchTypeUi(isShopping = false, syncValues = false)
            return
        }

        val isShopping = (card.pitchType == "shopping")
        if (isShopping) {
            binding.radioPitchShopping.isChecked = true
            applyPitchTypeUi(isShopping = true, syncValues = false)

            val v = card.clawsCount ?: 0
            binding.editClawsCount.setText(v.toString())
        } else {
            binding.radioPitchScratch.isChecked = true
            applyPitchTypeUi(isShopping = false, syncValues = false)

            val v = (card.clawsCount ?: 1).coerceIn(1, 5)
            setSpinnerSelection(binding.spinnerClawsCount, v)
        }

        val give = (card.giveawayCount ?: 1).coerceIn(1, 5)
        setSpinnerSelection(binding.spinnerGiveawayCount, give)
    }

    private fun buildAllSplitPreviews(mainContainer: ViewGroup, boardNumbers: Map<String, List<Int>>) {
        currentFocusedSubBoardId = null // 🌟 每次重新建立版面時，重置聚焦狀態
        mainContainer.removeAllViews()

        val context = mainContainer.context

        val verticalWrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            weightSum = 2f
        }

        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            weightSum = 2f
        }

        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = (4 * context.resources.displayMetrics.density).toInt()
            }
            weightSum = 2f
        }

        // 🌟 動態判斷當前子板的刮數 (20, 25 或 30)，讓迴圈正確對應
        val defaultCount = boardNumbers.values.firstOrNull()?.size ?: 20
        val defaultNumbers = (1..defaultCount).toList()

        row1.addView(createSingleBoardPreview(context, "A", boardNumbers["A"] ?: defaultNumbers))
        row1.addView(createSingleBoardPreview(context, "B", boardNumbers["B"] ?: defaultNumbers))
        row2.addView(createSingleBoardPreview(context, "C", boardNumbers["C"] ?: defaultNumbers))
        row2.addView(createSingleBoardPreview(context, "D", boardNumbers["D"] ?: defaultNumbers))

        verticalWrapper.addView(row1)
        verticalWrapper.addView(row2)

        // 🌟 正式將四個子板貼到畫面上
        mainContainer.addView(verticalWrapper)

        // 🌟 核心修改：在全部視圖加入到 mainContainer (畫面上) 後，統一更新一次各子板的格子 UI 與上方標題 UI
        listOf("A", "B", "C", "D").forEach { boardName ->
            updateSubBoardCellsUI(boardName)
            renderSubBoardHeaderUI(boardName) // 🌟 補上這行，標題列就會顯示金圈特獎了！
        }
    }

    private fun createSingleBoardPreview(context: android.content.Context, boardName: String, numbers: List<Int>): View {
        val boardLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(4, 4, 4, 4)
            }
            setBackgroundColor(Color.BLACK)
            setPadding(8, 8, 8, 8)

            tag = "sub_board_$boardName"
            isClickable = true
            setOnClickListener {
                // 🌟 新增防呆：如果是使用中的板位，直接阻擋進入聚焦模式
                val order = shelfManager.selectedShelfOrder
                val card = viewModel.cards.value[order]
                if (card != null && card.inUsed) {
                    showToast("此為使用中板位，無法進行子板編輯")
                    return@setOnClickListener
                }

                if (currentFocusedSubBoardId == boardName) {
                    // 點擊正在聚焦的自己：無反應，不再跳出聚焦模式
                    return@setOnClickListener
                } else {
                    if (isPickingSpecialPrize || isPickingGrandPrize) {
                        showToast("請先取消選取模式，再切換子板")
                        return@setOnClickListener
                    }

                    // 點擊其他子板：如果目前有聚焦的板，先把它暫存並退出
                    if (currentFocusedSubBoardId != null) {
                        exitSubBoardFocusMode()
                    }
                    // 接著直接進入點擊的那個新子板
                    enterSubBoardFocusMode(boardName)
                }
            }
        }

        val density = context.resources.displayMetrics.density

        val headerLayout = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            setPadding(4, 0, 0, 8)
            minimumHeight = (36 * density).toInt()
            tag = "header_$boardName"
        }
        boardLayout.addView(headerLayout)

        val titleView = TextView(context).apply {
            tag = "title"
            text = "${boardName}板"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, (8 * density).toInt(), 0)
        }
        headerLayout.addView(titleView)

        val tvSpecialLabel = TextView(context).apply {
            tag = "special_label"
            text = "特獎:"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = (2 * density).toInt()
            }
            visibility = View.GONE
        }
        headerLayout.addView(tvSpecialLabel)

        val specialSize = (26 * density).toInt()
        val tvSpecial = TextView(context).apply {
            tag = "special"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(context, R.drawable.circle_cell_normal_background)?.mutate()?.apply {
                if (this is android.graphics.drawable.GradientDrawable) {
                    val gold = ContextCompat.getColor(context, R.color.scratch_card_gold)
                    setColor(gold)
                    setStroke(2, gold)
                }
            }
            layoutParams = LinearLayout.LayoutParams(specialSize, specialSize).apply {
                marginEnd = (8 * density).toInt()
            }
            visibility = View.GONE
        }
        headerLayout.addView(tvSpecial)

        val tvGrandLabel = TextView(context).apply {
            tag = "grand_label"
            text = "大獎:"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = (2 * density).toInt()
                marginEnd = (2 * density).toInt()
            }
            visibility = View.GONE
        }
        headerLayout.addView(tvGrandLabel)

        for (i in 0..2) {
            val tvGrand = TextView(context).apply {
                tag = "grand_$i"
                setTextColor(Color.WHITE)
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(context, R.drawable.circle_cell_normal_background)?.mutate()?.apply {
                    if (this is android.graphics.drawable.GradientDrawable) {
                        val green = ContextCompat.getColor(context, R.color.scratch_card_green)
                        setColor(green)
                        setStroke(2, green)
                    }
                }
                layoutParams = LinearLayout.LayoutParams(specialSize, specialSize).apply {
                    marginEnd = (4 * density).toInt()
                }
                visibility = View.GONE
            }
            headerLayout.addView(tvGrand)
        }

        val tvMore = TextView(context).apply {
            tag = "more"
            text = "..."
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = (2 * density).toInt()
            }
            visibility = View.GONE
        }
        headerLayout.addView(tvMore)

        val space = Space(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerLayout.addView(space)

        val tvRule = TextView(context).apply {
            tag = "rule"
            setTextColor(Color.parseColor("#FFC107")) // 黃色
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = (2 * density).toInt()
            }
        }
        headerLayout.addView(tvRule)

        renderSubBoardHeaderUI(boardName)

        // 🌟 核心修改：動態計算行列與大小
        val cellCount = numbers.size
        val targetRowCount = when (cellCount) {
            25 -> 5
            30 -> 5
            else -> 4
        }
        val targetColCount = when (cellCount) {
            25 -> 5
            30 -> 6
            else -> 5
        }
        val targetCircleSizeDp = when (cellCount) {
            25 -> 30  // 稍微縮小避免破版
            30 -> 26
            else -> 36
        }
        val targetTextSize = when (cellCount) {
            25 -> 12f
            30 -> 11f
            else -> 14f
        }

        val gridContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val gridLayout = GridLayout(context).apply {
            tag = "grid_$boardName"
            rowCount = targetRowCount
            columnCount = targetColCount
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.CENTER
            }
            setBackgroundColor(Color.BLACK)
        }

        val cellMargin = (1 * density).toInt()
        val circleSize = (targetCircleSizeDp * density).toInt()

        // 🌟 準備好邊框顏色和獎項數字，供下方格子判斷使用
        val specialPrize = splitBoardSpecialPrizes[boardName]
        val grandPrize = splitBoardGrandPrizes[boardName]

        val specialNum = specialPrize?.toIntOrNull()
        val grandNums = if (!grandPrize.isNullOrEmpty() && grandPrize != "無") {
            grandPrize.split(",").mapNotNull { it.trim().toIntOrNull() }
        } else {
            emptyList()
        }

        val colorGold = ContextCompat.getColor(context, R.color.scratch_card_gold)
        val colorGreen = ContextCompat.getColor(context, R.color.scratch_card_green)
        val colorGrey = Color.GRAY

        val configs = splitBoardConfigurations[boardName]

        for (i in 0 until cellCount) {
            val cellFrame = FrameLayout(context).apply {
                setBackgroundColor(Color.WHITE)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(cellMargin, cellMargin, cellMargin, cellMargin)
                }

                isClickable = true
                setOnClickListener {
                    // 🌟 新增防呆：連點擊格子也要阻擋，避免被當成切換子板的觸發點
                    val order = shelfManager.selectedShelfOrder
                    val card = viewModel.cards.value[order]
                    if (card != null && card.inUsed) {
                        showToast("此為使用中板位，無法進行子板編輯")
                        return@setOnClickListener
                    }

                    if (currentFocusedSubBoardId != boardName) {
                        if (isPickingSpecialPrize || isPickingGrandPrize) {
                            showToast("請先取消選取模式，再切換子板")
                            return@setOnClickListener
                        }

                        if (currentFocusedSubBoardId != null) {
                            exitSubBoardFocusMode()
                        }
                        enterSubBoardFocusMode(boardName)
                    } else {
                        val number = numbers.getOrNull(i) ?: return@setOnClickListener

                        if (isPickingSpecialPrize) {
                            val currentGrandStr = binding.editTextGrandPrize.text.toString()
                            val grandList = currentGrandStr.split(",").mapNotNull { it.trim().toIntOrNull() }

                            if (grandList.contains(number)) {
                                showToast("此數字已在大獎清單，請先取消大獎再選為特獎")
                                return@setOnClickListener
                            }

                            binding.editTextSpecialPrize.setText(number.toString())

                        } else if (isPickingGrandPrize) {
                            val currentSpecialStr = binding.editTextSpecialPrize.text.toString()
                            val specialNumPick = currentSpecialStr.toIntOrNull()

                            if (specialNumPick == number) {
                                showToast("此數字已是特獎，無法加入大獎清單")
                                return@setOnClickListener
                            }

                            val currentGrandStr = binding.editTextGrandPrize.text.toString()
                            val grandList = currentGrandStr.split(",").mapNotNull { it.trim().toIntOrNull() }.toMutableList()

                            if (grandList.contains(number)) {
                                grandList.remove(number)
                            } else {
                                // 🌟 核心修復：這裡是子板專屬的建立邏輯，大獎上限絕對是 2 個
                                if (grandList.size >= 2) {
                                    showToast("分割版面的大獎數量限制為 2 個")
                                    return@setOnClickListener
                                }
                                grandList.add(number)
                            }

                            val sortedText = grandList.sorted().joinToString(", ")
                            binding.editTextGrandPrize.setText(sortedText)

                        } else {
                            android.util.Log.d("SettingsFragment", "總裁，您點擊了 ${boardName}板 的第 $number 號碼")
                        }
                    }
                }
            }

            val circleView = TextView(context).apply {
                val number = numbers.getOrNull(i) ?: 0
                text = number.toString()

                textSize = targetTextSize
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER

                // 🌟 根據「已刮開」狀態動態渲染 UI 視覺
                val config = configs?.get(i)
                val isScratched = config?.scratched ?: false

                if (isScratched) {
                    setTextColor(Color.BLACK) // 白底黑字

                    val borderColor = when {
                        number == specialNum -> colorGold
                        grandNums.contains(number) -> colorGreen
                        else -> colorGrey
                    }

                    background = ContextCompat.getDrawable(context, R.drawable.circle_cell_normal_background)?.mutate()?.apply {
                        if (this is android.graphics.drawable.GradientDrawable) {
                            setColor(Color.WHITE) // 白底
                            setStroke(3, borderColor) // 稍微加粗邊框讓狀態更明顯
                        }
                    }
                } else {
                    setTextColor(Color.GRAY) // 黑底灰字
                    background = ContextCompat.getDrawable(context, R.drawable.circle_cell_background_black)
                }

                layoutParams = FrameLayout.LayoutParams(circleSize, circleSize).apply {
                    gravity = Gravity.CENTER
                }
            }

            cellFrame.addView(circleView)
            gridLayout.addView(cellFrame)
        }

        gridContainer.addView(gridLayout)
        boardLayout.addView(gridContainer)

        return boardLayout
    }

    // ==========================================
    // 🌟 分割版面專屬：子板聚焦模式控制
    // ==========================================
    private var currentFocusedSubBoardId: String? = null

    private fun enterSubBoardFocusMode(boardName: String) {
        currentFocusedSubBoardId = boardName
        val order = shelfManager.selectedShelfOrder
        binding.textParametersTitle.text = "${order}號板${boardName}板參數設定"

        binding.onShelfListContainer.alpha = 0.35f
        setEnabledRecursively(binding.onShelfListContainer, false)
        updateSubBoardsAlpha(boardName)

        // 1. UI 顯示切換：隱藏刮數列，顯示右側容器
        binding.layoutScratchCountRow.visibility = View.GONE
        binding.rightPanelContainer.visibility = View.VISIBLE

        // 準備該子板的暫存資料
        val specialStr = splitBoardSpecialPrizes[boardName] ?: ""
        val grandStr = splitBoardGrandPrizes[boardName] ?: ""
        val pitchType = splitBoardPitchTypes[boardName] ?: "scratch"
        val claws = splitBoardClawsCounts[boardName] ?: "1"
        val giveaway = splitBoardGiveawayCounts[boardName] ?: 1

        // 2. 徹底解決 A 切 B 的干擾：先移除竊聽器
        binding.editTextSpecialPrize.removeTextChangedListener(splitSpecialPrizeWatcher)
        binding.editTextGrandPrize.removeTextChangedListener(splitGrandPrizeWatcher)

        // 3. 安全地載入該子板的特獎/大獎暫存資料到輸入框 (即使隱藏也塞入值)
        binding.editTextSpecialPrize.setText(specialStr)
        binding.editTextGrandPrize.setText(grandStr)

        // 先把下拉選單的數值與基礎 UI 顯示設定好
        if (pitchType == "shopping") {
            binding.radioPitchShopping.isChecked = true
            applyPitchTypeUi(isShopping = true, syncValues = false)
            binding.editClawsCount.setText(claws)
        } else {
            binding.radioPitchScratch.isChecked = true
            applyPitchTypeUi(isShopping = false, syncValues = false)
            setSpinnerSelection(binding.spinnerClawsCount, claws.toIntOrNull() ?: 1)
        }
        setSpinnerSelection(binding.spinnerGiveawayCount, giveaway)

        // 判斷該子板是否已經有被刮開的格子
        val configs = splitBoardConfigurations[boardName]
        val isBoardScratched = configs?.any { it.scratched } == true

        val specialPrizeContainer = findViewContaining(binding.buttonPickSpecialPrize)
        val grandPrizeContainer = findViewContaining(binding.buttonPickGrandPrize)

        // 依據是否刮過，作為最後一步強制覆蓋元件的隱藏狀態
        if (isBoardScratched) {
            setupReadonlyLabels(specialStr, grandStr)

            // 已刮過：強制唯讀，隱藏編輯框，顯示唯讀文字
            specialPrizeContainer?.visibility = View.GONE
            grandPrizeContainer?.visibility = View.GONE

            specialPrizeLabel?.let { (it.parent as? View)?.visibility = View.VISIBLE }
            grandPrizeLabel?.let { (it.parent as? View)?.visibility = View.VISIBLE }

            (binding.radioGroupPitchType.parent as? ViewGroup)?.let { parent ->
                parent.visibility = View.VISIBLE
                for (i in 0 until parent.childCount) {
                    val child = parent.getChildAt(i)
                    if (child is TextView && child.text.toString().contains("玩法規則設定")) {
                        child.visibility = View.VISIBLE // 標題保留
                    }
                }
            }

            // 設定規則唯讀文字並顯示
            if (pitchType == "shopping") {
                binding.textPitchRuleReadonly.text = "消費 $claws 刮 $giveaway"
            } else {
                binding.textPitchRuleReadonly.text = "夾 $claws 刮 $giveaway"
            }
            binding.textPitchRuleReadonly.visibility = View.VISIBLE

            // 將所有規則輸入的下拉選單、單位文字「強制隱藏」！
            binding.radioGroupPitchType.visibility = View.GONE
            binding.spinnerGiveawayCount.visibility = View.GONE
            binding.textClawsPrefix.visibility = View.GONE
            binding.textClawsUnit.visibility = View.GONE
            binding.textGiveawayPrefix.visibility = View.GONE
            binding.textGiveawayUnit.visibility = View.GONE
            binding.spinnerClawsCount.visibility = View.GONE
            binding.editClawsCount.visibility = View.GONE

        } else {
            // ⬜ 沒刮過：保持可編輯
            specialPrizeContainer?.visibility = View.VISIBLE
            grandPrizeContainer?.visibility = View.VISIBLE

            // 確保在子板模式下，把母板上鎖的控制項重新解鎖！
            setPrizeControlsEnabled(true)

            specialPrizeLabel?.let { (it.parent as? View)?.visibility = View.GONE }
            grandPrizeLabel?.let { (it.parent as? View)?.visibility = View.GONE }
            binding.textPitchRuleReadonly.visibility = View.GONE

            (binding.radioGroupPitchType.parent as? ViewGroup)?.let { parent ->
                parent.visibility = View.VISIBLE
                for (i in 0 until parent.childCount) {
                    val child = parent.getChildAt(i)
                    if (child is TextView && child.text.toString().contains("玩法規則設定")) {
                        child.visibility = View.VISIBLE
                    }
                }
            }

            binding.radioGroupPitchType.visibility = View.VISIBLE
            binding.textClawsPrefix.visibility = View.VISIBLE
            binding.textClawsUnit.visibility = View.VISIBLE
            binding.textGiveawayPrefix.visibility = View.VISIBLE
            binding.textGiveawayUnit.visibility = View.VISIBLE
            binding.spinnerGiveawayCount.visibility = View.VISIBLE

            // 根據 pitchType 恢復對應的控制項顯示
            if (pitchType == "shopping") {
                binding.spinnerClawsCount.visibility = View.GONE
                binding.editClawsCount.visibility = View.VISIBLE
            } else {
                binding.spinnerClawsCount.visibility = View.VISIBLE
                binding.editClawsCount.visibility = View.GONE
            }
        }

        // 🌟 核心按鈕控制：子板模式「顯示」自動刮開，「隱藏」設為使用中、返回與刪除
        binding.buttonAutoScratch.visibility = View.VISIBLE
        binding.buttonToggleInuse.visibility = View.GONE
        binding.rowActionsReturnDelete.visibility = View.GONE
        binding.buttonBackToMaster.visibility = View.VISIBLE

        // ✅ 修復：連續切換子板 (A -> B) 時，按鈕被 exitSubBoardFocusMode 設為 disabled 的問題
        // 強制恢復「自動刮開」按鈕為啟用狀態 (紫色)
        binding.buttonAutoScratch.apply {
            isEnabled = true
            isClickable = true
            isFocusable = true
            backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#6750A4"))
            alpha = 1.0f
        }

        // 根據子板是否已被刮開，動態設定「儲存」按鈕的啟用狀態 (藍色 / 灰色)
        binding.buttonSaveSettings.apply {
            val canSave = !isBoardScratched
            isEnabled = canSave
            isClickable = canSave
            isFocusable = canSave
            if (canSave) {
                backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2196F3"))
                alpha = 1.0f
            } else {
                backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.LTGRAY)
                alpha = 0.7f
            }
        }

        // 5. 資料塞好後，再重新掛上竊聽器
        binding.editTextSpecialPrize.addTextChangedListener(splitSpecialPrizeWatcher)
        binding.editTextGrandPrize.addTextChangedListener(splitGrandPrizeWatcher)
    }

    private fun exitSubBoardFocusMode() {
        // 1. 在退出前，先把當前 UI 的數值存進子板地圖中！
        currentFocusedSubBoardId?.let { boardName ->
            val isShopping = binding.radioPitchShopping.isChecked
            splitBoardPitchTypes[boardName] = if (isShopping) "shopping" else "scratch"

            splitBoardClawsCounts[boardName] = if (isShopping) {
                binding.editClawsCount.text?.toString() ?: "0"
            } else {
                binding.spinnerClawsCount.selectedItem?.toString() ?: "1"
            }

            splitBoardGiveawayCounts[boardName] = binding.spinnerGiveawayCount.selectedItem?.toString()?.toIntOrNull() ?: 1

            renderSubBoardHeaderUI(boardName)
        }

        // 2. 拔除竊聽器
        binding.editTextSpecialPrize.removeTextChangedListener(splitSpecialPrizeWatcher)
        binding.editTextGrandPrize.removeTextChangedListener(splitGrandPrizeWatcher)

        currentFocusedSubBoardId = null

        // 3. 準備無縫還原母板 UI 狀態
        val order = shelfManager.selectedShelfOrder
        val card = viewModel.cards.value[order]
        val isReadonly = card != null && hasBeenScratched(card)
        val scratchTypeStr = if (card != null) {
            if (!card.splitMode.isNullOrEmpty()) card.splitMode!! else card.scratchesType.toString()
        } else {
            getCurrentScratchType() ?: "20x4"
        }

        updateParametersTitle(order)

        // 核心防閃爍：根據是否已儲存，預先設定好正確的「刮數文字」或「下拉選單」
        if (card != null) {
            showScratchTypeLabel(scratchTypeStr)
        } else {
            showScratchTypeSpinner()
        }

        binding.onShelfListContainer.alpha = 1.0f
        setEnabledRecursively(binding.onShelfListContainer, true)
        updateSubBoardsAlpha(null)

        // 清空右側文字
        binding.editTextSpecialPrize.setText("")
        binding.editTextGrandPrize.setText("")

        // 4. 一次性強制套用母板 UI
        binding.layoutScratchCountRow.visibility = View.VISIBLE
        binding.rightPanelContainer.visibility = View.VISIBLE

        // 呼叫統一的顯示管理方法，完美隱藏子板專屬欄位並乾淨俐落地處理唯讀標籤
        applySplitModeVisibility(isSplitMode = true, isReadonly = isReadonly)

        // 🌟 核心修復點：退出子板時，強制把「設為使用中」與「返回/刪除」列恢復顯示！
        binding.buttonToggleInuse.visibility = View.VISIBLE
        binding.rowActionsReturnDelete.visibility = View.VISIBLE

        // 🌟 隱藏專屬的返回母板按鈕
        binding.buttonBackToMaster.visibility = View.GONE

        // 同步還原下方按鈕的狀態
        if (card != null) {
            uiManager.updateInUseButtonUI(card)
            uiManager.updateActionButtonsUI(card)
            if (isReadonly) {
                setButtonsEnabled(save = false, toggleInUse = true, autoScratch = false, returnBtn = false, delete = !card.inUsed)
            } else {
                setButtonsEnabled(save = true, toggleInUse = true, autoScratch = false, returnBtn = !card.inUsed, delete = !card.inUsed)
            }
        } else {
            setButtonsEnabled(save = true, toggleInUse = false, autoScratch = false, returnBtn = false, delete = false)
        }
        updateRefreshButtonVisibility()
    }

    private fun updateSubBoardsAlpha(focusedBoardName: String?) {
        val root = binding.scratchBoardArea

        fun applyAlpha(view: View) {
            val tag = view.tag as? String
            if (tag != null && tag.startsWith("sub_board_")) {
                if (focusedBoardName == null || tag == "sub_board_$focusedBoardName") {
                    view.alpha = 1.0f
                } else {
                    view.alpha = 0.35f
                }
            } else if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    applyAlpha(view.getChildAt(i))
                }
            }
        }
        applyAlpha(root)
    }

    private fun renderSubBoardHeaderUI(boardName: String) {
        val root = binding.scratchBoardArea
        val headerLayout = root.findViewWithTag<LinearLayout>("header_$boardName") ?: return

        val specialPrize = splitBoardSpecialPrizes[boardName]
        val grandPrize = splitBoardGrandPrizes[boardName]

        // 🌟 更新特獎：連同標籤一起判斷顯示
        val hasSpecial = !specialPrize.isNullOrEmpty() && specialPrize != "無"
        val tvSpecialLabel = headerLayout.findViewWithTag<TextView>("special_label")
        val tvSpecial = headerLayout.findViewWithTag<TextView>("special")

        if (tvSpecialLabel != null) {
            tvSpecialLabel.visibility = if (hasSpecial) View.VISIBLE else View.GONE
        }

        if (tvSpecial != null) {
            if (hasSpecial) {
                tvSpecial.text = specialPrize
                tvSpecial.visibility = View.VISIBLE
            } else {
                tvSpecial.visibility = View.GONE
            }
        }

        // 🌟 更新大獎：連同標籤一起判斷顯示
        val grandNumbers = if (!grandPrize.isNullOrEmpty() && grandPrize != "無") {
            grandPrize.split(",").mapNotNull { it.trim().toIntOrNull() }
        } else {
            emptyList()
        }

        val tvGrandLabel = headerLayout.findViewWithTag<TextView>("grand_label")
        if (tvGrandLabel != null) {
            tvGrandLabel.visibility = if (grandNumbers.isNotEmpty()) View.VISIBLE else View.GONE
        }

        for (i in 0..2) {
            val tvGrand = headerLayout.findViewWithTag<TextView>("grand_$i")
            if (tvGrand != null) {
                if (i < grandNumbers.size) {
                    tvGrand.text = grandNumbers[i].toString()
                    tvGrand.visibility = View.VISIBLE
                } else {
                    tvGrand.visibility = View.GONE
                }
            }
        }

        val tvMore = headerLayout.findViewWithTag<TextView>("more")
        if (tvMore != null) {
            if (grandNumbers.size > 3) {
                tvMore.visibility = View.VISIBLE
            } else {
                tvMore.visibility = View.GONE
            }
        }

        val tvRule = headerLayout.findViewWithTag<TextView>("rule")
        if (tvRule != null) {
            val pitchType = splitBoardPitchTypes[boardName] ?: "scratch"
            val claws = splitBoardClawsCounts[boardName] ?: "1"
            val giveaway = splitBoardGiveawayCounts[boardName] ?: 1

            if (pitchType == "shopping") {
                tvRule.text = "消費${claws}元刮${giveaway}"
            } else {
                tvRule.text = "夾${claws}刮${giveaway}"
            }
        }
    }

    private fun updateSubBoardCellsUI(boardName: String) {
        val root = binding.scratchBoardArea
        // 透過 Tag 找到該子板專屬的 GridLayout
        val gridLayout = root.findViewWithTag<GridLayout>("grid_$boardName") ?: return
        val context = gridLayout.context

        // 取得目前最新的獎項設定
        val specialStr = splitBoardSpecialPrizes[boardName] ?: ""
        val grandStr = splitBoardGrandPrizes[boardName] ?: ""

        val specialList = specialStr.split(",").mapNotNull { it.trim().toIntOrNull() }
        val grandList = grandStr.split(",").mapNotNull { it.trim().toIntOrNull() }

        // 🌟 取得該子板目前的格子配置 (包含是否已被刮開的狀態)
        val configs = splitBoardConfigurations[boardName]

        val colorGold = ContextCompat.getColor(context, R.color.scratch_card_gold)
        val colorGreen = ContextCompat.getColor(context, R.color.scratch_card_green)
        val colorGrey = Color.GRAY
        val darkGray = ContextCompat.getColor(context, R.color.scratch_card_dark_gray)

        // 遍歷所有 20 個格子，比對數字並重新上色
        for (i in 0 until gridLayout.childCount) {
            val cellFrame = gridLayout.getChildAt(i) as? FrameLayout ?: continue
            val circleView = cellFrame.getChildAt(0) as? TextView ?: continue

            val numberStr = circleView.text.toString()
            val number = numberStr.toIntOrNull() ?: continue

            // 🌟 判斷該格子是否已被刮開
            val isScratched = configs?.getOrNull(i)?.scratched ?: configs?.find { it.number == number }?.scratched ?: false

            if (isScratched) {
                // =====================================
                // 🌟 已刮開的格子視覺 (白底黑字)
                // =====================================
                circleView.setTextColor(Color.BLACK) // 黑字

                val borderColor = when {
                    specialList.contains(number) -> colorGold    // 特獎用黃框
                    grandList.contains(number) -> colorGreen // 大獎用綠框
                    else -> colorGrey // 一般數字用灰框
                }

                circleView.background = ContextCompat.getDrawable(context, R.drawable.circle_cell_normal_background)?.mutate()?.apply {
                    if (this is android.graphics.drawable.GradientDrawable) {
                        setColor(Color.WHITE) // 白底
                        setStroke(3, borderColor)
                    }
                }
            } else {
                // =====================================
                // ⬛ 未刮開的格子視覺 (黑底/灰底 + 灰字/白字)
                // =====================================
                when {
                    specialList.contains(number) -> {
                        // 🌟 特獎：白色粗體字 + 深灰底 + 金色加粗外框
                        circleView.setTextColor(Color.WHITE)
                        circleView.background = ContextCompat.getDrawable(context, R.drawable.circle_cell_normal_background)?.mutate()?.apply {
                            if (this is android.graphics.drawable.GradientDrawable) {
                                setColor(darkGray)
                                setStroke(4, colorGold)
                            }
                        }
                    }
                    grandList.contains(number) -> {
                        // 🌟 大獎：白色粗體字 + 深灰底 + 綠色加粗外框
                        circleView.setTextColor(Color.WHITE)
                        circleView.background = ContextCompat.getDrawable(context, R.drawable.circle_cell_normal_background)?.mutate()?.apply {
                            if (this is android.graphics.drawable.GradientDrawable) {
                                setColor(darkGray)
                                setStroke(4, colorGreen)
                            }
                        }
                    }
                    else -> {
                        // ⬛ 沒中獎：恢復原始的黑底灰字與細灰框
                        circleView.setTextColor(Color.GRAY)
                        circleView.background = ContextCompat.getDrawable(context, R.drawable.circle_cell_background_black)
                    }
                }
            }
        }
    }
}