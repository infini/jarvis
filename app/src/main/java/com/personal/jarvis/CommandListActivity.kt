package com.personal.jarvis

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class CommandListActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
    }

    private fun buildContentView(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
            setBackgroundColor(Color.rgb(246, 247, 249))
        }

        root.addView(
            TextView(this).apply {
                text = "명령어 리스트"
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(16, 20, 24))
                gravity = Gravity.CENTER_HORIZONTAL
            },
            matchWrap(bottomMargin = dp(8)),
        )
        root.addView(
            TextView(this).apply {
                text = "전원 버튼 길게 누르기 또는 Jarvis 명령 듣기 후 아래처럼 말합니다."
                textSize = 14f
                setTextColor(Color.rgb(76, 86, 96))
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, 0, dp(12))
            },
            matchWrap(bottomMargin = dp(12)),
        )

        CommandCatalog.entries
            .groupBy { it.category }
            .forEach { (category, entries) ->
                root.addView(categoryHeader(category), matchWrap(topMargin = dp(8), bottomMargin = dp(8)))
                entries.forEach { entry ->
                    root.addView(commandRow(entry), matchWrap(bottomMargin = dp(8)))
                }
            }

        return ScrollView(this).apply { addView(root) }
    }

    private fun categoryHeader(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(90, 100, 112))
        }
    }

    private fun commandRow(entry: CommandCatalog.Entry): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBackground(Color.WHITE)
            setOnClickListener { showCommandDetail(entry) }

            addView(
                TextView(context).apply {
                    text = entry.title
                    textSize = 17f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(16, 20, 24))
                },
                matchWrap(bottomMargin = dp(4)),
            )
            addView(
                TextView(context).apply {
                    text = entry.phrases.first()
                    textSize = 14f
                    setTextColor(Color.rgb(0, 122, 255))
                },
                matchWrap(bottomMargin = dp(4)),
            )
            addView(
                TextView(context).apply {
                    text = entry.summary
                    textSize = 14f
                    setTextColor(Color.rgb(76, 86, 96))
                },
                matchWrap(bottomMargin = 0),
            )
        }
    }

    private fun showCommandDetail(entry: CommandCatalog.Entry) {
        AlertDialog.Builder(this)
            .setTitle(entry.title)
            .setMessage(detailText(entry))
            .setPositiveButton("확인", null)
            .show()
    }

    private fun detailText(entry: CommandCatalog.Entry): String {
        return buildString {
            appendLine("예시")
            entry.phrases.forEach { appendLine("- $it") }
            appendLine()
            appendLine("동작")
            appendLine(entry.detail)
            appendLine()
            appendLine("필요 조건")
            entry.requirements.forEach { appendLine("- $it") }
            appendLine()
            appendLine("명령 ID")
            appendLine(entry.commandId)
            appendLine()
            appendLine("명령 대기 유지")
            appendLine(if (entry.keepsCommandWindowOpen) "처리 후 30초 명령 대기를 다시 엽니다." else "처리 후 현재 명령 대기를 닫습니다.")
            appendLine()
            appendLine("빠른 실행")
            append(if (entry.fastPartial) "partial STT 결과에서 먼저 잡히면 final 결과를 기다리지 않고 실행합니다." else "final STT 결과까지 기다릴 수 있습니다.")
        }
    }

    private fun roundedBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(8).toFloat()
        }
    }

    private fun matchWrap(
        topMargin: Int = 0,
        bottomMargin: Int = dp(10),
    ): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, topMargin, 0, bottomMargin) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
