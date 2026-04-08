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

    // 🌟 核心修改：以 UID 為節點，內部夾帶帳號名稱
    fun setInUseExclusive(target: ScratchCard, current: Map<Int, ScratchCard>, newState: Boolean) {
        viewModelScope.launch {
            try {
                val dbRef = com.google.firebase.database.FirebaseDatabase.getInstance(com.champion.king.core.config.AppConfig.DB_URL).reference

                // 1. 先去資料庫查詢這個 userKey 對應的「帳號名稱 (account)」
                dbRef.child("users").child(userKey).child("account").get().addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        viewModelScope.launch { _events.emit(UiEvent.Toast("無法取得帳號名稱，同步失敗")) }
                        return@addOnCompleteListener
                    }

                    // 取得真正的帳號名稱 (例如 "aaaaaa")
                    val accountName = task.result?.getValue(String::class.java) ?: "未知帳號"

                    val updates = mutableMapOf<String, Any?>()

                    if (newState) {
                        // 2. 若設為使用中，先把其他所有卡片設為 false
                        current.values.forEach { card ->
                            if (card.serialNumber != null && card.serialNumber != target.serialNumber) {
                                updates["users/$userKey/scratchCards/${card.serialNumber}/inUsed"] = false
                            }
                        }

                        // 3. 設定目標卡片為 true，並寫入大廳 (使用 userKey 當節點，傳入 accountName)
                        if (target.serialNumber != null) {
                            updates["users/$userKey/scratchCards/${target.serialNumber}/inUsed"] = true

                            // 將帳號名稱傳給生成函數
                            val publicData = generatePublicScratchBoardData(target, accountName)

                            // 🌟 關鍵：節點依然是 userKey，保有極致效能與安全
                            updates["public_live_scratchBoards/$userKey"] = publicData
                        }
                    } else {
                        // 4. 若是取消使用中 (設為 false)，同步下架公開版面
                        if (target.serialNumber != null) {
                            updates["users/$userKey/scratchCards/${target.serialNumber}/inUsed"] = false

                            // 🌟 關鍵：節點依然是 userKey
                            updates["public_live_scratchBoards/$userKey"] = null
                        }
                    }

                    // 5. 執行原子性更新
                    if (updates.isNotEmpty()) {
                        dbRef.updateChildren(updates).addOnCompleteListener { updateTask ->
                            if (updateTask.isSuccessful) {
                                viewModelScope.launch {
                                    _events.emit(UiEvent.Toast(if (newState) "已設為使用中，並同步至遠端系統！" else "已改為未使用，遠端系統已下架"))
                                }
                            } else {
                                viewModelScope.launch {
                                    _events.emit(UiEvent.Toast("更新狀態失敗：${updateTask.exception?.message}"))
                                }
                            }
                        }
                    } else {
                        viewModelScope.launch { _events.emit(UiEvent.Toast("無有效的版位可更新")) }
                    }
                }
            } catch (e: Exception) {
                _events.emit(UiEvent.Toast("更新失敗：${e.message}"))
            }
        }
    }

    // 🌟 核心防護：將 ScratchCard 轉換為只有「已刮開」資料的安全公開結構，並加入帳號名稱與詳細歷程
    private fun generatePublicScratchBoardData(card: ScratchCard, accountName: String): Map<String, Any> {
        val publicData = mutableMapOf<String, Any>()

        // 👑 總裁專屬：將帳號名稱塞在 Payload 第一層，供網頁端搜尋與顯示
        publicData["accountName"] = accountName

        publicData["serialNumber"] = card.serialNumber ?: ""
        publicData["order"] = card.order ?: 0
        publicData["scratchesType"] = card.scratchesType ?: 0

        val currentSplitMode = card.splitMode

        if (!currentSplitMode.isNullOrEmpty()) {
            // ==================================
            // 處理：分割版面 (Split Mode)
            // ==================================
            publicData["splitMode"] = currentSplitMode
            val publicBoards = mutableMapOf<String, Any>()

            val boardsMap = try {
                card.javaClass.getMethod("getBoards").invoke(card) as? Map<*, *>
            } catch (e: Exception) {
                try {
                    val field = card.javaClass.getDeclaredField("boards")
                    field.isAccessible = true
                    field.get(card) as? Map<*, *>
                } catch (ex: Exception) { null }
            }

            if (boardsMap != null) {
                for ((boardKey, rawBoard) in boardsMap) {
                    if (boardKey !is String || rawBoard == null) continue

                    var sp: Any = ""
                    var gp = ""
                    var pt = "scratch"
                    var cc = 1
                    var gc = 1
                    var configsList: List<*>? = null

                    if (rawBoard is Map<*, *>) {
                        sp = rawBoard["specialPrize"]?.toString()?.toIntOrNull() ?: rawBoard["specialPrize"]?.toString() ?: ""
                        gp = rawBoard["grandPrize"]?.toString() ?: ""
                        pt = rawBoard["pitchType"]?.toString() ?: "scratch"
                        cc = rawBoard["clawsCount"]?.toString()?.toIntOrNull() ?: 1
                        gc = (rawBoard["giveawayCount"] as? Number)?.toInt() ?: 1
                        configsList = rawBoard["numberConfigurations"] as? List<*>
                    } else {
                        try {
                            val clazz = rawBoard.javaClass
                            sp = (clazz.getMethod("getSpecialPrize").invoke(rawBoard) as? String)?.toIntOrNull() ?: ""
                            gp = clazz.getMethod("getGrandPrize").invoke(rawBoard) as? String ?: ""
                            pt = clazz.getMethod("getPitchType").invoke(rawBoard) as? String ?: "scratch"
                            cc = clazz.getMethod("getClawsCount").invoke(rawBoard) as? Int ?: 1
                            gc = clazz.getMethod("getGiveawayCount").invoke(rawBoard) as? Int ?: 1
                            configsList = clazz.getMethod("getNumberConfigurations").invoke(rawBoard) as? List<*>
                        } catch (e: Exception) {}
                    }

                    // 🌟 核心升級：將 value 變成詳細的 Map 結構
                    val publicConfigs = mutableMapOf<String, Any>()
                    configsList?.forEach { item ->
                        var id = 0
                        var number = 0
                        var scratched = false
                        var scratchedAt = 0L

                        if (item is Map<*, *>) {
                            id = (item["id"] as? Number)?.toInt() ?: 0
                            number = (item["number"] as? Number)?.toInt() ?: 0
                            scratched = item["scratched"] as? Boolean ?: false
                            scratchedAt = (item["scratchedAt"] as? Number)?.toLong() ?: 0L
                        } else if (item is NumberConfiguration) {
                            id = item.id
                            number = item.number
                            scratched = item.scratched
                            // 🛡️ 防呆機制：使用反射讀取 scratchedAt，避免如果您的 Data Class 忘記加這個欄位導致編譯失敗
                            scratchedAt = try {
                                item.javaClass.getMethod("getScratchedAt").invoke(item) as? Long ?: 0L
                            } catch (e: Exception) { 0L }
                        }

                        // 只記錄被刮開的，並且用「陣列位置 (id - 1)」當作節點名稱
                        if (scratched && id > 0) {
                            val indexKey = (id - 1).toString()
                            publicConfigs[indexKey] = mapOf(
                                "id" to id,
                                "number" to number,
                                "scratchedAt" to scratchedAt
                            )
                        }
                    }

                    publicBoards[boardKey] = mapOf(
                        "specialPrize" to sp,
                        "grandPrize" to gp,
                        "pitchType" to pt,
                        "clawsCount" to cc,
                        "giveawayCount" to gc,
                        "numberConfigurations" to publicConfigs
                    )
                }
            }
            publicData["boards"] = publicBoards

        } else {
            // ==================================
            // 處理：單一版面 (Single Mode)
            // ==================================
            val spStr = card.specialPrize
            publicData["specialPrize"] = spStr?.toIntOrNull() ?: spStr ?: ""
            publicData["grandPrize"] = card.grandPrize ?: ""
            publicData["pitchType"] = card.pitchType ?: "scratch"
            publicData["clawsCount"] = card.clawsCount ?: 1
            publicData["giveawayCount"] = card.giveawayCount ?: 1

            // 🌟 核心升級：將 value 變成詳細的 Map 結構
            val publicConfigs = mutableMapOf<String, Any>()
            card.numberConfigurations?.forEach { config ->
                if (config.scratched) {
                    val indexKey = (config.id - 1).toString()
                    val scratchedAtTime = try {
                        config.javaClass.getMethod("getScratchedAt").invoke(config) as? Long ?: 0L
                    } catch (e: Exception) { 0L }

                    publicConfigs[indexKey] = mapOf(
                        "id" to config.id,
                        "number" to config.number,
                        "scratchedAt" to scratchedAtTime
                    )
                }
            }
            publicData["numberConfigurations"] = publicConfigs
        }

        return publicData
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