package com.champion.king

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.champion.king.data.FirebaseRepository
import com.champion.king.model.NumberConfiguration
import com.champion.king.model.ScratchCard
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repo: FirebaseRepository,
    private val userKey: String
) : ViewModel() {

    // 6 版位資料（order -> ScratchCard）
    val cards: StateFlow<Map<Int, ScratchCard>> =
        repo.listenScratchCardsFlow(userKey)
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    // UI 事件（Toast等）
    private val _events = MutableSharedFlow<UiEvent>()
    val events = _events.asSharedFlow()

    sealed class UiEvent {
        data class Toast(val message: String) : UiEvent()
    }

    // ==============================
    // ✅ 設置頁「草稿」：每個板位各自保存（全域 DraftStore，登出可直接清除）
    // ==============================
    data class SettingsDraft(
        val scratchType: Int?,
        val specialPrize: String?,
        val grandPrize: String?,
        val claws: Int?,
        val giveaway: Int?,
        val numberConfigurations: List<NumberConfiguration>?,

        // ✅ 新增：草稿記住規則類型（scratch / shopping）
        val pitchType: String = "scratch"
    )

    companion object DraftStore {
        private val _draftMap = MutableStateFlow<Map<Int, SettingsDraft>>(emptyMap())
        val draftMap: StateFlow<Map<Int, SettingsDraft>> = _draftMap

        fun saveDraft(order: Int, draft: SettingsDraft) {
            _draftMap.value = _draftMap.value.toMutableMap().apply { put(order, draft) }
        }

        fun getDraft(order: Int): SettingsDraft? = _draftMap.value[order]

        fun clearDraft(order: Int) {
            _draftMap.value = _draftMap.value.toMutableMap().apply { remove(order) }
        }

        /** ✅ 登出用：清空所有板位草稿 */
        fun clearAllDrafts() {
            _draftMap.value = emptyMap()
        }
    }

    // --- 下面這些是「instance wrapper」：讓你 Fragment 原本 viewModel.saveDraft(...) 不用改 ---
    val draftMap: StateFlow<Map<Int, SettingsDraft>> get() = DraftStore.draftMap
    fun saveDraft(order: Int, draft: SettingsDraft) = DraftStore.saveDraft(order, draft)
    fun getDraft(order: Int): SettingsDraft? = DraftStore.getDraft(order)
    fun clearDraft(order: Int) = DraftStore.clearDraft(order)
    fun clearAllDrafts() = DraftStore.clearAllDrafts()

    fun setInUseExclusive(target: ScratchCard, current: Map<Int, ScratchCard>, newState: Boolean) {
        viewModelScope.launch {
            try {
                repo.setInUseExclusive(userKey, target, current, newState)
                _events.emit(UiEvent.Toast(if (newState) "已設為使用中" else "已改為未使用"))
            } catch (e: Exception) {
                _events.emit(UiEvent.Toast("更新失敗：${e.message}"))
            }
        }
    }

    fun returnCard(order: Int, card: ScratchCard) {
        viewModelScope.launch {
            try {
                repo.returnScratchCard(userKey, card)
                _events.emit(UiEvent.Toast("${order}號板已返回背包，${card.scratchesType}刮數量+1。"))
            } catch (e: Exception) {
                _events.emit(UiEvent.Toast("返回失敗：${e.message}"))
            }
        }
    }

    fun deleteCard(serial: String) {
        viewModelScope.launch {
            try {
                repo.deleteScratchCard(userKey, serial)
                _events.emit(UiEvent.Toast("已刪除。"))
            } catch (e: Exception) {
                _events.emit(UiEvent.Toast("刪除失敗：${e.message}"))
            }
        }
    }

    fun upsertCard(
        order: Int,
        scratchesType: Int,
        specialPrize: String?,
        grandPrize: String?,
        clawsCount: Int?,
        giveawayCount: Int?,
        numberConfigurations: List<NumberConfiguration>,
        existingSerial: String?,
        keepInUsed: Boolean,

        // ✅ 新增（放最後、給預設值）
        pitchType: String = "scratch"
    ) {
        viewModelScope.launch {
            try {
                repo.upsertScratchCard(
                    userKey, order, scratchesType, specialPrize, grandPrize,
                    clawsCount, giveawayCount, numberConfigurations,
                    pitchType,                    // ✅ 這個位置要對上 repo 的 pitchType 參數
                    existingSerial, keepInUsed
                )
                _events.emit(UiEvent.Toast(if (existingSerial == null) "刮刮卡設定已新增！" else "${order}號板設定已更新！"))
            } catch (e: Exception) {
                _events.emit(UiEvent.Toast("儲存失敗：${e.message}"))
            }
        }
    }

    // 簡易 DI：用 Factory 注入 repo 與 userKey
    class Factory(
        private val repo: FirebaseRepository,
        private val userKey: String
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return SettingsViewModel(repo, userKey) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
