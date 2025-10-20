package com.champion.king

import android.app.AlertDialog  // 新增
import android.app.Dialog
import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button

class ScratchDialog(
    context: Context,
    private val number: Int,
    private val isSpecialPrize: Boolean,
    private val isGrandPrize: Boolean,
    private val isSecondToLast: Boolean,
    private val hasUnscatchedPrizesRemaining: Boolean,
    private val onScratchStart: () -> Unit,
    private val onScratchComplete: () -> Unit
) : Dialog(context, android.R.style.Theme_Dialog) {

    private lateinit var scratchView: ScratchView
    private lateinit var quickScratchButton: Button
    private var canCancelByTouchingOutside = true
    private var mediaPlayer: MediaPlayer? = null

    private var hasClickedQuickScratch = false
    private var isPlayingSound = false
    private var hasTriggeredScratchStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window?.setBackgroundDrawableResource(android.R.color.transparent)
        setContentView(R.layout.dialog_scratch_card)

        scratchView = findViewById(R.id.scratch_view)
        quickScratchButton = findViewById(R.id.quick_scratch_button)

        scratchView.setup(number, isSpecialPrize, isGrandPrize)

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
        }

        // 初始狀態允許點擊外部關閉
        setCanceledOnTouchOutside(true)

        quickScratchButton.setOnClickListener {
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

    /**
     * 顯示返回確認視窗
     */
    private fun showBackConfirmationDialog() {
        AlertDialog.Builder(context)
            .setTitle("確認返回")
            .setMessage("您已刮開部分塗層，返回刮板後將視為一鍵刮開，是否確認返回？")
            .setPositiveButton("確定") { dialog, _ ->
                android.util.Log.d("ScratchDialog", "【返回確認】玩家確認返回，標記為已刮開")

                // 停止音效（如果正在播放）
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null

                // 調用完成回調，將 scratched 設為 true
                onScratchComplete()

                // 關閉確認視窗
                dialog.dismiss()

                // 關閉刮卡對話框
                this@ScratchDialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ ->
                android.util.Log.d("ScratchDialog", "【返回確認】玩家取消返回，繼續刮卡")
                // 繼續刮卡，不做任何操作
                dialog.dismiss()
            }
            .setCancelable(false) // 禁止點擊外部關閉確認視窗
            .show()
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
        mediaPlayer?.release()
        mediaPlayer = null
        super.dismiss()
    }
}