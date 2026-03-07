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
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // UI 事件（Toast等）
    private val _events = MutableSharedFlow<UiEvent>()
    val events = _events.asSharedFlow()

    sealed class UiEvent {
        data class Toast(val message: String) : UiEvent()
    }

    // ==============================
    // ✅ 設置頁「草稿」：每個板位各自保存
    // ==============================
    data class SettingsDraft(
        val scratchType: Int?,
        val specialPrize: String?,
        val grandPrize: String?,
        val claws: Int?,
        val giveaway: Int?,
        val numberConfigurations: List<NumberConfiguration>?,
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

        fun clearAllDrafts() {
            _draftMap.value = emptyMap()
        }
    }

    val draftMap: StateFlow<Map<Int, SettingsDraft>> get() = DraftStore.draftMap
    fun saveDraft(order: Int, draft: SettingsDraft) = DraftStore.saveDraft(order, draft)
    fun getDraft(order: Int): SettingsDraft? = DraftStore.getDraft(order)
    fun clearDraft(order: Int) = DraftStore.clearDraft(order)
    fun clearAllDrafts() = DraftStore.clearAllDrafts()

    // 🌟 核心修改：使用 updateChildren 精準修改 inUsed，絕對保護分割版面資料
    fun setInUseExclusive(target: ScratchCard, current: Map<Int, ScratchCard>, newState: Boolean) {
        viewModelScope.launch {
            try {
                val dbRef = com.google.firebase.database.FirebaseDatabase.getInstance(com.champion.king.core.config.AppConfig.DB_URL).reference
                val updates = mutableMapOf<String, Any>()

                if (newState) {
                    // 若設為使用中，先把其他所有卡片設為 false
                    current.values.forEach { card ->
                        if (card.serialNumber != null && card.serialNumber != target.serialNumber) {
                            updates["users/$userKey/scratchCards/${card.serialNumber}/inUsed"] = false
                        }
                    }
                }

                // 設定目標卡片
                if (target.serialNumber != null) {
                    updates["users/$userKey/scratchCards/${target.serialNumber}/inUsed"] = newState
                }

                if (updates.isNotEmpty()) {
                    dbRef.updateChildren(updates).addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            viewModelScope.launch {
                                _events.emit(UiEvent.Toast(if (newState) "已設為使用中" else "已改為未使用"))
                            }
                        } else {
                            viewModelScope.launch {
                                _events.emit(UiEvent.Toast("更新狀態失敗：${task.exception?.message}"))
                            }
                        }
                    }
                } else {
                    _events.emit(UiEvent.Toast("無有效的版位可更新"))
                }
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
        pitchType: String = "scratch"
    ) {
        viewModelScope.launch {
            try {
                repo.upsertScratchCard(
                    userKey, order, scratchesType, specialPrize, grandPrize,
                    clawsCount, giveawayCount, numberConfigurations,
                    pitchType,
                    existingSerial, keepInUsed
                )
                _events.emit(UiEvent.Toast(if (existingSerial == null) "刮刮卡設定已新增！" else "${order}號板設定已更新！"))
            } catch (e: Exception) {
                _events.emit(UiEvent.Toast("儲存失敗：${e.message}"))
            }
        }
    }

    fun saveSplitCardDirectly(
        existingSerial: String?,
        cardData: Map<String, Any>,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch {
            try {
                val dbRef = com.google.firebase.database.FirebaseDatabase.getInstance(com.champion.king.core.config.AppConfig.DB_URL).reference
                val cardRef = if (existingSerial != null) {
                    dbRef.child("users").child(userKey).child("scratchCards").child(existingSerial)
                } else {
                    dbRef.child("users").child(userKey).child("scratchCards").push()
                }

                cardRef.setValue(cardData).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        viewModelScope.launch {
                            _events.emit(UiEvent.Toast(if (existingSerial == null) "分割版面已新增！" else "分割版面已更新！"))
                            onComplete()
                        }
                    } else {
                        viewModelScope.launch {
                            _events.emit(UiEvent.Toast("儲存失敗：${task.exception?.message}"))
                            onComplete()
                        }
                    }
                }
            } catch (e: Exception) {
                _events.emit(UiEvent.Toast("儲存失敗：${e.message}"))
                onComplete()
            }
        }
    }

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