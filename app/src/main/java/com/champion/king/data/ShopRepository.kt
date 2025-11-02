package com.champion.king.data

import android.util.Log
import com.champion.king.core.config.AppConfig
import com.champion.king.model.ShopItem
import com.google.firebase.database.*
import com.google.firebase.database.FirebaseDatabase
import com.champion.king.model.PurchaseRecord
import com.champion.king.model.PurchaseItem

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

    /**
     * 保存購買紀錄到資料庫
     * @param userKey 用戶Key
     * @param username 用戶名稱（可選）
     * @param totalPoints 總花費點數
     * @param purchaseDetails 購買詳細資料 Map<商品名稱, Pair<購買數量, 贈送數量>>
     * @param itemPrices 商品單價 Map<商品名稱, 單價>
     * @param callback 回調函數
     */
    fun savePurchaseRecord(
        userKey: String,
        username: String = "",
        totalPoints: Int,
        purchaseDetails: Map<String, Pair<Int, Int>>,  // <商品名, <購買數, 贈送數>>
        itemPrices: Map<String, Int>,  // <商品名, 單價>
        callback: (success: Boolean, message: String?) -> Unit
    ) {
        try {
            val database = FirebaseDatabase.getInstance()
            val purchaseRecordsRef = database.getReference("purchase_records")

            // 生成唯一的紀錄ID
            val recordId = purchaseRecordsRef.push().key ?: run {
                callback(false, "無法生成紀錄ID")
                return
            }

            // 準備購買項目資料
            val items = mutableMapOf<String, PurchaseItem>()
            purchaseDetails.forEach { (productName, quantities) ->
                val (purchasedQty, bonusQty) = quantities
                val pricePerUnit = itemPrices[productName] ?: 0
                val subtotal = purchasedQty * pricePerUnit

                items[productName] = PurchaseItem(
                    productName = productName,
                    purchasedQuantity = purchasedQty,
                    bonusQuantity = bonusQty,
                    pricePerUnit = pricePerUnit,
                    subtotal = subtotal
                )
            }

            // 創建購買紀錄
            val purchaseRecord = PurchaseRecord(
                recordId = recordId,
                userKey = userKey,
                username = username,
                purchaseTime = System.currentTimeMillis(),
                totalPoints = totalPoints,
                items = items
            )

            // 保存到資料庫
            purchaseRecordsRef.child(recordId).setValue(purchaseRecord)
                .addOnSuccessListener {
                    callback(true, "購買紀錄已保存")
                }
                .addOnFailureListener { e ->
                    callback(false, "保存購買紀錄失敗：${e.message}")
                }

        } catch (e: Exception) {
            callback(false, "保存購買紀錄異常：${e.message}")
        }
    }

    /**
     * 獲取用戶帳號
     * @param userKey 用戶 Key
     * @param callback 回調函數 (成功, 帳號名稱)
     */
    fun getUserAccount(userKey: String, callback: (success: Boolean, account: String?) -> Unit) {
        try {
            val database = FirebaseDatabase.getInstance()
            val userRef = database.getReference("users").child(userKey).child("account")

            userRef.get().addOnSuccessListener { snapshot ->
                val account = snapshot.getValue(String::class.java)
                callback(true, account)
            }.addOnFailureListener { e ->
                Log.e("ShopRepository", "獲取用戶帳號失敗：${e.message}")
                callback(false, null)
            }
        } catch (e: Exception) {
            Log.e("ShopRepository", "獲取用戶帳號異常：${e.message}")
            callback(false, null)
        }
    }

    companion object {
        private val REGEX_NUMBER = Regex("""(\d+)刮""")
    }
}
