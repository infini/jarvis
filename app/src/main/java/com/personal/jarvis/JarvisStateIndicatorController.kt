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
        val style = styleFor(state)
        view.text = style.text
        view.setTextColor(style.textColor)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(style.backgroundColor)
            setStroke(dp(1), style.strokeColor)
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
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(8), dp(14), dp(8))
            gravity = Gravity.CENTER
            includeFontPadding = false
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
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(56)
        }
    }

    private fun styleFor(state: JarvisVoiceState): IndicatorStyle {
        return when (state) {
            JarvisVoiceState.COMMAND_READY -> IndicatorStyle(
                text = "JARVIS 듣는 중",
                backgroundColor = Color.argb(232, 7, 112, 72),
                strokeColor = Color.argb(230, 118, 255, 198),
                textColor = Color.WHITE,
            )
            JarvisVoiceState.COMMAND_PROCESSING -> IndicatorStyle(
                text = "JARVIS 처리 중",
                backgroundColor = Color.argb(232, 133, 83, 10),
                strokeColor = Color.argb(230, 255, 207, 128),
                textColor = Color.WHITE,
            )
            JarvisVoiceState.COMMAND_HANDLED -> IndicatorStyle(
                text = "JARVIS · 다음 명령 가능",
                backgroundColor = Color.argb(232, 24, 91, 145),
                strokeColor = Color.argb(230, 139, 209, 255),
                textColor = Color.WHITE,
            )
            JarvisVoiceState.COMMAND_FAILED -> IndicatorStyle(
                text = "JARVIS · 다시 말하세요",
                backgroundColor = Color.argb(235, 156, 39, 49),
                strokeColor = Color.argb(230, 255, 166, 174),
                textColor = Color.WHITE,
            )
            JarvisVoiceState.IDLE -> IndicatorStyle(
                text = "",
                backgroundColor = Color.TRANSPARENT,
                strokeColor = Color.TRANSPARENT,
                textColor = Color.TRANSPARENT,
            )
        }
    }

    private fun dp(value: Int): Int {
        return (value * service.resources.displayMetrics.density).toInt()
    }

    private data class IndicatorStyle(
        val text: String,
        val backgroundColor: Int,
        val strokeColor: Int,
        val textColor: Int,
    )
}
