package com.champion.king.data

import com.champion.king.core.config.AppConfig
import com.champion.king.model.ScratchCard
import com.google.firebase.database.*

/**
 * 讀取使用者的刮刮卡資料。
 * - 仍使用即時監聽（與你原邏輯一致）
 * - 只收 1..6 的 order（與原檔一致）
 * - 幫卡補上 serialNumber = child.key
 */
class ScratchCardRepository {

    private val db: DatabaseReference =
        FirebaseDatabase.getInstance(AppConfig.DB_URL).reference

    fun observeUserScratchCards(
        userKey: String,
        onCards: (List<ScratchCard>) -> Unit,
        onError: (String) -> Unit
    ): DbListenerHandle {
        val ref = db.child("users").child(userKey).child("scratchCards")
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = buildList {
                    snapshot.children.forEach { child ->
                        val card = child.getValue(ScratchCard::class.java)
                        if (card != null && card.order in 1..6) {
                            card.serialNumber = child.key
                            add(card)
                        }
                    }
                }
                onCards(list)
            }

            override fun onCancelled(error: DatabaseError) {
                onError(error.message)
            }
        }
        ref.addValueEventListener(l)
        return DbListenerHandle(ref, l)
    }
}
