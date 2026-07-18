package com.personal.jarvis.debug

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.personal.jarvis.CameraLauncher
import com.personal.jarvis.CommandBus
import com.personal.jarvis.JarvisUi
import com.personal.jarvis.JarvisVoiceService
import com.personal.jarvis.ScreenController

class JarvisDeveloperMenuActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JarvisUi.prepareWindow(this)
        setContentView(buildContentView())
    }

    private fun buildContentView(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
            setBackgroundColor(JarvisUi.BACKGROUND)
        }

        root.addView(backButton(), JarvisUi.matchWrap(this, bottom = 18))
        root.addView(
            JarvisUi.label(this, "개발자 메뉴", 28f, JarvisUi.INK, bold = true),
            JarvisUi.matchWrap(this, bottom = 6),
        )
        root.addView(
            JarvisUi.label(this, "디버그 APK에서만 제공되는 기능 테스트와 진단 도구입니다.", 15f, JarvisUi.MUTED),
            JarvisUi.matchWrap(this, bottom = 16),
        )
        root.addView(debugWarning(), JarvisUi.matchWrap(this, bottom = 24))

        root.addView(sectionTitle("명령 듣기"), JarvisUi.matchWrap(this, bottom = 10))
        root.addView(
            actionCard(
                "5초 명령 창 열기",
                "실제 음성 인식 경로와 상태 표시를 짧게 확인합니다.",
            ) {
                startActivity(
                    Intent(this, JarvisDebugCommandWindowActivity::class.java)
                        .putExtra(JarvisDebugCommandWindowActivity.EXTRA_WINDOW_MS, 5_000L)
                        .putExtra(JarvisDebugCommandWindowActivity.EXTRA_REQUEST_ID, "developer_menu"),
                )
            },
            JarvisUi.matchWrap(this, bottom = 12),
        )
        root.addView(
            actionCard("음성 서비스 중지", "열린 명령 창과 포그라운드 서비스를 종료합니다.") {
                stopService(Intent(this, JarvisVoiceService::class.java))
                Toast.makeText(this, "음성 서비스를 중지했습니다.", Toast.LENGTH_SHORT).show()
            },
            JarvisUi.matchWrap(this, bottom = 24),
        )

        root.addView(sectionTitle("카메라 자동화 테스트"), JarvisUi.matchWrap(this, bottom = 10))
        root.addView(commandGrid(), JarvisUi.matchWrap(this, bottom = 24))

        root.addView(sectionTitle("진단"), JarvisUi.matchWrap(this, bottom = 10))
        root.addView(
            actionCard("프로필 상태 로그", "등록 상태와 embedding 수를 logcat에 기록합니다.") {
                startActivity(
                    Intent(this, JarvisDebugProfileStatusActivity::class.java)
                        .putExtra(JarvisDebugProfileStatusActivity.EXTRA_REQUEST_ID, "developer_menu"),
                )
                Toast.makeText(this, "JarvisDebugStatus 로그를 확인하세요.", Toast.LENGTH_SHORT).show()
            },
            JarvisUi.matchWrap(this, bottom = 12),
        )
        root.addView(
            actionCard("Activation 캡처 재생", "저장된 activation 진단 캡처를 다시 분석합니다.") {
                startActivity(
                    Intent(this, JarvisDebugActivationReplayActivity::class.java)
                        .putExtra(JarvisDebugActivationReplayActivity.EXTRA_REQUEST_ID, "developer_menu"),
                )
                Toast.makeText(this, "JarvisDebugReplay 로그를 확인하세요.", Toast.LENGTH_SHORT).show()
            },
            JarvisUi.matchWrap(this, bottom = 12),
        )
        root.addView(
            actionCard("명령 캡처 재생", "저장된 local ASR/샘플 매칭 캡처를 다시 분석합니다.") {
                startActivity(
                    Intent(this, JarvisDebugCommandReplayActivity::class.java)
                        .putExtra(JarvisDebugCommandReplayActivity.EXTRA_REQUEST_ID, "developer_menu"),
                )
                Toast.makeText(this, "JarvisDebugCommandReplay 로그를 확인하세요.", Toast.LENGTH_SHORT).show()
            },
            JarvisUi.matchWrap(this, bottom = 0),
        )

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
            JarvisUi.applySystemBarPadding(this)
        }
    }

    private fun backButton(): TextView {
        return JarvisUi.statusPill(this, "‹  메인", JarvisUi.INK, JarvisUi.SURFACE).apply {
            minHeight = dp(48)
            isClickable = true
            isFocusable = true
            background = JarvisUi.ripple(JarvisUi.SURFACE, JarvisUi.BORDER, context)
            setOnClickListener { finish() }
        }
    }

    private fun debugWarning(): LinearLayout {
        return JarvisUi.card(this).apply {
            background = JarvisUi.rounded(JarvisUi.SOFT_AMBER, dp(16).toFloat())
            addView(JarvisUi.label(context, "DEBUG BUILD", 13f, JarvisUi.WARNING, bold = true))
            addView(
                JarvisUi.label(
                    context,
                    "테스트 명령은 즉시 카메라·셔터·화면을 제어할 수 있습니다. 의도한 기기에서만 사용하세요.",
                    14f,
                    JarvisUi.MUTED,
                ),
                JarvisUi.matchWrap(context, top = 7, bottom = 0),
            )
        }
    }

    private fun commandGrid(): LinearLayout {
        return JarvisUi.card(this).apply {
            addView(testButton("기본 카메라 열기") { CameraLauncher.open(this@JarvisDeveloperMenuActivity) }, JarvisUi.matchWrap(context, bottom = 8))
            addView(testButton("전면 카메라") { send(CommandBus.COMMAND_OPEN_FRONT_CAMERA) }, JarvisUi.matchWrap(context, bottom = 8))
            addView(testButton("후면 카메라") { send(CommandBus.COMMAND_OPEN_REAR_CAMERA) }, JarvisUi.matchWrap(context, bottom = 8))
            addView(testButton("셔터 누르기") { send(CommandBus.COMMAND_TAKE_PHOTO) }, JarvisUi.matchWrap(context, bottom = 8))
            addView(testButton("화면 켜기") { ScreenController.wake(this@JarvisDeveloperMenuActivity) }, JarvisUi.matchWrap(context, bottom = 8))
            addView(testButton("화면 끄기") { send(CommandBus.COMMAND_SLEEP_SCREEN) }.apply {
                setTextColor(JarvisUi.DANGER)
            }, JarvisUi.matchWrap(context, bottom = 0))
        }
    }

    private fun testButton(label: String, action: () -> Unit) =
        JarvisUi.button(this, label, primary = false, onClick = action)

    private fun send(command: String) {
        CommandBus.send(command)
        Toast.makeText(this, "테스트 명령 전송: $command", Toast.LENGTH_SHORT).show()
    }

    private fun actionCard(title: String, description: String, action: () -> Unit): LinearLayout {
        return JarvisUi.card(this).apply {
            isClickable = true
            isFocusable = true
            background = JarvisUi.ripple(JarvisUi.SURFACE, JarvisUi.BORDER, context)
            setOnClickListener { action() }
            addView(JarvisUi.label(context, "$title  ›", 16f, JarvisUi.INK, bold = true))
            addView(
                JarvisUi.label(context, description, 13f, JarvisUi.MUTED),
                JarvisUi.matchWrap(context, top = 5, bottom = 0),
            )
        }
    }

    private fun sectionTitle(text: String): TextView =
        JarvisUi.label(this, text, 17f, JarvisUi.INK, bold = true)

    private fun dp(value: Int): Int = JarvisUi.dp(this, value)
}
