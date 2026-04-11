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
        val pitchType: String = "scratch",
        val isAnswerShowed: Boolean = false
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

    fun setInUseExclusive(target: ScratchCard, currentCards: Map<Int, ScratchCard>, newState: Boolean) {
        // userKey 已經是 SettingsViewModel 的屬性，直接使用即可
        viewModelScope.launch {
            try {
                // 呼叫 repo 去更新私有節點的 inUsed 狀態 (變數名為 repo)
                repo.setInUseExclusive(userKey, target, currentCards, newState)

                // 如果原本有發送成功的 Toast，可以寫在這裡
                // _events.emit(UiEvent.Toast(if (newState) "已設為使用中" else "已取消使用"))

            } catch (e: Exception) {
                // 使用 _events.emit 發送錯誤訊息
                _events.emit(UiEvent.Toast("設定狀態失敗: ${e.message}"))
            }
        }
    }

    // 🌟 核心修改：攔截分割版面的返回動作，確保退回的是正確的字串版型（如 20x4）
    fun returnCard(order: Int, card: ScratchCard) {
        viewModelScope.launch {
            try {
                if (!card.splitMode.isNullOrEmpty()) {
                    val dbRef = com.google.firebase.database.FirebaseDatabase.getInstance(com.champion.king.core.config.AppConfig.DB_URL).reference
                    val serial = card.serialNumber

                    if (serial != null) {
                        // 1. 從 Firebase 的 scratchCards 節點中刪除此板
                        dbRef.child("users").child(userKey).child("scratchCards").child(serial).removeValue()
                            .addOnCompleteListener { task ->
                                if (task.isSuccessful) {
                                    // 2. 增加分割版面專屬庫存 (例如 scratchType_20x4)
                                    val stockField = "scratchType_${card.splitMode}"
                                    val stockRef = dbRef.child("users").child(userKey).child(stockField)
                                    stockRef.get().addOnSuccessListener { snapshot ->
                                        val currentStock = snapshot.getValue(Int::class.java) ?: 0
                                        stockRef.setValue(currentStock + 1)
                                    }

                                    // 3. 顯示成功提示
                                    viewModelScope.launch {
                                        _events.emit(UiEvent.Toast("${order}號板已返回背包，${card.splitMode}版型數量+1。"))
                                    }
                                } else {
                                    viewModelScope.launch {
                                        _events.emit(UiEvent.Toast("返回失敗：${task.exception?.message}"))
                                    }
                                }
                            }
                    } else {
                        _events.emit(UiEvent.Toast("返回失敗：找不到卡片序號"))
                    }
                } else {
                    // 單一版面走原本的 repository 邏輯
                    repo.returnScratchCard(userKey, card)
                    _events.emit(UiEvent.Toast("${order}號板已返回背包，${card.scratchesType}刮數量+1。"))
                }
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
        pitchType: String = "scratch",
        isAnswerShowed: Boolean = false
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