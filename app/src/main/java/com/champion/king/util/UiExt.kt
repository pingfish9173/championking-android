package com.champion.king.util

import android.content.Context
import android.os.Build
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Lifecycle
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.MotionEvent
import android.widget.EditText
import androidx.annotation.DrawableRes
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import com.champion.king.core.config.AppConfig

/** 防連點 */
fun View.setThrottledClick(intervalMs: Long = 600, onClick: (View) -> Unit) {
    var last = 0L
    setOnClickListener {
        val now = System.currentTimeMillis()
        if (now - last > intervalMs) {
            last = now
            onClick(it)
        }
    }
}

/** 網路守門（需已有 isInternetAvailable() 與 toast()） */
inline fun Context.guardOnline(crossinline block: () -> Unit) {
    if (isInternetAvailable()) block() else toast(AppConfig.Msg.NO_INTERNET)
}

fun EditText.attachPasswordToggle(
    @DrawableRes visibleIcon: Int,
    @DrawableRes hiddenIcon: Int
) {
    var visible = false

    fun applyVisibility(show: Boolean) {
        visible = show
        if (show) {
            transformationMethod = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            transformationMethod = PasswordTransformationMethod.getInstance()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        setSelection(text?.length ?: 0)
        val icon = if (visible) visibleIcon else hiddenIcon
        TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(this, 0, 0, icon, 0)
        compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
    }

    applyVisibility(false)

    setOnTouchListener { v, event ->
        if (event.action == MotionEvent.ACTION_UP) {
            val rel = TextViewCompat.getCompoundDrawablesRelative(this)
            val end = rel[2] ?: return@setOnTouchListener false
            val isRtl = ViewCompat.getLayoutDirection(this) == ViewCompat.LAYOUT_DIRECTION_RTL
            val hit = if (isRtl) {
                event.x <= (paddingLeft + end.bounds.width())
            } else {
                event.x >= (width - paddingRight - end.bounds.width())
            }
            if (hit) {
                v.performClick()
                applyVisibility(!visible)
                return@setOnTouchListener true
            }
        }
        false
    }
}

/* ---- Toast 擴充：三種接收者都支援（統一導向 ToastManager） ---- */
private fun toastLengthToDurationMs(length: Int): Long {
    // Android 的 Toast 長度只有 SHORT/LONG，我們映射到自家可控毫秒
    return when (length) {
        Toast.LENGTH_LONG -> 3500L
        else -> 2000L
    }
}

fun Context.toast(message: String, length: Int = Toast.LENGTH_SHORT) {
    ToastManager.show(
        context = this,
        message = message,
        durationMs = toastLengthToDurationMs(length)
    )
}

fun Fragment.toast(message: String, length: Int = Toast.LENGTH_SHORT) {
    // 用 Fragment 的 Activity 更穩（ToastManager 需要 Activity 來掛浮層）
    val act = activity
    if (act != null) {
        ToastManager.show(
            context = act,
            message = message,
            durationMs = toastLengthToDurationMs(length)
        )
    } else {
        // 保底：若 fragment 尚未 attach，避免炸掉
        requireContext().toast(message, length)
    }
}

fun View.toast(message: String, length: Int = Toast.LENGTH_SHORT) {
    // View.context 通常是 Activity（或 ContextThemeWrapper）
    ToastManager.show(
        context = context,
        message = message,
        durationMs = toastLengthToDurationMs(length)
    )
}

/* ---- View 類常用 ---- */
fun View.show() {
    this.visibility = View.VISIBLE
}

fun View.hide() {
    this.visibility = View.GONE
}

/* ---- Fragment 安全更新 UI ---- */
fun Fragment.canSafelyUpdateUi(): Boolean =
    isAdded && view != null &&
            viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

/* ---- 安全 replace（狀態已儲存時改用 allowingStateLoss） ---- */
fun FragmentManager.safeReplace(
    containerId: Int,
    fragment: Fragment,
    tag: String? = null,
    reorderingAllowed: Boolean = true
) {
    val tx: FragmentTransaction = beginTransaction()
    if (reorderingAllowed) tx.setReorderingAllowed(true)
    tx.replace(containerId, fragment, tag)
    if (isStateSaved) tx.commitAllowingStateLoss() else tx.commit()
}

/* ---- 網路可用性 ---- */
fun Context.isInternetAvailable(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } else {
        @Suppress("DEPRECATION")
        val info = cm.activeNetworkInfo
        @Suppress("DEPRECATION")
        info != null && info.isConnected
    }
}
