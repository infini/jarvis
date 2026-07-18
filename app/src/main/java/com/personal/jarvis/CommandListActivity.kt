package com.personal.jarvis

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class CommandListActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var currentDialog: AlertDialog? = null
    private var contentScrollView: ScrollView? = null
    private val samplePanel by lazy {
        CommandVoiceSamplePanel(
            activity = this,
            handler = handler,
            onSamplesChanged = {
                if (!isFinishing && currentDialog?.isShowing != true) {
                    refreshContent()
                }
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JarvisUi.prepareWindow(this)
        showContent(preserveScroll = false)
    }

    override fun onStop() {
        samplePanel.stop()
        super.onStop()
    }

    override fun onDestroy() {
        samplePanel.stop()
        super.onDestroy()
    }

    private fun refreshContent() {
        if (!isFinishing && !isDestroyed) showContent(preserveScroll = true)
    }

    private fun showContent(preserveScroll: Boolean) {
        val scrollY = if (preserveScroll) contentScrollView?.scrollY ?: 0 else 0
        val content = buildContentView()
        contentScrollView = content
        setContentView(content)
        if (preserveScroll && scrollY > 0) content.post { content.scrollTo(0, scrollY) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        samplePanel.onRequestPermissionsResult(requestCode, grantResults)
    }

    private fun buildContentView(): ScrollView {
        val commandCount = CommandCatalog.entries.size
        val phraseCount = CommandCatalog.entries.sumOf { it.phrases.size }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
            setBackgroundColor(JarvisUi.BACKGROUND)
        }

        root.addView(
            JarvisUi.statusPill(this, "‹  메인", JarvisUi.INK, JarvisUi.SURFACE).apply {
                minHeight = dp(48)
                isClickable = true
                isFocusable = true
                contentDescription = "메인 화면으로 돌아가기"
                background = JarvisUi.ripple(JarvisUi.SURFACE, JarvisUi.BORDER, context)
                setOnClickListener { finish() }
            },
            matchWrap(bottomMargin = dp(18)),
        )

        root.addView(
            TextView(this).apply {
                text = getString(R.string.command_list_title)
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(16, 20, 24))
                gravity = Gravity.START
            },
            matchWrap(bottomMargin = dp(8)),
        )
        root.addView(
            TextView(this).apply {
                text = getString(R.string.command_list_summary, commandCount, phraseCount)
                textSize = 14f
                setTextColor(Color.rgb(76, 86, 96))
                gravity = Gravity.START
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

        return ScrollView(this).apply {
            addView(root)
            JarvisUi.applySystemBarPadding(this)
        }
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
            isFocusable = true
            contentDescription = "${entry.title}. ${entry.phrases.first()}. 상세 보기"
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = JarvisUi.ripple(JarvisUi.SURFACE, JarvisUi.BORDER, context)
            setOnClickListener { showCommandDetail(entry) }

            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(context).apply {
                            text = entry.title
                            textSize = 17f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.rgb(16, 20, 24))
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        TextView(context).apply {
                            text = "보기  ›"
                            textSize = 12f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(JarvisUi.PRIMARY)
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                },
                matchWrap(bottomMargin = dp(4)),
            )
            addView(
                TextView(context).apply {
                    text = "이렇게 말해 보세요"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(90, 100, 112))
                },
                matchWrap(bottomMargin = dp(2)),
            )
            addView(
                TextView(context).apply {
                    text = entry.phrases.first()
                    textSize = 14f
                    setTextColor(JarvisUi.PRIMARY)
                },
                matchWrap(bottomMargin = dp(4)),
            )
            addView(
                TextView(context).apply {
                    text = entry.summary
                    textSize = 14f
                    setTextColor(Color.rgb(76, 86, 96))
                },
                matchWrap(bottomMargin = dp(4)),
            )
            addView(
                TextView(context).apply {
                    val summary = CommandVoiceSampleStore.summary(context, entry.commandId)
                    text = sampleSummaryText(summary)
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (summary.count > 0) JarvisUi.SUCCESS else JarvisUi.MUTED)
                },
                matchWrap(bottomMargin = 0),
            )
        }
    }

    private fun showCommandDetail(entry: CommandCatalog.Entry) {
        currentDialog = AlertDialog.Builder(this)
            .setTitle(entry.title)
            .setView(samplePanel.build(entry, detailText(entry)))
            .setPositiveButton("닫기", null)
            .create()
            .apply {
                setOnDismissListener {
                    samplePanel.onDismiss(entry)
                    if (currentDialog === this) {
                        currentDialog = null
                        if (!isFinishing) refreshContent()
                    }
                }
                show()
            }
    }

    private fun detailText(entry: CommandCatalog.Entry): String {
        return buildString {
            appendLine("이렇게 말해 보세요")
            entry.phrases.forEach { appendLine("• $it") }
            appendLine()
            appendLine("Jarvis가 하는 일")
            appendLine(entry.detail)
            appendLine()
            appendLine("사용 전 확인")
            entry.requirements.forEach { appendLine("• $it") }
            if (entry.keepsCommandWindowOpen) {
                appendLine()
                append("실행 뒤에도 30초 동안 다음 명령을 이어서 말할 수 있습니다.")
            }
        }
    }

    private fun sampleSummaryText(summary: CommandVoiceSampleStore.Summary): String {
        return if (summary.count > 0) {
            "내 발음 ${summary.count}개 등록됨"
        } else {
            "내 발음으로 인식 개선 가능"
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
