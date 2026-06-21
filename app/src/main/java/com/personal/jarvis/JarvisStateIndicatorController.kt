package com.personal.jarvis

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView

class JarvisStateIndicatorController(
    private val service: AccessibilityService,
) {
    private val windowManager by lazy {
        service.getSystemService(WindowManager::class.java)
    }
    private var indicatorView: TextView? = null

    fun update(state: JarvisVoiceState) {
        Log.d(TAG, "state=$state")
        if (state == JarvisVoiceState.IDLE) {
            hide()
            return
        }

        val created = indicatorView == null
        val view = indicatorView ?: createIndicatorView().also {
            indicatorView = it
            windowManager.addView(it, layoutParams())
        }
        view.text = INDICATOR_TEXT
        view.setTextColor(Color.WHITE)
        view.setCompoundDrawables(dotDrawable(dotColorFor(state)), null, null, null)
        view.background = islandBackground()
        Log.d(TAG, "overlay_visible state=$state created=$created")
    }

    fun hide() {
        val view = indicatorView
        if (view == null) {
            Log.d(TAG, "overlay_hidden alreadyHidden=true")
            return
        }
        runCatching { windowManager.removeView(view) }
        indicatorView = null
        Log.d(TAG, "overlay_hidden alreadyHidden=false")
    }

    fun dispose() {
        hide()
    }

    private fun createIndicatorView(): TextView {
        return TextView(service).apply {
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.08f
            minWidth = dp(78)
            minHeight = dp(25)
            compoundDrawablePadding = dp(7)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            gravity = Gravity.CENTER
            includeFontPadding = false
            elevation = dp(10).toFloat()
        }
    }

    private fun islandBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(Color.argb(234, 3, 3, 5))
            setStroke(dp(1), Color.argb(34, 255, 255, 255))
        }
    }

    private fun dotDrawable(color: Int): GradientDrawable {
        val size = dp(5)
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setSize(size, size)
            setBounds(0, 0, size, size)
        }
    }

    private fun dotColorFor(state: JarvisVoiceState): Int {
        return when (state) {
            JarvisVoiceState.COMMAND_READY -> Color.rgb(48, 209, 88)
            JarvisVoiceState.COMMAND_PROCESSING -> Color.rgb(255, 159, 10)
            JarvisVoiceState.COMMAND_HANDLED -> Color.rgb(10, 132, 255)
            JarvisVoiceState.COMMAND_FAILED -> Color.rgb(255, 69, 58)
            JarvisVoiceState.IDLE -> Color.TRANSPARENT
        }
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = overlayTopOffset()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
        }
    }

    private fun overlayTopOffset(): Int {
        return topSafeInset() + dp(7)
    }

    private fun topSafeInset(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.currentWindowMetrics.windowInsets
            val statusBarInset = insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars(),
            ).top
            val cutoutInset = insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.displayCutout(),
            ).top
            return maxOf(statusBarInset, cutoutInset)
        }
        return statusBarHeight()
    }

    private fun statusBarHeight(): Int {
        val resourceId = service.resources.getIdentifier(
            "status_bar_height",
            "dimen",
            "android",
        )
        return if (resourceId > 0) {
            service.resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
    }

    private fun dp(value: Int): Int {
        return (value * service.resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val TAG = "JarvisStateIndicator"
        private const val INDICATOR_TEXT = "JARVIS"
    }
}
