package com.champion.king

import android.app.AlertDialog  // 新增
import android.app.Dialog
import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

class ScratchDialog(
    context: Context,
    private val number: Int,
    private val isSpecialPrize: Boolean,
    private val isGrandPrize: Boolean,
    private val isSecondToLast: Boolean,
    private val hasUnscatchedPrizesRemaining: Boolean,
    private val onScratchStart: () -> Unit,
    private val onScratchComplete: () -> Unit,
    private val onTimeoutForceReveal: (() -> Unit)? = null // ✅ 新增：逾時強制視為刮開
) : Dialog(context, android.R.style.Theme_Dialog) {

    private lateinit var scratchView: ScratchView
    private lateinit var quickScratchButton: Button
    private var canCancelByTouchingOutside = true
    private var mediaPlayer: MediaPlayer? = null

    private var hasClickedQuickScratch = false
    private var isPlayingSound = false
    private var hasTriggeredScratchStart = false

    // ====== ✅ 60秒無動作 Timeout 機制 ======
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val TIMEOUT_MS = 60_000L
    private var lastInteractionAt = 0L

    // 返回確認視窗（避免卡住）
    private var backConfirmDialog: AlertDialog? = null

    private val inactivityRunnable = Runnable {
        handleInactivityTimeout()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        (context as? MainActivity)?.enableImmersiveMode()
        super.onCreate(savedInstanceState)

        window?.setBackgroundDrawableResource(android.R.color.transparent)
        setContentView(R.layout.dialog_scratch_card)

        scratchView = findViewById(R.id.scratch_view)
        quickScratchButton = findViewById(R.id.quick_scratch_button)

        scratchView.setup(number, isSpecialPrize, isGrandPrize)

        // ✅ Dialog 一出現就開始倒數（60秒無動作就自動處理）
        resetInactivityTimer()

        // 監聽刮卡開始事件 - 防弊機制關鍵
        scratchView.setOnScratchStartListener {
            // 一旦開始刮卡，就不能通過點擊外部關閉
            canCancelByTouchingOutside = false
            setCanceledOnTouchOutside(false)

            // 立即觸發防弊機制：標記 hasTriggeredScratchStart
            if (!hasTriggeredScratchStart) {
                hasTriggeredScratchStart = true
                onScratchStart.invoke()
            }

            // ✅ 有刮動，重置倒數
            resetInactivityTimer()
        }

        // 初始狀態允許點擊外部關閉
        setCanceledOnTouchOutside(true)

        quickScratchButton.setOnClickListener {
            // ✅ 點按也算互動
            resetInactivityTimer()

            // 標記為已點擊一鍵刮開
            hasClickedQuickScratch = true

            // 點擊一鍵刮開後，立即禁止點擊外部關閉
            canCancelByTouchingOutside = false
            setCanceledOnTouchOutside(false)

            // 立即觸發防弊機制：標記 hasTriggeredScratchStart
            if (!hasTriggeredScratchStart) {
                hasTriggeredScratchStart = true
                onScratchStart.invoke()
            }

            // 先清除塗層
            scratchView.revealCompletely()

            // 檢查是否已經在播放音效
            if (!isPlayingSound) {
                isPlayingSound = true

                // ✅ 已經進入完成收尾（播放音效），停止 Timeout 避免干擾
                stopInactivityTimer()

                val soundResId = getSoundResource()
                val delayTime = getSoundDuration(soundResId)
                playSound(soundResId)
                scratchView.postDelayed({
                    onScratchComplete()
                    dismiss()
                }, delayTime)
            }
        }

        scratchView.setOnScratchCompleteListener {
            canCancelByTouchingOutside = false
            setCanceledOnTouchOutside(false)

            if (!isPlayingSound) {
                isPlayingSound = true

                // ✅ 已經進入完成收尾（播放音效），停止 Timeout 避免干擾
                stopInactivityTimer()

                val soundResId = getSoundResource()
                val delayTime = getSoundDuration(soundResId)
                playSound(soundResId)
                scratchView.postDelayed({
                    onScratchComplete()
                    dismiss()
                }, delayTime)
            }
        }
    }

    /**
     * 攔截返回鍵的處理
     * 如果已經開始刮卡但還沒完成，彈出確認視窗
     * 如果還沒開始刮卡，直接關閉
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                // 返回鍵彈起時處理
                if (hasTriggeredScratchStart && !isPlayingSound) {
                    // 情況1：已經開始刮卡，但還沒刮完（未達到75%或未點一鍵刮開）
                    android.util.Log.d("ScratchDialog", "【返回鍵】玩家已開始刮卡但未完成，顯示確認視窗")
                    showBackConfirmationDialog()
                } else if (isPlayingSound) {
                    // 情況2：正在播放音效（已經刮完或點了一鍵刮開）
                    // 此時不允許返回，需要等音效播完
                    android.util.Log.d("ScratchDialog", "【返回鍵】音效播放中，不允許返回")
                    // 不處理，保持對話框開啟
                } else {
                    // 情況3：還沒開始刮卡，正常關閉
                    android.util.Log.d("ScratchDialog", "【返回鍵】玩家未開始刮卡，正常關閉")
                    dismiss()
                }
                return true
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 只要玩家有觸碰，就視為仍在操作 -> 重置倒數
        // （播放音效收尾時不重置，避免拖延）
        if (!isPlayingSound) {
            resetInactivityTimer()
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 顯示返回確認視窗
     */
    private fun showBackConfirmationDialog() {
        // ✅ 顯示確認視窗也算互動
        resetInactivityTimer()

        val dialog = AlertDialog.Builder(context)
            .setTitle("確認返回")
            .setMessage("您已刮開部分塗層，返回刮板後將視為一鍵刮開，是否確認返回？")
            .setPositiveButton("確定") { d, _ ->
                android.util.Log.d("ScratchDialog", "【返回確認】玩家確認返回，標記為已刮開")

                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null

                // 視為刮開
                onScratchComplete()

                d.dismiss()
                this@ScratchDialog.dismiss()
            }
            .setNegativeButton("取消") { d, _ ->
                android.util.Log.d("ScratchDialog", "【返回確認】玩家取消返回，繼續刮卡")
                d.dismiss()

                // ✅ 繼續刮卡也重置倒數
                resetInactivityTimer()
            }
            .setCancelable(false)
            .create()

        dialog.setCanceledOnTouchOutside(false)
        dialog.show()

        // ✅ 保存起來，讓 Timeout 時可以一併關閉，避免卡住
        backConfirmDialog = dialog
    }

    private fun resetInactivityTimer() {
        lastInteractionAt = SystemClock.uptimeMillis()
        timeoutHandler.removeCallbacks(inactivityRunnable)
        timeoutHandler.postDelayed(inactivityRunnable, TIMEOUT_MS)
    }

    private fun stopInactivityTimer() {
        timeoutHandler.removeCallbacks(inactivityRunnable)
    }

    private fun handleInactivityTimeout() {
        // 播放音效中（代表已經完成流程在收尾），不要插手
        if (isPlayingSound) return

        // 如果剛好有返回確認視窗也一起關掉，避免殘留卡畫面
        backConfirmDialog?.dismiss()
        backConfirmDialog = null

        if (hasTriggeredScratchStart) {
            android.util.Log.d("ScratchDialog", "【無動作逾時】已開始刮卡但未完成，強制視為刮開並關閉")

            // ✅ 你要的「刮沒乾淨跑掉」：直接視為刮開
            // 優先走外部傳入的強制流程；沒傳就用原本 onScratchComplete
            (onTimeoutForceReveal ?: onScratchComplete).invoke()
        } else {
            android.util.Log.d("ScratchDialog", "【無動作逾時】未開始刮卡，直接關閉")
        }

        dismiss()
    }

    fun hasStartedScratching(): Boolean {
        if (hasClickedQuickScratch) {
            return true
        }

        return if (::scratchView.isInitialized) {
            scratchView.hasStartedScratching()
        } else {
            false
        }
    }

    private fun getSoundResource(): Int {
        return when {
            isSpecialPrize -> R.raw.special
            isGrandPrize -> R.raw.big
            isSecondToLast && hasUnscatchedPrizesRemaining -> R.raw.sad
            else -> R.raw.normal
        }
    }

    private fun getSoundDuration(soundResId: Int): Long {
        return when (soundResId) {
            R.raw.special -> 6200L
            R.raw.sad -> 9200L
            R.raw.big -> 2200L
            R.raw.normal -> 2200L
            else -> 2200L
        }
    }

    private fun playSound(soundResId: Int) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(context, soundResId)
            mediaPlayer?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!canCancelByTouchingOutside && event.action == MotionEvent.ACTION_DOWN) {
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun dismiss() {
        // ✅ 清 Timeout
        stopInactivityTimer()

        // ✅ 清返回確認視窗
        backConfirmDialog?.dismiss()
        backConfirmDialog = null

        mediaPlayer?.release()
        mediaPlayer = null
        super.dismiss()
    }

    override fun onStart() {
        super.onStart()
        (context as? MainActivity)?.enableImmersiveMode()
    }
    override fun onStop() {
        super.onStop()
        (context as? MainActivity)?.enableImmersiveMode()
    }

}