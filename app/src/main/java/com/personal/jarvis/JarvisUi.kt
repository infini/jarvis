package com.personal.jarvis

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object JarvisUi {
    val BACKGROUND: Int = Color.rgb(245, 247, 251)
    val SURFACE: Int = Color.WHITE
    val INK: Int = Color.rgb(18, 25, 38)
    val MUTED: Int = Color.rgb(93, 105, 124)
    val PRIMARY: Int = Color.rgb(28, 92, 230)
    val PRIMARY_DARK: Int = Color.rgb(12, 35, 74)
    val SUCCESS: Int = Color.rgb(8, 112, 66)
    val WARNING: Int = Color.rgb(145, 70, 0)
    val DANGER: Int = Color.rgb(205, 53, 68)
    val BORDER: Int = Color.rgb(222, 228, 238)
    val SOFT_BLUE: Int = Color.rgb(235, 241, 255)
    val SOFT_GREEN: Int = Color.rgb(232, 248, 240)
    val SOFT_AMBER: Int = Color.rgb(255, 246, 226)

    @Suppress("DEPRECATION")
    fun prepareWindow(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.setDecorFitsSystemWindows(false)
        }
        activity.window.statusBarColor = BACKGROUND
        activity.window.navigationBarColor = PRIMARY_DARK
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }

    fun applySystemBarPadding(view: View) {
        val initialLeft = view.paddingLeft
        val initialTop = view.paddingTop
        val initialRight = view.paddingRight
        val initialBottom = view.paddingBottom
        view.setOnApplyWindowInsetsListener { target, insets ->
            val left: Int
            val top: Int
            val right: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val safeInsets = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                left = safeInsets.left
                top = safeInsets.top
                right = safeInsets.right
                bottom = safeInsets.bottom
            } else {
                @Suppress("DEPRECATION")
                val systemLeft = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                val systemTop = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                val systemRight = insets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                val systemBottom = insets.systemWindowInsetBottom
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val cutout = insets.displayCutout
                    left = maxOf(systemLeft, cutout?.safeInsetLeft ?: 0)
                    top = maxOf(systemTop, cutout?.safeInsetTop ?: 0)
                    right = maxOf(systemRight, cutout?.safeInsetRight ?: 0)
                    bottom = maxOf(systemBottom, cutout?.safeInsetBottom ?: 0)
                } else {
                    left = systemLeft
                    top = systemTop
                    right = systemRight
                    bottom = systemBottom
                }
            }
            target.setPadding(
                initialLeft + left,
                initialTop + top,
                initialRight + right,
                initialBottom + bottom,
            )
            insets
        }
        view.requestApplyInsets()
    }

    fun card(context: Context, padding: Int = 18): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, padding), dp(context, padding), dp(context, padding), dp(context, padding))
            background = rounded(SURFACE, dp(context, 18).toFloat(), BORDER, dp(context, 1))
            elevation = dp(context, 2).toFloat()
        }
    }

    fun label(
        context: Context,
        text: String,
        size: Float,
        color: Int = INK,
        bold: Boolean = false,
    ): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setLineSpacing(0f, 1.12f)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }

    fun button(
        context: Context,
        text: String,
        primary: Boolean,
        onClick: () -> Unit,
    ): Button {
        val fill = if (primary) PRIMARY else SURFACE
        val textColor = if (primary) Color.WHITE else INK
        val stroke = if (primary) PRIMARY else BORDER
        return Button(context).apply {
            this.text = text
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            setAllCaps(false)
            gravity = Gravity.CENTER
            minHeight = dp(context, 54)
            stateListAnimator = null
            background = ripple(fill, stroke, context)
            setPadding(dp(context, 16), 0, dp(context, 16), 0)
            setOnClickListener { onClick() }
        }
    }

    fun statusPill(context: Context, text: String, color: Int, backgroundColor: Int): TextView {
        return label(context, text, 13f, color, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(context, 12), dp(context, 7), dp(context, 12), dp(context, 7))
            background = rounded(backgroundColor, dp(context, 99).toFloat())
        }
    }

    fun rounded(
        fill: Int,
        radius: Float,
        strokeColor: Int? = null,
        strokeWidth: Int = 0,
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            if (strokeColor != null && strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    fun ripple(fill: Int, stroke: Int, context: Context): RippleDrawable {
        val content = rounded(fill, dp(context, 14).toFloat(), stroke, dp(context, 1))
        return RippleDrawable(ColorStateList.valueOf(Color.argb(30, 30, 70, 150)), content, null)
    }

    fun matchWrap(
        context: Context,
        top: Int = 0,
        bottom: Int = 12,
    ): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, dp(context, top), 0, dp(context, bottom)) }
    }

    fun wrapWrap(context: Context): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
