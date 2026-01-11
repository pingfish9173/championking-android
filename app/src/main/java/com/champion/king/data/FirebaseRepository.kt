package com.champion.king.data

import com.champion.king.model.NumberConfiguration
import com.champion.king.model.ScratchCard
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.util.Log

class FirebaseRepository(private val rootRef: DatabaseReference) {

    // 路徑小工具
    fun userRef(userKey: String) = rootRef.child("users").child(userKey)
    private fun scratchCardsRef(userKey: String) = userRef(userKey).child("scratchCards")
    private fun scratchCardRef(userKey: String, serial: String) = scratchCardsRef(userKey).child(serial)
    private fun backpackCounterKey(scratchesType: Int) = "scratchType_$scratchesType"

    companion object {
        private const val TAG = "FirebaseRepository"
    }

    // 監聽 6 個版位：Flow<Map<order, ScratchCard>>
    fun listenScratchCardsFlow(userKey: String): Flow<Map<Int, ScratchCard>> = callbackFlow {
        val ref = scratchCardsRef(userKey)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val map = mutableMapOf<Int, ScratchCard>()
                for (child in snapshot.children) {
                    val card = child.getValue(ScratchCard::class.java) ?: continue
                    card.serialNumber = child.key
                    if (card.order in 1..6) map[card.order] = card
                }
                trySend(map).isSuccess
            }

            override fun onCancelled(error: DatabaseError) {
                // 登出/權限切換時很容易發生，不要把整條 Flow 關死
                trySend(emptyMap()).isSuccess
                // 這邊不要 close(exception)，避免 UI 之後永遠收不到資料
            }
        }

        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    // 新增或更新（existingSerial == null -> 新增；否則更新）
    suspend fun upsertScratchCard(
        userKey: String,
        order: Int,
        scratchesType: Int,
        specialPrize: String?,
        grandPrize: String?,
        clawsCount: Int?,
        giveawayCount: Int?,
        numberConfigurations: List<NumberConfiguration>,

        // ✅ 新增（放最後、給預設值，降低其他呼叫點風險）
        pitchType: String = "scratch",

        existingSerial: String? = null,
        keepInUsed: Boolean = false
    ) {
        val serial = existingSerial ?: UUID.randomUUID().toString()
        val data = ScratchCard(
            serialNumber = serial,
            numberConfigurations = numberConfigurations,
            scratchesType = scratchesType,
            order = order,
            specialPrize = specialPrize,
            grandPrize = grandPrize,
            inUsed = keepInUsed,
            pitchType = pitchType,          // ✅ 存進 Firebase
            clawsCount = clawsCount,
            giveawayCount = giveawayCount
        )
        scratchCardRef(userKey, serial).setValue(data).await()
    }

    // 刪除（不動背包數量）
    suspend fun deleteScratchCard(userKey: String, serial: String) {
        scratchCardRef(userKey, serial).removeValue().await()
    }

    // 返回＝刪除該卡 + 背包數量 +1（transaction）
    suspend fun returnScratchCard(userKey: String, card: ScratchCard) {
        val serial = card.serialNumber ?: throw IllegalStateException("serialNumber is null")
        // 1) 刪除該版位卡片 → 變未設置
        scratchCardRef(userKey, serial).removeValue().await()
        // 2) 背包 +1（交易，避免併發）
        val counterRef = userRef(userKey).child(backpackCounterKey(card.scratchesType))
        runTransactionSuspend(counterRef) { cur -> (cur as? Long ?: 0L) + 1L }
    }

    // 切換 inUsed（newState=true 時，其他 inUsed=true 改為 false，確保唯一）
    suspend fun setInUseExclusive(
        userKey: String,
        target: ScratchCard,
        currentCards: Map<Int, ScratchCard>,
        newState: Boolean
    ) {
        val targetSerial = target.serialNumber ?: throw IllegalStateException("serialNumber is null")
        val updates = hashMapOf<String, Any?>(
            "users/$userKey/scratchCards/$targetSerial/inUsed" to newState
        )
        if (newState) {
            currentCards.values.forEach { c ->
                val s = c.serialNumber ?: return@forEach
                if (s != targetSerial && c.inUsed) {
                    updates["users/$userKey/scratchCards/$s/inUsed"] = false
                }
            }
        }
        rootRef.updateChildren(updates).await()
    }

    // 把 runTransaction 包成 suspend
    private suspend fun runTransactionSuspend(
        ref: DatabaseReference,
        updater: (Any?) -> Any?
    ) = suspendCancellableCoroutine<Unit> { cont ->
        ref.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                currentData.value = updater(currentData.value)
                return Transaction.success(currentData)
            }

            override fun onComplete(
                error: DatabaseError?,
                committed: Boolean,
                snapshot: DataSnapshot?
            ) {
                if (error == null && committed) cont.resume(Unit)
                else cont.resumeWithException(
                    error?.toException() ?: Exception("Transaction failed")
                )
            }
        })
    }
}