package com.personal.jarvis

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

class JarvisStateIndicatorController(
    private val service: AccessibilityService,
) {
    private val windowManager by lazy {
        service.getSystemService(WindowManager::class.java)
    }
    private var indicatorView: HyperIslandIndicatorView? = null

    fun update(state: JarvisVoiceState) {
        Log.d(TAG, "state=$state")
        if (state == JarvisVoiceState.IDLE) {
            hide()
            return
        }

        val metrics = islandMetrics()
        val created = indicatorView == null
        val view = indicatorView ?: HyperIslandIndicatorView(service).also {
            indicatorView = it
            windowManager.addView(it, layoutParams(metrics))
        }
        view.applyState(state, metrics)
        if (!created) {
            runCatching { windowManager.updateViewLayout(view, layoutParams(metrics)) }
        }
        Log.d(
            TAG,
            "overlay_visible state=$state created=$created " +
                "width=${metrics.widthPx} height=${metrics.heightPx} " +
                "gap=${metrics.cameraGapPx} x=${metrics.horizontalOffsetPx} " +
                "y=${metrics.topOffsetPx} left=${metrics.leftLabelWidthPx} " +
                "right=${metrics.rightLabelWidthPx}",
        )
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

    private fun layoutParams(metrics: IslandMetrics): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            metrics.widthPx,
            metrics.heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = metrics.horizontalOffsetPx
            y = metrics.topOffsetPx
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun islandMetrics(): IslandMetrics {
        val screenWidth = screenWidth()
        val topInset = topSafeInset()
        val cutout = centeredCutoutBounds()
        val height = cutoutHeight(cutout, topInset)
        val cameraGap = ((cutout?.width() ?: 0) + dp(4)).coerceIn(dp(26), dp(48))
        val topOffset = if (topInset > height) {
            ((topInset - height) / 2).coerceAtLeast(0)
        } else {
            dp(2)
        }
        val horizontalPadding = dp(6)
        val textMetrics = fittedTextMetrics(
            cameraGapPx = cameraGap,
            horizontalPaddingPx = horizontalPadding,
            maxWidthPx = (screenWidth - dp(72)).coerceAtLeast(dp(164)),
        )
        val gapCenterInView = horizontalPadding + textMetrics.leftLabelWidthPx + cameraGap / 2f
        val horizontalOffset = (textMetrics.widthPx / 2f - gapCenterInView).roundToInt()

        return IslandMetrics(
            widthPx = textMetrics.widthPx,
            heightPx = height,
            cameraGapPx = cameraGap,
            horizontalPaddingPx = horizontalPadding,
            leftLabelWidthPx = textMetrics.leftLabelWidthPx,
            rightLabelWidthPx = textMetrics.rightLabelWidthPx,
            horizontalOffsetPx = horizontalOffset,
            topOffsetPx = topOffset,
            textSizePx = textMetrics.textSizePx,
        )
    }

    private fun cutoutHeight(cutout: Rect?, topInset: Int): Int {
        val cutoutHeight = cutout?.height()?.takeIf { it > 0 }
        if (cutoutHeight != null) {
            return cutoutHeight.coerceIn(dp(22), dp(30))
        }
        return if (topInset > 0) {
            (topInset - dp(12)).coerceIn(dp(22), dp(26))
        } else {
            dp(24)
        }
    }

    private fun fittedTextMetrics(
        cameraGapPx: Int,
        horizontalPaddingPx: Int,
        maxWidthPx: Int,
    ): TextMetrics {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val minTextPx = sp(7.5f)
        var candidate = sp(BASE_TEXT_SIZE_SP).coerceAtLeast(minTextPx)
        var metrics = labelMetrics(paint, candidate, cameraGapPx, horizontalPaddingPx)
        while (candidate > minTextPx) {
            if (metrics.widthPx <= maxWidthPx) return metrics
            candidate -= sp(0.5f)
            metrics = labelMetrics(paint, candidate, cameraGapPx, horizontalPaddingPx)
        }
        return metrics
    }

    private fun labelMetrics(
        paint: Paint,
        textSizePx: Float,
        cameraGapPx: Int,
        horizontalPaddingPx: Int,
    ): TextMetrics {
        paint.textSize = textSizePx
        val leftWidth = measuredLabelWidth(paint, LEFT_LABEL)
        val rightWidth = measuredLabelWidth(paint, RIGHT_READY_LABEL)
        return TextMetrics(
            widthPx = horizontalPaddingPx * 2 + leftWidth + cameraGapPx + rightWidth,
            leftLabelWidthPx = leftWidth,
            rightLabelWidthPx = rightWidth,
            textSizePx = textSizePx,
        )
    }

    private fun measuredLabelWidth(paint: Paint, label: String): Int {
        return (paint.measureText(label) * TEXT_LETTER_SPACING_WIDTH_FACTOR).roundToInt() + dp(2)
    }

    private fun centeredCutoutBounds(): Rect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val screenCenterX = screenWidth() / 2
        val displayCutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.windowInsets.displayCutout
        } else {
            null
        } ?: return null

        return displayCutout.boundingRects
            .filter { rect -> rect.width() > 0 && rect.height() > 0 }
            .minByOrNull { rect -> kotlin.math.abs(rect.centerX() - screenCenterX) }
    }

    private fun screenWidth(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            @Suppress("DEPRECATION")
            service.resources.displayMetrics.widthPixels
        }
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
        return (value * service.resources.displayMetrics.density).roundToInt()
    }

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            service.resources.displayMetrics,
        )
    }

    private data class IslandMetrics(
        val widthPx: Int,
        val heightPx: Int,
        val cameraGapPx: Int,
        val horizontalPaddingPx: Int,
        val leftLabelWidthPx: Int,
        val rightLabelWidthPx: Int,
        val horizontalOffsetPx: Int,
        val topOffsetPx: Int,
        val textSizePx: Float,
    )

    private data class TextMetrics(
        val widthPx: Int,
        val leftLabelWidthPx: Int,
        val rightLabelWidthPx: Int,
        val textSizePx: Float,
    )

    private class HyperIslandIndicatorView(
        service: AccessibilityService,
    ) : LinearLayout(service) {
        private val leftLabel = islandLabel(service, LEFT_LABEL)
        private val cameraGap = View(service)
        private val rightLabel = islandLabel(service, RIGHT_READY_LABEL)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            isBaselineAligned = false
            elevation = dp(service, 12).toFloat()
            addView(
                leftLabel,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
            )
            addView(cameraGap, LayoutParams(dp(service, 42), LayoutParams.MATCH_PARENT))
            addView(
                rightLabel,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
            )
        }

        fun applyState(state: JarvisVoiceState, metrics: IslandMetrics) {
            setPadding(metrics.horizontalPaddingPx, 0, metrics.horizontalPaddingPx, 0)
            background = islandBackground(metrics.heightPx)

            cameraGap.layoutParams = cameraGap.layoutParams.apply {
                width = metrics.cameraGapPx
                height = LayoutParams.MATCH_PARENT
            }
            leftLabel.layoutParams = leftLabel.layoutParams.apply {
                width = metrics.leftLabelWidthPx
                height = LayoutParams.MATCH_PARENT
            }
            rightLabel.layoutParams = rightLabel.layoutParams.apply {
                width = metrics.rightLabelWidthPx
                height = LayoutParams.MATCH_PARENT
            }

            leftLabel.text = LEFT_LABEL
            rightLabel.text = statusTextFor(state)
            listOf(leftLabel, rightLabel).forEach { label ->
                label.setTextSize(TypedValue.COMPLEX_UNIT_PX, metrics.textSizePx)
            }
            leftLabel.setTextColor(Color.rgb(100, 210, 255))
            rightLabel.setTextColor(statusColorFor(state))
        }

        private fun islandBackground(heightPx: Int): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = heightPx / 2f
                setColor(Color.argb(240, 0, 0, 0))
                setStroke(dp(context, 1), Color.argb(38, 255, 255, 255))
            }
        }

        private fun statusTextFor(state: JarvisVoiceState): String {
            return when (state) {
                JarvisVoiceState.COMMAND_READY -> RIGHT_READY_LABEL
                JarvisVoiceState.COMMAND_PROCESSING -> "WORKING"
                JarvisVoiceState.COMMAND_HANDLED -> "DONE"
                JarvisVoiceState.COMMAND_FAILED -> "FAILED"
                JarvisVoiceState.IDLE -> ""
            }
        }

        private fun statusColorFor(state: JarvisVoiceState): Int {
            return when (state) {
                JarvisVoiceState.COMMAND_READY -> Color.rgb(48, 209, 88)
                JarvisVoiceState.COMMAND_PROCESSING -> Color.rgb(255, 159, 10)
                JarvisVoiceState.COMMAND_HANDLED -> Color.rgb(10, 132, 255)
                JarvisVoiceState.COMMAND_FAILED -> Color.rgb(255, 69, 58)
                JarvisVoiceState.IDLE -> Color.TRANSPARENT
            }
        }

        private companion object {
            private fun islandLabel(service: AccessibilityService, text: String): TextView {
                return TextView(service).apply {
                    this.text = text
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    letterSpacing = TEXT_LETTER_SPACING
                    maxLines = 1
                }
            }

            private fun dp(viewContext: android.content.Context, value: Int): Int {
                return (value * viewContext.resources.displayMetrics.density).roundToInt()
            }
        }
    }

    companion object {
        private const val TAG = "JarvisStateIndicator"
        private const val LEFT_LABEL = "JARVIS"
        private const val RIGHT_READY_LABEL = "LISTENING"
        private const val BASE_TEXT_SIZE_SP = 10.5f
        private const val TEXT_LETTER_SPACING = 0.08f
        private const val TEXT_LETTER_SPACING_WIDTH_FACTOR = 1.1f
    }
}
