package com.champion.king.util

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.champion.king.R
import java.lang.ref.WeakReference

object ToastManager {

    private val handler = Handler(Looper.getMainLooper())

    // 用 token 控制「延長顯示」與「避免舊的 hide 生效」
    private var token: Long = 0L

    // 不要強持有 Activity
    private var activityRef: WeakReference<Activity>? = null

    fun show(
        context: Context,
        message: String,
        durationMs: Long = 4000L,
        yOffsetDp: Int = 64,       // 你要更高：64/72/80
        textSizeSp: Float = 16f
    ) {
        val activity = context as? Activity ?: return
        activityRef = WeakReference(activity)

        val myToken = ++token

        activity.runOnUiThread {
            val decor = activity.window.decorView as ViewGroup
            val container = ensureContainer(decor)
            val tv = ensureTextView(container)

            tv.text = message
            tv.setTypeface(tv.typeface, Typeface.BOLD)
            tv.textSize = textSizeSp

            val lp = tv.layoutParams as FrameLayout.LayoutParams
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = dp(activity, yOffsetDp)
            tv.layoutParams = lp

            if (!tv.isVisible) {
                tv.alpha = 0f
                tv.isVisible = true
                tv.animate().alpha(1f).setDuration(120).start()
            }

            // 不保存 Runnable；用 token 判斷是否仍有效
            handler.postDelayed({
                val act = activityRef?.get() ?: return@postDelayed
                if (token != myToken) return@postDelayed

                act.runOnUiThread {
                    val d = act.window.decorView as? ViewGroup ?: return@runOnUiThread
                    val c = d.findViewWithTag<FrameLayout>(CONTAINER_TAG) ?: return@runOnUiThread
                    val t = c.findViewWithTag<TextView>(TEXT_TAG) ?: return@runOnUiThread

                    t.animate()
                        .alpha(0f)
                        .setDuration(160)
                        .withEndAction { t.isVisible = false }
                        .start()
                }
            }, durationMs)
        }
    }

    private const val CONTAINER_TAG = "ToastManager.Container"
    private const val TEXT_TAG = "ToastManager.Text"

    private fun ensureContainer(decor: ViewGroup): FrameLayout {
        val existing = decor.findViewWithTag<FrameLayout>(CONTAINER_TAG)
        if (existing != null) return existing

        return FrameLayout(decor.context).apply {
            tag = CONTAINER_TAG
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            decor.addView(this)
        }
    }

    private fun ensureTextView(container: FrameLayout): TextView {
        val existing = container.findViewWithTag<TextView>(TEXT_TAG)
        if (existing != null) return existing

        val tv = TextView(container.context).apply {
            tag = TEXT_TAG
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.bg_toast)
            setPadding(dp(context, 24), dp(context, 12), dp(context, 24), dp(context, 12))
            maxWidth = dp(context, 600)
            gravity = Gravity.CENTER
            isVisible = false
        }

        container.addView(
            tv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(container.context, 64)
            }
        )
        return tv
    }

    private fun dp(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}
