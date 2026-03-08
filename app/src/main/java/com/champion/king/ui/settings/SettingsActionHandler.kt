package com.champion.king.ui.settings

import android.app.AlertDialog
import android.content.Context
import android.util.Log
import com.champion.king.SettingsViewModel
import com.champion.king.model.NumberConfiguration
import com.champion.king.model.ScratchCard

class SettingsActionHandler(
    private val viewModel: SettingsViewModel,
    private val context: Context,
    private val switchThreshold: Double = DEFAULT_SWITCH_THRESHOLD,
    private val showToast: (String) -> Unit
) {

    companion object {
        private const val TAG = "SettingsActionHandler"
        private const val DEFAULT_SWITCH_THRESHOLD = 0.5 // 50%
    }

    fun handleToggleInUse(selectedShelfOrder: Int, currentCards: Map<Int, ScratchCard>) {
        val targetCard = currentCards[selectedShelfOrder] ?: run {
            showToast("該版位沒有刮刮卡資料。")
            return
        }

        val newState = !targetCard.inUsed

        if (newState) {
            // 要設為使用中 - 需要檢查當前使用中的版位是否可以切換
            handleSetInUse(targetCard, currentCards)
        } else {
            // 要取消使用中 - 需要檢查該版位是否允許取消
            handleSetNotInUse(targetCard, currentCards)
        }
    }

    private fun handleSetInUse(targetCard: ScratchCard, currentCards: Map<Int, ScratchCard>) {
        val currentInUseCard = currentCards.values.firstOrNull { it.inUsed }

        if (currentInUseCard == null) {
            Log.d(TAG, "沒有其他刮板在使用中，直接設為使用中")
            viewModel.setInUseExclusive(targetCard, currentCards, true)
            return
        }

        Log.d(TAG, "檢測到 ${currentInUseCard.order}號板 正在使用中，開始檢查切換條件")

        val lockAnalysis = analyzeSwitchConditions(currentInUseCard)

        if (lockAnalysis.result == SwitchResult.DENY_OVER_HALF_NO_SPECIAL) {
            Log.d(TAG, "當前使用中的 ${currentInUseCard.order}號板 被鎖定，不允許切換")
            showToast("無法切換：當前使用中的 ${currentInUseCard.order}號板 已刮超過1/2（${lockAnalysis.scratchedCount}/${lockAnalysis.totalCount}）且特獎未完全開出，請先完成該板。")
            return
        }

        checkSwitchConditions(targetCard, currentInUseCard, currentCards)
    }

    private fun handleSetNotInUse(targetCard: ScratchCard, currentCards: Map<Int, ScratchCard>) {
        Log.d(TAG, "檢查 ${targetCard.order}號板 是否可以取消使用中")

        val lockAnalysis = analyzeSwitchConditions(targetCard)

        if (lockAnalysis.result == SwitchResult.DENY_OVER_HALF_NO_SPECIAL) {
            Log.d(TAG, "${targetCard.order}號板 被鎖定，不允許取消使用中")
            showToast("無法取消使用中：${targetCard.order}號板 已刮超過1/2（${lockAnalysis.scratchedCount}/${lockAnalysis.totalCount}）且特獎未完全開出，請先完成該板。")
            return
        }

        checkUnsetConditions(targetCard, currentCards)
    }

    private fun checkSwitchConditions(
        targetCard: ScratchCard,
        currentInUseCard: ScratchCard,
        currentCards: Map<Int, ScratchCard>
    ) {
        // 🌟 規則更新：如果當前使用中的是「分割版面」，直接放行並切換，不跳確認視窗！
        if (!currentInUseCard.splitMode.isNullOrEmpty()) {
            Log.d(TAG, "當前使用中為分割版面 ${currentInUseCard.splitMode}，免確認直接切換到 ${targetCard.order}號板")
            viewModel.setInUseExclusive(targetCard, currentCards, true)
            return
        }

        // --- 以下為單一版面的原有邏輯 (會跳出確認視窗) ---
        val switchAnalysis = analyzeSwitchConditions(currentInUseCard)

        when (switchAnalysis.result) {
            SwitchResult.ALLOW_SPECIAL_PRIZE_OUT -> {
                showSwitchConfirmDialog(
                    "切換確認",
                    "檢測到 ${currentInUseCard.order}號板 的特獎已經刮出，可以切換到 ${targetCard.order}號板。\n\n是否確認切換？",
                    onConfirm = { viewModel.setInUseExclusive(targetCard, currentCards, true) }
                )
            }
            SwitchResult.ALLOW_UNDER_HALF -> {
                showSwitchConfirmDialog(
                    "切換確認",
                    "${currentInUseCard.order}號板 刮取進度未超過1/2（${switchAnalysis.scratchedCount}/${switchAnalysis.totalCount}），可以切換到 ${targetCard.order}號板。\n\n是否確認切換？",
                    onConfirm = { viewModel.setInUseExclusive(targetCard, currentCards, true) }
                )
            }
            SwitchResult.DENY_OVER_HALF_NO_SPECIAL -> {
                showToast("無法切換：${currentInUseCard.order}號板 已刮超過1/2（${switchAnalysis.scratchedCount}/${switchAnalysis.totalCount}）且特獎未出，請先完成該板。")
            }
            SwitchResult.ERROR -> {
                showToast("切換檢查失敗，請重試。")
            }
        }
    }

    private fun checkUnsetConditions(
        targetCard: ScratchCard,
        currentCards: Map<Int, ScratchCard>
    ) {
        // 🌟 規則更新：如果想取消使用的板位是「分割版面」，直接放行並取消，不跳確認視窗！
        if (!targetCard.splitMode.isNullOrEmpty()) {
            Log.d(TAG, "目標為分割版面 ${targetCard.splitMode}，免確認直接取消使用中")
            viewModel.setInUseExclusive(targetCard, currentCards, false)
            return
        }

        // --- 以下為單一版面的原有邏輯 (會跳出確認視窗) ---
        val unsetAnalysis = analyzeSwitchConditions(targetCard)

        when (unsetAnalysis.result) {
            SwitchResult.ALLOW_SPECIAL_PRIZE_OUT -> {
                showUnsetConfirmDialog(
                    "取消使用中確認",
                    "檢測到 ${targetCard.order}號板 的特獎已經刮出，可以取消使用中。\n\n是否確認取消使用中？",
                    onConfirm = { viewModel.setInUseExclusive(targetCard, currentCards, false) }
                )
            }
            SwitchResult.ALLOW_UNDER_HALF -> {
                showUnsetConfirmDialog(
                    "取消使用中確認",
                    "${targetCard.order}號板 刮取進度未超過1/2（${unsetAnalysis.scratchedCount}/${unsetAnalysis.totalCount}），可以取消使用中。\n\n是否確認取消使用中？",
                    onConfirm = { viewModel.setInUseExclusive(targetCard, currentCards, false) }
                )
            }
            SwitchResult.DENY_OVER_HALF_NO_SPECIAL -> {
                showToast("無法取消使用中：${targetCard.order}號板 已刮超過1/2（${unsetAnalysis.scratchedCount}/${unsetAnalysis.totalCount}）且特獎未出，請先完成該板。")
            }
            SwitchResult.ERROR -> {
                showToast("取消使用中檢查失敗，請重試。")
            }
        }
    }

    // 🌟 核心修改：完整支援分割版面的切換防呆分析邏輯 (包含豁免機制)
    private fun analyzeSwitchConditions(card: ScratchCard): SwitchAnalysis {
        try {
            // 🌟 規則更新：如果是分割版面，直接無條件放行 (不受 1/2 與特獎未開限制)
            if (!card.splitMode.isNullOrEmpty()) {
                Log.d(TAG, "檢測到分割版面 ${card.splitMode}，豁免切換防呆規則，直接放行")
                // 為了符合現有架構，我們回傳 ALLOW_UNDER_HALF，這樣它就會跳出「確認切換」的對話框讓台主確認
                return SwitchAnalysis(SwitchResult.ALLOW_UNDER_HALF, 0, 100)
            }

            // --- 單一版面原有邏輯 ---
            val specialPrizeNumber = card.specialPrize?.toIntOrNull()
            if (specialPrizeNumber == null) return SwitchAnalysis(SwitchResult.ERROR, 0, 0)

            val configurations = card.numberConfigurations
            if (configurations.isNullOrEmpty()) return SwitchAnalysis(SwitchResult.ERROR, 0, 0)

            val specialPrizeConfig = configurations.find { it.number == specialPrizeNumber }
            if (specialPrizeConfig == null) return SwitchAnalysis(SwitchResult.ERROR, 0, 0)

            if (specialPrizeConfig.scratched) {
                return SwitchAnalysis(SwitchResult.ALLOW_SPECIAL_PRIZE_OUT, 0, configurations.size)
            }

            val totalCount = configurations.size
            val scratchedCount = configurations.count { it.scratched }
            val scratchedRatio = scratchedCount.toDouble() / totalCount.toDouble()

            return if (scratchedRatio < switchThreshold) {
                SwitchAnalysis(SwitchResult.ALLOW_UNDER_HALF, scratchedCount, totalCount)
            } else {
                SwitchAnalysis(SwitchResult.DENY_OVER_HALF_NO_SPECIAL, scratchedCount, totalCount)
            }

        } catch (e: Exception) {
            Log.e(TAG, "分析切換條件時發生異常", e)
            return SwitchAnalysis(SwitchResult.ERROR, 0, 0)
        }
    }

    private fun showSwitchConfirmDialog(title: String, message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("確認切換") { _, _ -> onConfirm() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showUnsetConfirmDialog(title: String, message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("確認取消使用中") { _, _ -> onConfirm() }
            .setNegativeButton("保持使用中", null)
            .show()
    }

    // 原有的其他方法保持不變...
    fun handleReturn(selectedShelfOrder: Int, currentCards: Map<Int, ScratchCard>) {
        val card = currentCards[selectedShelfOrder] ?: run {
            showToast("該版位沒有資料。")
            return
        }

        if (card.inUsed) {
            showToast("使用中的版位不可返回。")
            return
        }

        // 檢查是否已被刮過
        if (hasBeenScratched(card)) {
            showToast("此刮板已刮過，無法返回")
            return
        }

        // 未被刮過，執行返回
        showReturnConfirmationDialog { viewModel.returnCard(selectedShelfOrder, card) }
    }

    // 🌟 核心修改：支援判斷分割版面是否已經被刮過
    private fun hasBeenScratched(card: ScratchCard): Boolean {
        if (!card.splitMode.isNullOrEmpty()) {
            val boardsMap = try { card.javaClass.getMethod("getBoards").invoke(card) as? Map<*, *> } catch (e: Exception) { null }
                ?: try {
                    val field = card.javaClass.getDeclaredField("boards")
                    field.isAccessible = true
                    field.get(card) as? Map<*, *>
                } catch (e: Exception) { null }

            if (boardsMap != null) {
                for ((_, rawBoard) in boardsMap) {
                    var configsList: List<*>? = null
                    if (rawBoard is Map<*, *>) {
                        configsList = rawBoard["numberConfigurations"] as? List<*>
                    } else if (rawBoard is com.champion.king.model.Board) {
                        configsList = rawBoard.numberConfigurations
                    }

                    if (configsList != null) {
                        for (item in configsList) {
                            val scratched = if (item is Map<*, *>) item["scratched"] as? Boolean ?: false
                            else if (item is NumberConfiguration) item.scratched else false
                            if (scratched) return true
                        }
                    }
                }
            }
            return false
        }

        val configurations = card.numberConfigurations
        if (configurations.isNullOrEmpty()) {
            Log.w(TAG, "刮板沒有數字配置，視為未刮過")
            return false
        }

        val scratchedCount = configurations.count { it.scratched }
        Log.d(TAG, "刮板${card.order}已刮數量: $scratchedCount")

        return scratchedCount >= 1
    }

    fun handleDelete(selectedShelfOrder: Int, currentCards: Map<Int, ScratchCard>) {
        val card = currentCards[selectedShelfOrder] ?: run {
            showToast("該版位沒有資料。")
            return
        }

        if (card.inUsed) {
            showToast("使用中的版位不可刪除。")
            return
        }

        val serialNumber = card.serialNumber ?: run {
            showToast("找不到序號。")
            return
        }

        // 檢查刪除條件並顯示對應的確認視窗
        checkDeleteConditions(card, serialNumber)
    }

    private fun checkDeleteConditions(card: ScratchCard, serialNumber: String) {
        // 8-1: 檢查是否未被刮過
        if (!hasBeenScratched(card)) {
            showDeleteConfirmDialog(
                "刪除確認",
                "此刮板未被刮過，是否確認刪除？",
                onConfirm = { viewModel.deleteCard(serialNumber) }
            )
            return
        }

        // 8-2: 已被刮過，需要進一步檢查
        val deleteAnalysis = analyzeDeleteConditions(card)

        when (deleteAnalysis.result) {
            DeleteResult.ALLOW_SPECIAL_PRIZE_OUT -> {
                // 8-2-1: 特獎已出，允許刪除但需確認
                Log.d(TAG, "特獎已出，允許刪除但需確認")
                showDeleteConfirmDialog(
                    "刪除確認",
                    "此刮板刪除後無法復原，確認是否刪除？",
                    onConfirm = { viewModel.deleteCard(serialNumber) }
                )
            }

            DeleteResult.ALLOW_UNDER_HALF_NO_SPECIAL -> {
                // 8-2-2-2: 特獎未出但未超過1/2，允許刪除但需確認
                Log.d(TAG, "特獎未出但刮取未超過1/2，允許刪除但需確認")
                showDeleteConfirmDialog(
                    "刪除確認",
                    "此刮板刪除後無法復原，確認是否刪除？",
                    onConfirm = { viewModel.deleteCard(serialNumber) }
                )
            }

            DeleteResult.DENY_OVER_HALF_NO_SPECIAL -> {
                // 8-2-2-1: 特獎未出且超過1/2，拒絕刪除
                Log.d(TAG, "特獎未出且刮取超過1/2，拒絕刪除")
                showToast("此刮板已刮超過1/2，無法刪除")
            }

            DeleteResult.ERROR -> {
                Log.e(TAG, "分析刪除條件時發生錯誤")
                showToast("刪除檢查失敗，請重試。")
            }
        }
    }

    // 🌟 核心修改：同上，支援判斷分割版面的刪除條件 (包含豁免機制)
    private fun analyzeDeleteConditions(card: ScratchCard): DeleteAnalysis {
        try {
            // 🌟 規則更新：如果是分割版面，直接無條件放行 (不受 1/2 與特獎未開限制)
            if (!card.splitMode.isNullOrEmpty()) {
                Log.d(TAG, "檢測到分割版面 ${card.splitMode}，豁免刪除防呆規則，直接放行")
                // 回傳 ALLOW_UNDER_HALF_NO_SPECIAL 會觸發標準的刪除確認視窗
                return DeleteAnalysis(DeleteResult.ALLOW_UNDER_HALF_NO_SPECIAL, 0, 100)
            }

            // --- 單一版面原有邏輯 ---
            val specialPrizeNumber = card.specialPrize?.toIntOrNull()
            if (specialPrizeNumber == null) return DeleteAnalysis(DeleteResult.ERROR, 0, 0)

            val configurations = card.numberConfigurations
            if (configurations.isNullOrEmpty()) return DeleteAnalysis(DeleteResult.ERROR, 0, 0)

            val specialPrizeConfig = configurations.find { it.number == specialPrizeNumber }
            if (specialPrizeConfig == null) return DeleteAnalysis(DeleteResult.ERROR, 0, 0)

            if (specialPrizeConfig.scratched) {
                return DeleteAnalysis(DeleteResult.ALLOW_SPECIAL_PRIZE_OUT, 0, configurations.size)
            }

            val totalCount = configurations.size
            val scratchedCount = configurations.count { it.scratched }
            val scratchedRatio = scratchedCount.toDouble() / totalCount.toDouble()

            return if (scratchedRatio < switchThreshold) {
                DeleteAnalysis(DeleteResult.ALLOW_UNDER_HALF_NO_SPECIAL, scratchedCount, totalCount)
            } else {
                DeleteAnalysis(DeleteResult.DENY_OVER_HALF_NO_SPECIAL, scratchedCount, totalCount)
            }

        } catch (e: Exception) {
            Log.e(TAG, "分析刪除條件時發生異常", e)
            return DeleteAnalysis(DeleteResult.ERROR, 0, 0)
        }
    }

    private fun showDeleteConfirmDialog(title: String, message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("是") { _, _ -> onConfirm() }
            .setNegativeButton("否", null)
            .show()
    }

    fun handleSaveSettings(
        selectedShelfOrder: Int,
        selectedScratchType: String,
        specialPrize: String?,
        grandPrize: String?,
        claws: Int?,
        giveaway: Int?,
        numberConfigurations: List<NumberConfiguration>?,
        currentCards: Map<Int, ScratchCard>
    ) {
        if (!validateSaveSettings(specialPrize, numberConfigurations)) return

        val scratchesTypeInt = parseScratchesType(selectedScratchType).let { it.first * it.second }
        val existing = currentCards[selectedShelfOrder]

        viewModel.upsertCard(
            order = selectedShelfOrder,
            scratchesType = scratchesTypeInt,
            specialPrize = specialPrize!!,
            grandPrize = grandPrize,
            clawsCount = claws,
            giveawayCount = giveaway,
            numberConfigurations = numberConfigurations!!,
            existingSerial = existing?.serialNumber,
            keepInUsed = existing?.inUsed == true
        )
    }

    private fun validateSaveSettings(
        specialPrize: String?,
        numberConfigurations: List<NumberConfiguration>?
    ): Boolean {
        if (specialPrize.isNullOrEmpty()) {
            showToast("特獎未設定，無法儲存！")
            return false
        }

        if (numberConfigurations.isNullOrEmpty()) {
            showToast("刮板數字配置尚未生成，無法儲存！")
            return false
        }

        return true
    }

    private fun parseScratchesType(type: String): Pair<Int, Int> {
        val regex = Regex("\\((\\d+)x(\\d+)\\)")
        val match = regex.find(type) ?: return 5 to 5
        val (rows, cols) = match.destructured
        return rows.toInt() to cols.toInt()
    }

    private fun showReturnConfirmationDialog(onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle("確認返回背包")
            .setMessage("是否將刮板返回背包？")
            .setPositiveButton("是") { _, _ -> onConfirm() }
            .setNegativeButton("否", null)
            .show()
    }

    private fun showToast(message: String) = showToast.invoke(message)

    // 內部數據類別
    private enum class SwitchResult {
        ALLOW_SPECIAL_PRIZE_OUT,    // 允許切換：特獎已出
        ALLOW_UNDER_HALF,           // 允許切換：刮取未超過1/2
        DENY_OVER_HALF_NO_SPECIAL,  // 拒絕切換：超過1/2且特獎未出
        ERROR                       // 錯誤
    }

    private data class SwitchAnalysis(
        val result: SwitchResult,
        val scratchedCount: Int,
        val totalCount: Int
    )

    // 刪除相關的枚舉和數據類別
    private enum class DeleteResult {
        ALLOW_SPECIAL_PRIZE_OUT,      // 8-2-1: 特獎已出，允許刪除
        ALLOW_UNDER_HALF_NO_SPECIAL,  // 8-2-2-2: 特獎未出但未超過1/2，允許刪除
        DENY_OVER_HALF_NO_SPECIAL,    // 8-2-2-1: 特獎未出且超過1/2，拒絕刪除
        ERROR                         // 錯誤
    }

    private data class DeleteAnalysis(
        val result: DeleteResult,
        val scratchedCount: Int,
        val totalCount: Int
    )
}