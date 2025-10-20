package com.champion.king.data

import com.champion.king.core.config.AppConfig
import com.google.firebase.database.*

/**
 * 供 BackpackFragment 使用的資料層：
 * - 監聽使用者點數
 * - 監聽使用者背包（users/{uid} 下的 scratchType_* 欄位）
 *
 * 備註：
 * - 回傳 Map<String, Int>：key = 刮卡數字(例如 "10"、"20")，value = 數量
 * - 使用與 ShopRepository 相同的 DbListenerHandle，onDestroyView 時可 remove()
 */
class BackpackRepository(
    private val root: DatabaseReference = FirebaseDatabase.getInstance(AppConfig.DB_URL).reference
) {
    private fun usersRef() = root.child("users")

    /** 監聽使用者點數 */
    fun observeUserPoints(
        userKey: String,
        onPoints: (Int) -> Unit,
        onError: (String) -> Unit
    ): DbListenerHandle {
        val q = usersRef().child(userKey).child("point")
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onPoints(snapshot.getValue(Int::class.java) ?: 0)
            }
            override fun onCancelled(error: DatabaseError) = onError(error.message)
        }
        q.addValueEventListener(l)
        return DbListenerHandle(q, l)
    }

    /**
     * 監聽背包內容：讀取 users/{uid} 下所有 scratchType_* 欄位（>0 才回傳）
     * 回傳 map：key = "10" / "20" / "25" ...，value = 數量
     */
    fun observeBackpack(
        userKey: String,
        onBackpack: (Map<String, Int>) -> Unit,
        onError: (String) -> Unit
    ): DbListenerHandle {
        val q = usersRef().child(userKey)
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val map = mutableMapOf<String, Int>()
                for (c in snapshot.children) {
                    val key = c.key ?: continue
                    if (key.startsWith("scratchType_")) {
                        val type = key.removePrefix("scratchType_")
                        val qty = c.getValue(Int::class.java) ?: 0
                        if (qty > 0) map[type] = qty
                    }
                }
                onBackpack(map)
            }
            override fun onCancelled(error: DatabaseError) = onError(error.message)
        }
        q.addValueEventListener(l)
        return DbListenerHandle(q, l)
    }
}
