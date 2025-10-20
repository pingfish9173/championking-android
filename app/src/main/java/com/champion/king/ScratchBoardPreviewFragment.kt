package com.champion.king

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import com.champion.king.model.NumberConfiguration
import kotlin.random.Random
import android.widget.GridLayout

class ScratchBoardPreviewFragment : Fragment() {

    companion object {
        private const val ARG_SCRATCHES_TYPE = "scratches_type"
        private const val ARG_NUMBER_CONFIGURATIONS = "number_configurations"
        private const val ARG_ENABLE_SINGLE_PICK = "enable_single_pick"
        private const val ARG_ENABLE_MULTI_PICK = "enable_multi_pick"
        private const val ARG_GRAND_SELECTED = "arg_grand_selected"
        private const val ARG_READONLY_MODE = "arg_readonly_mode"

        private const val STATE_CONFIGS = "state_configs"
        private const val STATE_PICK_MODE = "state_pick_mode"
        private const val STATE_MULTI_MODE = "state_multi_mode"
        private const val STATE_LAST_PICKED = "state_last_picked"
        private const val STATE_GRAND_SET = "state_grand_set"

        private const val TAG = "ScratchBoardPreview"

        fun newInstance(scratchesType: String): ScratchBoardPreviewFragment {
            return ScratchBoardPreviewFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SCRATCHES_TYPE, scratchesType)
                }
            }
        }

        fun newInstance(
            scratchesType: String,
            numberConfigurations: List<NumberConfiguration>,
            readonlyMode: Boolean = false
        ): ScratchBoardPreviewFragment {
            return ScratchBoardPreviewFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SCRATCHES_TYPE, scratchesType)
                    putParcelableArrayList(
                        ARG_NUMBER_CONFIGURATIONS,
                        ArrayList(numberConfigurations)
                    )
                    putBoolean(ARG_READONLY_MODE, readonlyMode)
                }
            }
        }
    }

    private var scratchesType: String? = null
    private lateinit var grid: GridLayout
    private var hintView: TextView? = null

    /** 目前使用中的配置（可能來自 arguments、savedInstanceState 或新隨機生成） */
    private var generatedNumberConfigurations: ArrayList<NumberConfiguration>? = null

    /** 特獎：單選挑選模式 */
    private var singlePickEnabled: Boolean = false

    /** 大獎：多選挑選模式 */
    private var multiPickEnabled: Boolean = false

    /** 唯讀模式：使用中的刮板不允許修改特獎/大獎 */
    private var readonlyMode: Boolean = false

    /** 特獎：最後一次選中的數字（保留金色標記） */
    private var lastPickedNumber: Int? = null

    /** 大獎：已選數字集合（保留綠色標記） */
    private val grandSelectedNumbers = linkedSetOf<Int>()

    // 儲存格子視圖的參考，用於更新顯示狀態
    private val cellViews = mutableMapOf<Int, View>()
    private val textViews = mutableMapOf<Int, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 復原狀態
        generatedNumberConfigurations = savedInstanceState?.getParcelableArrayList(STATE_CONFIGS)
        singlePickEnabled = savedInstanceState?.getBoolean(STATE_PICK_MODE) ?: false
        multiPickEnabled = savedInstanceState?.getBoolean(STATE_MULTI_MODE) ?: false
        lastPickedNumber = savedInstanceState?.getInt(STATE_LAST_PICKED)?.takeIf { it > 0 }
        savedInstanceState?.getIntArray(STATE_GRAND_SET)?.let { arr ->
            grandSelectedNumbers.clear()
            grandSelectedNumbers.addAll(arr.toList())
        }

        // 讀取 arguments
        arguments?.let { args ->
            scratchesType = args.getString(ARG_SCRATCHES_TYPE)
            readonlyMode = args.getBoolean(ARG_READONLY_MODE, false)

            if (generatedNumberConfigurations == null) {
                val argConfigs: ArrayList<NumberConfiguration>? =
                    args.getParcelableArrayList(ARG_NUMBER_CONFIGURATIONS)
                if (!argConfigs.isNullOrEmpty()) {
                    generatedNumberConfigurations = argConfigs
                }
            }

            if (!singlePickEnabled) singlePickEnabled =
                args.getBoolean(ARG_ENABLE_SINGLE_PICK, false)
            if (!multiPickEnabled) multiPickEnabled = args.getBoolean(ARG_ENABLE_MULTI_PICK, false)

            // 若外部（SettingsFragment）有傳入初始大獎集合
            val initGrand = args.getIntArray(ARG_GRAND_SELECTED)
            if (initGrand != null && initGrand.isNotEmpty()) {
                grandSelectedNumbers.clear()
                grandSelectedNumbers.addAll(initGrand.toList())
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_scratch_board_preview, container, false)
        grid = root.findViewById(R.id.scratch_board_grid_layout)
        hintView = root.findViewById(R.id.single_pick_hint)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val type = scratchesType ?: "25刮 (5x5)"

        // 根據刮數類型載入對應的設置介面專用布局
        val scratchCount = extractScratchCount(type)
        if (scratchCount > 0) {
            setupPreviewWithXml(scratchCount)
        } else {
            // 降級：使用原本的動態生成方式
            setupGridLayout(type)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        generatedNumberConfigurations?.let { outState.putParcelableArrayList(STATE_CONFIGS, it) }
        outState.putBoolean(STATE_PICK_MODE, singlePickEnabled)
        outState.putBoolean(STATE_MULTI_MODE, multiPickEnabled)
        lastPickedNumber?.let { outState.putInt(STATE_LAST_PICKED, it) }
        if (grandSelectedNumbers.isNotEmpty()) {
            outState.putIntArray(STATE_GRAND_SET, grandSelectedNumbers.toIntArray())
        }
    }

    // 從類型字串中提取刮數
    private fun extractScratchCount(type: String): Int {
        return try {
            val regex = Regex("(\\d+)刮")
            val match = regex.find(type)
            match?.groupValues?.get(1)?.toInt() ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "無法從類型中提取刮數: $type", e)
            0
        }
    }

    // 根據刮數返回對應的設置介面專用布局資源ID
    private fun getSettingsPreviewLayoutResource(scratchCount: Int): Int {
        val resourceName = "settings_preview_$scratchCount"
        return try {
            resources.getIdentifier(resourceName, "layout", requireContext().packageName)
        } catch (e: Exception) {
            Log.e(TAG, "找不到資源: $resourceName", e)
            0
        }
    }

    private fun setupPreviewWithXml(scratchCount: Int) {
        try {
            val layoutResId = getSettingsPreviewLayoutResource(scratchCount)

            if (layoutResId != 0) {
                grid.removeAllViews()
                val configs = getOrGenerateConfigs(scratchCount)

                val view = LayoutInflater.from(requireContext())
                    .inflate(layoutResId, grid, false)

                grid.addView(view)

                // 設置每個格子
                setupCellsFromXml(view, configs)
                generatedNumberConfigurations = ArrayList(configs)

                // 初始套用模式與標記
                applyModesAndMarkers()

                Log.d(TAG, "成功載入${scratchCount}刮版型預覽(XML版本)")
            } else {
                Log.w(TAG, "找不到 settings_preview_${scratchCount}.xml,使用動態生成")
                fallbackToDynamicLayout(scratchCount)
            }
        } catch (e: Exception) {
            Log.e(TAG, "載入XML佈局失敗: ${e.message}", e)
            fallbackToDynamicLayout(scratchCount)
        }
    }

    private fun setupCellsFromXml(containerView: View, configs: List<NumberConfiguration>) {
        cellViews.clear()
        textViews.clear()  // ← 新增

        val gridLayout = containerView.findViewById<ViewGroup>(R.id.gridLayout)
        if (gridLayout == null) {
            Log.e(TAG, "找不到 GridLayout")
            return
        }

        var cellNumber = 1

        for (i in 0 until gridLayout.childCount) {
            if (cellNumber > configs.size) break

            val outerFrame = gridLayout.getChildAt(i) as? FrameLayout ?: continue
            val innerFrame = if (outerFrame.childCount > 0) {
                outerFrame.getChildAt(0) as? FrameLayout
            } else null

            if (innerFrame != null && innerFrame.childCount >= 2) {
                // settings_preview 結構
                val cellView = innerFrame.getChildAt(0)
                val textView = innerFrame.getChildAt(1) as? TextView

                cellViews[cellNumber] = cellView
                if (textView != null) {
                    textViews[cellNumber] = textView  // ← 保存引用
                }
                setupCellForSettings(cellView, textView, cellNumber, configs)
            } else {
                // scratch_card 結構
                val cellView = if (outerFrame.childCount > 0) {
                    outerFrame.getChildAt(0)
                } else continue

                val textView = cellView as? TextView
                cellViews[cellNumber] = cellView
                if (textView != null) {
                    textViews[cellNumber] = textView  // ← 保存引用
                }
                setupCellForSettings(cellView, textView, cellNumber, configs)
            }

            cellNumber++
        }
    }

    private fun setupCellForSettings(cellView: View, textView: TextView?, cellNumber: Int, configs: List<NumberConfiguration>) {
        // 根據資料庫結構，使用 numberConfigurations 數組
        val numberConfig = configs.find { it.id == cellNumber }
        val isScratched = numberConfig?.scratched == true
        val number = numberConfig?.number

        updateCellDisplay(cellView, textView, isScratched, number)

        // 根據模式設定點擊事件（點擊整個FrameLayout）
        val clickTarget = cellView.parent as? View ?: cellView

        if (readonlyMode) {
            // 唯讀模式：完全不可點擊
            clickTarget.isClickable = false
            clickTarget.isFocusable = false
        } else {
            // 設置點擊事件
            clickTarget.isClickable = singlePickEnabled || multiPickEnabled
            clickTarget.isFocusable = clickTarget.isClickable

            if (clickTarget.isClickable) {
                clickTarget.setOnClickListener {
                    handleCellClick(number)
                }
            }
        }
    }

    private fun handleCellClick(number: Int?) {
        if (number == null) return

        if (singlePickEnabled) {
            if (grandSelectedNumbers.contains(number)) {
                toast("此數字已在大獎清單，請先取消大獎再選為特獎")
                return
            }
            setFragmentResult(
                "scratch_number_selected",
                Bundle().apply { putInt("number", number) })
            lastPickedNumber = number
            applyModesAndMarkers()
        } else if (multiPickEnabled) {
            if (number == lastPickedNumber) {
                toast("此數字已是特獎，無法加入大獎清單")
                return
            }
            if (grandSelectedNumbers.contains(number)) {
                grandSelectedNumbers.remove(number)
            } else {
                grandSelectedNumbers.add(number)
            }
            setFragmentResult("grand_numbers_changed", Bundle().apply {
                putIntArray("numbers", grandSelectedNumbers.toIntArray())
            })
            applyModesAndMarkers()
        }
    }

    // 顯示更新方法 - 設置介面專用（支援XML中的TextView）
    private fun updateCellDisplay(cellView: View, textView: TextView?, isScratched: Boolean, number: Int?) {
        if (number != null) {
            val isSpecial = isSpecialPrize(number)
            val isGrand = isGrandPrize(number)

            val backgroundColor = if (isScratched) {
                ContextCompat.getColor(cellView.context, R.color.scratch_card_white)
            } else {
                ContextCompat.getColor(cellView.context, R.color.scratch_card_dark_gray)
            }

            val strokeColor = when {
                isSpecial -> ContextCompat.getColor(cellView.context, R.color.scratch_card_gold)
                isGrand -> ContextCompat.getColor(cellView.context, R.color.scratch_card_green)
                else -> ContextCompat.getColor(cellView.context, R.color.scratch_card_light_gray)
            }
            val strokeWidth = if (isSpecial || isGrand) 5 else 2

            val background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(strokeWidth, strokeColor)
                setColor(backgroundColor)
            }
            cellView.background = background

            val textColor = if (isScratched) {
                ContextCompat.getColor(cellView.context, R.color.scratch_card_medium_gray)
            } else {
                ContextCompat.getColor(cellView.context, R.color.scratch_card_light_gray)
            }

            // 優先使用 XML 中已有的 TextView
            if (textView != null) {
                textView.text = number.toString()
                textView.setTextColor(textColor)
                textView.visibility = View.VISIBLE
            } else if (cellView is TextView) {
                cellView.text = number.toString()
                cellView.setTextColor(textColor)
            } else {
                addNumberToCell(cellView, number, textColor)
            }
        } else {
            val background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(2, ContextCompat.getColor(cellView.context, R.color.scratch_card_light_gray))
                setColor(ContextCompat.getColor(cellView.context, R.color.scratch_card_dark_gray))
            }
            cellView.background = background

            if (textView != null) {
                textView.text = ""
                textView.visibility = View.GONE
            } else if (cellView is TextView) {
                cellView.text = ""
            } else {
                removeNumberFromCell(cellView)
            }
        }
    }

    private fun addNumberToCell(cellView: View, number: Int, textColor: Int) {
        // 如果cellView本身就在FrameLayout中
        val parentView = cellView.parent
        if (parentView is FrameLayout) {
            var numberTextView = parentView.findViewWithTag<TextView>("number_text")

            if (numberTextView == null) {
                numberTextView = TextView(requireContext()).apply {
                    tag = "number_text"
                    text = number.toString()
                    textSize = 24f // 調整為更大的字體，配合52dp的圓圈
                    setTextColor(textColor)
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                    setTypeface(null, Typeface.BOLD)
                }
                parentView.addView(numberTextView)
            } else {
                numberTextView.text = number.toString()
                numberTextView.setTextColor(textColor)
                numberTextView.textSize = 24f // 統一字體大小
                numberTextView.visibility = View.VISIBLE
            }
        }
    }

    private fun removeNumberFromCell(cellView: View) {
        val parentView = cellView.parent
        if (parentView is FrameLayout) {
            val numberTextView = parentView.findViewWithTag<TextView>("number_text")
            numberTextView?.visibility = View.GONE
        }
    }

    private fun isSpecialPrize(number: Int): Boolean {
        return number == lastPickedNumber
    }

    private fun isGrandPrize(number: Int): Boolean {
        return grandSelectedNumbers.contains(number)
    }

    // 降級到動態布局
    private fun fallbackToDynamicLayout(scratchCount: Int) {
        val dims = getRowsColsFromTotal(scratchCount)
        setupGridLayout("${scratchCount}刮 (${dims.first}x${dims.second})")
    }

    // 設置格子佈局（原有的動態生成方式）
    private fun setupGridLayout(type: String) {
        val (rows, cols) = parseScratchesType(type)
        val total = rows * cols
        val configs = getOrGenerateConfigs(total)

        createAndPopulateGrid(rows, cols, configs)
        generatedNumberConfigurations = ArrayList(configs)

        // 初始套用模式與標記
        applyModesAndMarkers()
    }

    private fun getOrGenerateConfigs(total: Int): List<NumberConfiguration> {
        return when {
            !generatedNumberConfigurations.isNullOrEmpty() &&
                    validateConfigs(generatedNumberConfigurations!!, total) -> {
                Log.d(TAG, "使用既有配置。")
                generatedNumberConfigurations!!
            }
            else -> {
                Log.d(TAG, "生成新的隨機數字配置。")
                generateConfigs(total)
            }
        }
    }

    private fun showErrorMessage(message: String) {
        // 顯示錯誤訊息
        grid.removeAllViews()
        val tv = TextView(requireContext()).apply {
            text = message
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
        }
        grid.addView(tv)
    }

    // 工具方法：顯示提示訊息
    private fun toast(msg: String) {
        context?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }
    }

    // ====== 公開方法供 SettingsFragment 調用 ======

    fun setSinglePickEnabled(enabled: Boolean) {
        if (readonlyMode) return // 唯讀模式不允許變更
        if (singlePickEnabled == enabled) return
        singlePickEnabled = enabled
        if (enabled) multiPickEnabled = false // 互斥
        applyModesAndMarkers()
    }

    fun setMultiPickEnabled(enabled: Boolean) {
        if (readonlyMode) return // 唯讀模式不允許變更
        if (multiPickEnabled == enabled) return
        multiPickEnabled = enabled
        if (enabled) singlePickEnabled = false // 互斥
        applyModesAndMarkers()
    }

    fun setSelectedNumber(number: Int?) {
        if (readonlyMode) return // 唯讀模式不允許變更
        lastPickedNumber = number?.takeIf { it > 0 }
        applyModesAndMarkers()
    }

    fun setGrandSelectedNumbers(numbers: Collection<Int>?) {
        if (readonlyMode) return // 唯讀模式不允許變更
        grandSelectedNumbers.clear()
        if (numbers != null) {
            grandSelectedNumbers.addAll(numbers.filter { it > 0 })
        }
        applyModesAndMarkers()
    }

    fun getGrandSelectedNumbers(): List<Int> = grandSelectedNumbers.toList()
    fun getGeneratedNumberConfigurations(): List<NumberConfiguration>? =
        generatedNumberConfigurations
    fun getScratchesType(): String? = scratchesType

    // ====== 輔助方法 ======

    private fun applyModesAndMarkers() {
        // 提示文字
        when {
            readonlyMode -> hintView?.apply {
                visibility = View.VISIBLE
                text = "此刮板為使用中狀態，不可修改特獎/大獎設定"
            }
            singlePickEnabled -> hintView?.apply {
                visibility = View.VISIBLE
                text = "請點選一個數字作為「特獎」"
            }
            multiPickEnabled -> hintView?.apply {
                visibility = View.VISIBLE
                text = "請點選一個或多個數字作為「大獎」（再點可取消）"
            }
            else -> hintView?.visibility = View.GONE
        }

        // 更新所有格子的顯示
        if (cellViews.isNotEmpty()) {
            updateXmlCells()
        } else {
            updateGridCells()
        }
    }

    private fun updateXmlCells() {
        cellViews.forEach { (cellNumber, cellView) ->
            val numberConfig = generatedNumberConfigurations?.find { it.id == cellNumber }
            val isScratched = numberConfig?.scratched == true
            val number = numberConfig?.number

            // ★ 直接從 map 中取得 TextView
            val textView = textViews[cellNumber]

            updateCellDisplay(cellView, textView, isScratched, number)

            // 重新設定點擊事件
            val clickTarget = cellView.parent as? View ?: cellView

            if (!readonlyMode && (singlePickEnabled || multiPickEnabled)) {
                clickTarget.isClickable = true
                clickTarget.isFocusable = true
                clickTarget.setOnClickListener { handleCellClick(number) }
            } else {
                clickTarget.isClickable = false
                clickTarget.isFocusable = false
                clickTarget.setOnClickListener(null)
            }
        }
    }

    private fun updateGridCells() {
        // 由 number -> config 快取，便於查 scratched
        val cfgByNumber = (generatedNumberConfigurations ?: emptyList()).associateBy { it.number }

        grid.children.forEach { view ->
            val tv = view as TextView
            val n = (tv.tag as? Int) ?: tv.text.toString().toIntOrNull()
            val scratched = n?.let { cfgByNumber[it]?.scratched } == true
            val isSpecial = n != null && n == lastPickedNumber
            val isGrand = n != null && grandSelectedNumbers.contains(n)

            tv.setOnClickListener(null)

            if (readonlyMode) {
                // 唯讀模式：不可點擊，顯示當前狀態
                tv.isClickable = false
                tv.isFocusable = false
                tv.background = when {
                    isSpecial -> buildCellBackgroundSelectedGold(scratched)
                    isGrand -> buildCellBackgroundSelectedGreen(scratched)
                    else -> buildCellBackgroundNormal(scratched)
                }
            } else {
                tv.isClickable = singlePickEnabled || multiPickEnabled
                tv.isFocusable = tv.isClickable

                if (singlePickEnabled) {
                    tv.background = when {
                        isSpecial -> buildCellBackgroundSelectedGold(scratched)
                        isGrand -> buildCellBackgroundSelectedGreen(scratched)
                        else -> buildCellBackgroundPickable(scratched)
                    }
                    tv.setOnClickListener {
                        val picked = n ?: return@setOnClickListener
                        if (grandSelectedNumbers.contains(picked)) {
                            toast("此數字已在大獎清單，請先取消大獎再選為特獎")
                            return@setOnClickListener
                        }
                        setFragmentResult(
                            "scratch_number_selected",
                            Bundle().apply { putInt("number", picked) })
                        lastPickedNumber = picked
                        applyModesAndMarkers()
                    }
                } else if (multiPickEnabled) {
                    tv.background = when {
                        isSpecial -> buildCellBackgroundSelectedGold(scratched)
                        isGrand -> buildCellBackgroundSelectedGreen(scratched)
                        else -> buildCellBackgroundPickable(scratched)
                    }
                    tv.setOnClickListener {
                        val picked = n ?: return@setOnClickListener
                        if (picked == lastPickedNumber) {
                            toast("此數字已是特獎，無法加入大獎清單")
                            return@setOnClickListener
                        }
                        if (grandSelectedNumbers.contains(picked)) {
                            grandSelectedNumbers.remove(picked)
                        } else {
                            grandSelectedNumbers.add(picked)
                        }
                        setFragmentResult("grand_numbers_changed", Bundle().apply {
                            putIntArray("numbers", grandSelectedNumbers.toIntArray())
                        })
                        tv.background = if (grandSelectedNumbers.contains(picked))
                            buildCellBackgroundSelectedGreen(scratched)
                        else buildCellBackgroundPickable(scratched)
                    }
                } else {
                    tv.background = when {
                        isSpecial -> buildCellBackgroundSelectedGold(scratched)
                        isGrand -> buildCellBackgroundSelectedGreen(scratched)
                        else -> buildCellBackgroundNormal(scratched)
                    }
                }
            }
        }
    }

    // ====== 原有的輔助方法保持不變 ======

    private fun parseScratchesType(type: String): Pair<Int, Int> {
        val regex = Regex("\\((\\d+)x(\\d+)\\)")
        val match = regex.find(type)
        return if (match != null) {
            val (r, c) = match.destructured
            Pair(r.toInt(), c.toInt())
        } else {
            Log.e(TAG, "無法解析刮數類型: $type，預設為 5x5")
            Pair(5, 5)
        }
    }

    private fun generateConfigs(total: Int): List<NumberConfiguration> {
        val shuffled = (1..total).shuffled(Random)
        return shuffled.mapIndexed { idx, n ->
            NumberConfiguration(id = idx + 1, number = n, scratched = false)
        }
    }

    private fun validateConfigs(cfgs: List<NumberConfiguration>, total: Int): Boolean {
        if (cfgs.size != total) return false
        val nums = cfgs.map { it.number }
        if (nums.any { it !in 1..total }) return false
        if (nums.toSet().size != total) return false
        return true
    }

    private fun createAndPopulateGrid(
        rows: Int,
        cols: Int,
        configurations: List<NumberConfiguration>
    ) {
        val ctx: Context = context ?: return

        grid.removeAllViews()
        grid.rowCount = rows
        grid.columnCount = cols

        val totalCells = rows * cols
        for (i in 0 until totalCells) {
            val cfg = configurations[i]

            val tv = TextView(ctx).apply {
                text = cfg.number.toString()
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                textSize = 16f
                background = buildCellBackgroundNormal(cfg.scratched)
                setTextColor(0xFF000000.toInt())
                contentDescription = "數字 ${cfg.number}"
                tag = cfg.number

                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    val m = 2
                    setMargins(m, m, m, m)
                }
            }

            grid.addView(tv)
        }
    }

    // 根據總數推算行列數
    private fun getRowsColsFromTotal(total: Int): Pair<Int, Int> {
        return when (total) {
            10 -> Pair(2, 5)
            20 -> Pair(4, 5)
            25 -> Pair(5, 5)
            30 -> Pair(5, 6)
            40 -> Pair(5, 8)
            50 -> Pair(5, 10)
            60 -> Pair(6, 10)
            80 -> Pair(8, 10)
            100 -> Pair(10, 10)
            120 -> Pair(10, 12)
            160 -> Pair(10, 16)
            200 -> Pair(10, 20)
            240 -> Pair(12, 20)
            else -> {
                // 嘗試計算最接近正方形的配置
                val sqrt = kotlin.math.sqrt(total.toFloat()).toInt()
                if (sqrt * sqrt == total) {
                    Pair(sqrt, sqrt)
                } else {
                    Pair(sqrt, (total + sqrt - 1) / sqrt)
                }
            }
        }
    }

    // ====== 背景樣式方法保持不變 ======

    private fun buildCellBackgroundBase(
        scratched: Boolean,
        strokeWidth: Int,
        strokeColor: Int
    ): GradientDrawable {
        val fill = if (scratched) 0xFFFFFFFF.toInt() else 0xFFE0E0E0.toInt()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f
            setStroke(strokeWidth, strokeColor)
            setColor(fill)
        }
    }

    private fun buildCellBackgroundNormal(scratched: Boolean): GradientDrawable =
        buildCellBackgroundBase(scratched, 2, 0xFFBDBDBD.toInt())

    private fun buildCellBackgroundPickable(scratched: Boolean): GradientDrawable =
        buildCellBackgroundBase(scratched, 3, 0xFF9E9E9E.toInt())

    private fun buildCellBackgroundSelectedGold(scratched: Boolean): GradientDrawable =
        buildCellBackgroundBase(scratched, 5, 0xFFFFC107.toInt())

    private fun buildCellBackgroundSelectedGreen(scratched: Boolean): GradientDrawable =
        buildCellBackgroundBase(scratched, 5, 0xFF43A047.toInt())
}