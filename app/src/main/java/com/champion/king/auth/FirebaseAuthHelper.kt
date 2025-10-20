package com.champion.king.auth  // ← 改成你的實際套件

import android.util.Log
import com.google.firebase.auth.FirebaseAuth

/**
 * 單一職責：確保目前過程有 Firebase Auth 身分（匿名）。
 * - 若已登入：立刻回呼成功
 * - 若未登入：呼叫 signInAnonymously
 * - 併發安全：多個並發呼叫只觸發一次登入，完成後一起回呼
 */
object FirebaseAuthHelper {
    private const val TAG = "FirebaseAuthHelper"
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private val lock = Any()
    private var signingIn = false
    private val pending = mutableListOf<(Boolean, Exception?) -> Unit>()

    /**
     * 確保已具備匿名身分。成功時 callback(true,null)，失敗 callback(false,err)。
     */
    fun ensureAnonymous(callback: (Boolean, Exception?) -> Unit) {
        // 已有身分，直接成功
        if (auth.currentUser != null) {
            callback(true, null)
            return
        }

        var shouldStart = false
        synchronized(lock) {
            pending += callback
            if (!signingIn) {
                signingIn = true
                shouldStart = true
            }
        }
        if (!shouldStart) return

        auth.signInAnonymously()
            .addOnSuccessListener { result ->
                Log.d(TAG, "Anonymous auth OK: uid=${result.user?.uid}")
                val callbacks: List<(Boolean, Exception?) -> Unit> = synchronized(lock) {
                    signingIn = false
                    pending.toList().also { pending.clear() }
                }
                callbacks.forEach { it(true, null) }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Anonymous auth FAILED: ${e.message}", e)
                val callbacks: List<(Boolean, Exception?) -> Unit> = synchronized(lock) {
                    signingIn = false
                    pending.toList().also { pending.clear() }
                }
                callbacks.forEach { it(false, e) }
            }
    }
}
