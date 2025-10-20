package com.champion.king

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class ScratchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var number: Int = 0
    private var isSpecialPrize: Boolean = false
    private var isGrandPrize: Boolean = false
    private val scratchPath = Path()

    // 保存上一次觸控位置，用於繪制連續線條
    private var lastX = 0f
    private var lastY = 0f
    private var isDrawing = false

    private lateinit var scratchBitmap: Bitmap
    private lateinit var scratchCanvas: Canvas

    // 新增：追蹤是否已經開始刮卡
    private var hasStartedScratching = false
    private var onScratchStartListener: (() -> Unit)? = null

    // 新增：追蹤是否已經觸發完成事件
    private var hasTriggeredComplete = false

    private val scratchPaint = Paint().apply {
        isAntiAlias = true
        isDither = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 35f
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        // 添加路徑效果，讓筆觸更平滑
        pathEffect = null
    }

    private val backgroundPaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
    }

    private val textPaint = Paint().apply {
        isAntiAlias = true
        color = Color.GRAY
        textSize = 100f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val strokePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private var scratchedPercent = 0f
    private var onScratchCompleteListener: (() -> Unit)? = null

    fun setup(number: Int, isSpecialPrize: Boolean, isGrandPrize: Boolean) {
        this.number = number
        this.isSpecialPrize = isSpecialPrize
        this.isGrandPrize = isGrandPrize
        this.hasStartedScratching = false // 重置狀態
        this.hasTriggeredComplete = false // 重置完成狀態
    }

    // 新增：設定刮卡開始監聽器
    fun setOnScratchStartListener(listener: () -> Unit) {
        onScratchStartListener = listener
    }

    // 新增：檢查是否已開始刮卡
    fun hasStartedScratching(): Boolean = hasStartedScratching

    fun setOnScratchCompleteListener(listener: () -> Unit) {
        onScratchCompleteListener = listener
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        if (w > 0 && h > 0) {
            scratchBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            scratchCanvas = Canvas(scratchBitmap)

            // 初始化圓形灰色遮罩，而不是矩形
            val centerX = w / 2f
            val centerY = h / 2f
            val radius = minOf(w, h) / 2f - 20f  // 減少一些邊距

            // 先清空畫布
            scratchCanvas.drawColor(Color.TRANSPARENT)
            // 只在圓形區域畫灰色遮罩
            scratchCanvas.drawCircle(centerX, centerY, radius, Paint().apply {
                color = Color.BLACK
                isAntiAlias = true
            })
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = minOf(width, height) / 2f - 20f

        // 畫背景圓圈和數字，但不畫邊框
        canvas.drawCircle(centerX, centerY, radius, backgroundPaint)
        canvas.drawText(
            number.toString(),
            centerX,
            centerY + textPaint.textSize / 3,
            textPaint
        )

        // 畫刮除遮罩
        if (::scratchBitmap.isInitialized) {
            canvas.drawBitmap(scratchBitmap, 0f, 0f, null)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!::scratchCanvas.isInitialized) return super.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                scratchPath.reset()
                scratchPath.moveTo(event.x, event.y)
                scratchPath.lineTo(event.x + 0.1f, event.y + 0.1f)
                scratchCanvas.drawPath(scratchPath, scratchPaint)

                lastX = event.x
                lastY = event.y
                isDrawing = true

                // 標記已開始刮卡並通知監聽器
                if (!hasStartedScratching) {
                    hasStartedScratching = true
                    onScratchStartListener?.invoke()
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDrawing) {
                    scratchPath.reset()
                    scratchPath.moveTo(lastX, lastY)
                    scratchPath.lineTo(event.x, event.y)
                    scratchCanvas.drawPath(scratchPath, scratchPaint)

                    lastX = event.x
                    lastY = event.y

                    calculateScratchedPercent()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                isDrawing = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun revealCompletely() {
        if (::scratchCanvas.isInitialized) {
            scratchCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            scratchedPercent = 100f
            invalidate()
        }
    }

    private fun calculateScratchedPercent() {
        if (!::scratchBitmap.isInitialized) return

        val pixels = IntArray(scratchBitmap.width * scratchBitmap.height)
        scratchBitmap.getPixels(pixels, 0, scratchBitmap.width, 0, 0, scratchBitmap.width, scratchBitmap.height)

        val transparentPixels = pixels.count { Color.alpha(it) == 0 }
        scratchedPercent = (transparentPixels.toFloat() / pixels.size) * 100

        // 讓玩家需要刮得多乾淨才會自動完成：85%
        // 確保只觸發一次
        if (scratchedPercent > 85f && !hasTriggeredComplete) {
            hasTriggeredComplete = true
            onScratchCompleteListener?.invoke()
        }
    }
}