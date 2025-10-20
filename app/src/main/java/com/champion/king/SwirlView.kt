package com.champion.king

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class SwirlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var rotationAngle = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 橘色系漸層色
    private val colors = intArrayOf(
        0xFFFFF3E0.toInt(), // 最淺橘/米色 - 中心
        0xFFFFE0B2.toInt(), // 很淺橘
        0xFFFFCC80.toInt(), // 淺橘
        0xFFFFB74D.toInt(), // 中橘
        0xFFFF9800.toInt(), // 深橘
        0xFFEF6C00.toInt()  // 更深橘 - 外圍
    )

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFFBDBDBD.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = Math.min(width, height) / 2f

        // 繪製多層漩渦
        for (layer in 0 until 8) {
            val layerRadius = radius * (1f - layer * 0.12f)

            // 計算每層的旋轉偏移
            val layerRotation = rotationAngle + (layer * 45f)

            // 計算漩渦中心點偏移
            val offsetRadius = radius * 0.15f * (layer / 8f)
            val offsetAngle = Math.toRadians((layerRotation * 2).toDouble())
            val offsetX = (cos(offsetAngle) * offsetRadius).toFloat()
            val offsetY = (sin(offsetAngle) * offsetRadius).toFloat()

            // 創建徑向漸層
            val shader = RadialGradient(
                centerX + offsetX,
                centerY + offsetY,
                layerRadius,
                colors,
                null,
                Shader.TileMode.CLAMP
            )

            paint.shader = shader
            paint.alpha = (255 * (1f - layer * 0.1f)).toInt()

            canvas.drawCircle(centerX, centerY, layerRadius, paint)
        }

        // 繪製邊框
        canvas.drawCircle(centerX, centerY, radius - 2f, borderPaint)

        // 更新旋轉角度
        rotationAngle += 2f
        if (rotationAngle >= 360f) {
            rotationAngle = 0f
        }

        // 持續重繪
        postInvalidateOnAnimation()
    }
}