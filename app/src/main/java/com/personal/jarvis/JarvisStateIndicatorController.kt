package com.personal.jarvis

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
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
        if (state == JarvisVoiceState.IDLE) {
            hide()
            return
        }

        val view = indicatorView ?: createIndicatorView().also {
            indicatorView = it
            windowManager.addView(it, layoutParams())
        }
        view.text = INDICATOR_TEXT
        view.setTextColor(Color.WHITE)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.argb(218, 8, 8, 10))
            setStroke(dp(1), Color.argb(48, 255, 255, 255))
        }
    }

    fun hide() {
        val view = indicatorView ?: return
        runCatching { windowManager.removeView(view) }
        indicatorView = null
    }

    fun dispose() {
        hide()
    }

    private fun createIndicatorView(): TextView {
        return TextView(service).apply {
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0f
            minWidth = dp(64)
            setPadding(dp(11), dp(5), dp(11), dp(5))
            gravity = Gravity.CENTER
            includeFontPadding = false
            elevation = dp(8).toFloat()
        }
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(8)
        }
    }

    private fun dp(value: Int): Int {
        return (value * service.resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val INDICATOR_TEXT = "JARVIS"
    }
}
