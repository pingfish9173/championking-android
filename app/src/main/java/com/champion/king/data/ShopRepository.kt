package com.champion.king.data

import com.champion.king.core.config.AppConfig
import com.champion.king.model.ShopItem
import com.google.firebase.database.*

/** 可移除的監聽句柄 */
data class DbListenerHandle(val query: Query, val listener: ValueEventListener) {
    fun remove() = query.removeEventListener(listener)
}

class ShopRepository(
    private val root: DatabaseReference = FirebaseDatabase.getInstance(AppConfig.DB_URL).reference
) {
    private fun shopsRef() = root.child("shops")
    private fun usersRef() = root.child("users")

    /** 監聽商店清單（依 order 排序後回傳） */
    fun observeShopItems(
        onItems: (List<ShopItem>) -> Unit,
        onError: (String) -> Unit
    ): DbListenerHandle {
        val q = shopsRef()
        val l = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                val list = mutableListOf<ShopItem>()
                for (c in snap.children) c.getValue(ShopItem::class.java)?.let(list::add)
                onItems(list.sortedBy { it.order })
            }
            override fun onCancelled(error: DatabaseError) = onError(error.message)
        }
        q.addValueEventListener(l)
        return DbListenerHandle(q, l)
    }

    /** 監聽使用者點數 */
    fun observeUserPoints(
        userKey: String,
        onPoints: (Int) -> Unit,
        onError: (String) -> Unit
    ): DbListenerHandle {
        val q = usersRef().child(userKey).child("point")
        val l = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                onPoints(snap.getValue(Int::class.java) ?: 0)
            }
            override fun onCancelled(error: DatabaseError) = onError(error.message)
        }
        q.addValueEventListener(l)
        return DbListenerHandle(q, l)
    }

    /**
     * 購買（原子更新在 /users/{uid} 底下）：
     * - 重新抓最新點數，確認 >= totalCost
     * - 同步把 point 扣除 & 各 scratchType_x 以 ServerValue.increment() 增加
     */
    fun purchase(
        userKey: String,
        totalCost: Int,
        // key = productName（如 "10刮"），value = 數量
        quantities: Map<String, Int>,
        onResult: (success: Boolean, message: String?) -> Unit
    ) {
        val userRef = usersRef().child(userKey)

        userRef.child("point").get()
            .addOnSuccessListener { snap ->
                val current = snap.getValue(Int::class.java) ?: 0
                if (totalCost <= 0) { onResult(false, "購物車為空"); return@addOnSuccessListener }
                if (current < totalCost) { onResult(false, "點數不足"); return@addOnSuccessListener }

                val valid = setOf("10","20","25","30","40","50","60","80","100","120","160","200","240")
                val updates = hashMapOf<String, Any>("point" to (current - totalCost))

                quantities.forEach { (productName, qtyRaw) ->
                    val qty = qtyRaw.coerceAtLeast(0)
                    if (qty <= 0) return@forEach
                    val num = REGEX_NUMBER.find(productName)?.groupValues?.get(1)
                    if (num != null && valid.contains(num)) {
                        val field = "scratchType_$num"
                        updates[field] = ServerValue.increment(qty.toLong())
                    }
                }

                if (updates.size == 1) { onResult(false, "購物車為空"); return@addOnSuccessListener }

                userRef.updateChildren(updates)
                    .addOnSuccessListener { onResult(true, null) }
                    .addOnFailureListener { e -> onResult(false, e.message) }
            }
            .addOnFailureListener { e -> onResult(false, e.message) }
    }

    companion object {
        private val REGEX_NUMBER = Regex("""(\d+)刮""")
    }
}
