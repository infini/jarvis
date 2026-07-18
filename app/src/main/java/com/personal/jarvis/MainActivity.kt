package com.personal.jarvis

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var heroStatus: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroDescription: TextView
    private lateinit var primaryButton: Button
    private lateinit var stopButton: Button
    private lateinit var setupHeadline: TextView
    private lateinit var setupDescription: TextView
    private lateinit var noticeView: TextView

    private var latestVoiceState = JarvisVoiceState.IDLE
    private var persistentNoticeCode: String? = null
    private val stateListener = JarvisStateBus.Listener { state ->
        latestVoiceState = state
        runOnUiThread(::render)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JarvisUi.prepareWindow(this)
        persistentNoticeCode = intent?.getStringExtra(EXTRA_NOTICE_CODE)
        setContentView(buildContentView())
        render()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        persistentNoticeCode = intent?.getStringExtra(EXTRA_NOTICE_CODE)
        render()
    }

    override fun onStart() {
        super.onStart()
        JarvisStateBus.addListener(stateListener)
    }

    override fun onResume() {
        super.onResume()
        latestVoiceState = if (JarvisVoiceService.isRunning) {
            JarvisStateBus.current()
        } else {
            JarvisVoiceState.IDLE
        }
        render()
    }

    override fun onStop() {
        JarvisStateBus.removeListener(stateListener)
        super.onStop()
    }

    private fun buildContentView(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(32))
            setBackgroundColor(JarvisUi.BACKGROUND)
        }

        root.addView(buildAppHeader(), JarvisUi.matchWrap(this, bottom = 20))
        root.addView(buildHeroCard(), JarvisUi.matchWrap(this, bottom = 16))

        noticeView = JarvisUi.label(this, "", 14f, JarvisUi.DANGER, bold = true).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = JarvisUi.rounded(Color.rgb(255, 239, 241), dp(14).toFloat())
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            isClickable = true
            isFocusable = true
            setOnClickListener { consumeNoticeAndOpenSetup() }
            visibility = View.GONE
        }
        root.addView(noticeView, JarvisUi.matchWrap(this, bottom = 16))

        root.addView(buildSetupCard(), JarvisUi.matchWrap(this, bottom = 22))
        root.addView(sectionTitle("이렇게 말해 보세요"), JarvisUi.matchWrap(this, bottom = 10))
        root.addView(buildCommandSuggestions(), JarvisUi.matchWrap(this, bottom = 22))
        root.addView(sectionTitle("빠른 메뉴"), JarvisUi.matchWrap(this, bottom = 10))
        root.addView(buildQuickMenu(), JarvisUi.matchWrap(this, bottom = 18))

        if (isDebuggableApp()) {
            root.addView(
                JarvisUi.button(this, "개발자 메뉴", primary = false) { openDeveloperMenu() },
                JarvisUi.matchWrap(this, bottom = 16),
            )
        }

        root.addView(
            JarvisUi.label(
                this,
                "내 목소리 프로필과 맞춤 음성 샘플은 이 기기의 앱 전용 저장공간에 보관됩니다.",
                12f,
                JarvisUi.MUTED,
            ).apply { gravity = Gravity.CENTER_HORIZONTAL },
            JarvisUi.matchWrap(this, bottom = 0),
        )

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
            JarvisUi.applySystemBarPadding(this)
        }
    }

    private fun buildAppHeader(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                JarvisUi.label(context, "JARVIS", 18f, JarvisUi.PRIMARY_DARK, bold = true).apply {
                    letterSpacing = 0.12f
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                JarvisUi.statusPill(context, "VOICE ASSISTANT", JarvisUi.PRIMARY, JarvisUi.SOFT_BLUE),
                JarvisUi.wrapWrap(context),
            )
        }
    }

    private fun buildHeroCard(): LinearLayout {
        return JarvisUi.card(this, padding = 22).apply {
            background = JarvisUi.rounded(JarvisUi.PRIMARY_DARK, dp(24).toFloat())
            elevation = dp(5).toFloat()

            heroStatus = JarvisUi.statusPill(
                context,
                "준비 상태 확인 중",
                Color.WHITE,
                Color.rgb(38, 68, 113),
            ).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
            addView(heroStatus, JarvisUi.wrapWrap(context))

            heroTitle = JarvisUi.label(context, "무엇을 도와드릴까요?", 28f, Color.WHITE, bold = true).apply {
                setPadding(0, dp(18), 0, 0)
            }
            addView(heroTitle, JarvisUi.matchWrap(context, bottom = 8))

            heroDescription = JarvisUi.label(context, "", 15f, Color.rgb(205, 217, 237)).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            addView(heroDescription, JarvisUi.matchWrap(context, bottom = 20))

            primaryButton = JarvisUi.button(context, "Jarvis에게 말하기", primary = true) {
                handlePrimaryAction()
            }
            addView(primaryButton, JarvisUi.matchWrap(context, bottom = 10))

            stopButton = JarvisUi.button(context, "듣기 중지", primary = false) { stopListening() }.apply {
                visibility = View.GONE
            }
            addView(stopButton, JarvisUi.matchWrap(context, bottom = 0))
        }
    }

    private fun buildSetupCard(): LinearLayout {
        return JarvisUi.card(this).apply {
            isClickable = true
            isFocusable = true
            background = JarvisUi.ripple(JarvisUi.SURFACE, JarvisUi.BORDER, context)
            setOnClickListener { openSetup() }

            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setupHeadline = JarvisUi.label(context, "시작하기", 17f, JarvisUi.INK, bold = true)
                    addView(
                        setupHeadline,
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(JarvisUi.label(context, "설정  ›", 14f, JarvisUi.PRIMARY, bold = true))
                },
                JarvisUi.matchWrap(context, bottom = 7),
            )
            setupDescription = JarvisUi.label(context, "", 14f, JarvisUi.MUTED)
            addView(setupDescription, JarvisUi.matchWrap(context, bottom = 0))
        }
    }

    private fun buildCommandSuggestions(): LinearLayout {
        return JarvisUi.card(this, padding = 0).apply {
            addView(suggestionRow("카메라 열기", "자비스 카메라 실행", showDivider = true))
            addView(suggestionRow("사진 찍기", "자비스 사진 찍어", showDivider = true))
            addView(suggestionRow("화면 끄기", "자비스 화면 꺼", showDivider = false))
        }
    }

    private fun suggestionRow(title: String, phrase: String, showDivider: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(15), dp(18), if (showDivider) 0 else dp(15))
            addView(JarvisUi.label(context, title, 13f, JarvisUi.MUTED, bold = true))
            addView(
                JarvisUi.label(context, "“$phrase”", 16f, JarvisUi.INK, bold = true),
                JarvisUi.matchWrap(context, top = 3, bottom = if (showDivider) 13 else 0),
            )
            if (showDivider) {
                addView(View(context).apply { setBackgroundColor(JarvisUi.BORDER) }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1),
                ))
            }
        }
    }

    private fun buildQuickMenu(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val commandButton = JarvisUi.button(context, "지원 명령", primary = false) { openCommandList() }
            val setupButton = JarvisUi.button(context, "설정 관리", primary = false) { openSetup() }
            addView(commandButton, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(6) })
            addView(setupButton, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(6) })
        }
    }

    private fun sectionTitle(text: String): TextView =
        JarvisUi.label(this, text, 17f, JarvisUi.INK, bold = true)

    private fun render() {
        if (!::heroStatus.isInitialized) return
        val setup = JarvisSetupStatus.capture(this)
        val serviceRunning = JarvisVoiceService.isRunning

        when {
            !setup.canListen -> renderSetupRequired(setup)
            serviceRunning -> renderVoiceState(latestVoiceState)
            else -> renderReady()
        }

        setupHeadline.text = if (setup.canListen) "Jarvis 설정" else "시작하기"
        setupDescription.text = setupSummary(setup)
        stopButton.visibility = if (serviceRunning) View.VISIBLE else View.GONE

        if (noticeResolved(persistentNoticeCode, setup)) clearNotice()
        val notice = noticeMessage(persistentNoticeCode)
        noticeView.text = if (notice.isNullOrBlank()) "" else "$notice\n설정 관리에서 확인하기  ›"
        noticeView.visibility = if (notice.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun renderSetupRequired(setup: JarvisSetupStatus) {
        heroStatus.text = getString(
            R.string.main_setup_progress,
            setup.completedRequiredSteps,
            setup.requiredStepCount,
        )
        heroStatus.setTextColor(Color.rgb(255, 221, 156))
        heroStatus.background = JarvisUi.rounded(Color.rgb(89, 67, 35), dp(99).toFloat())
        heroTitle.setText(R.string.main_setup_title)
        heroDescription.text = when (setup.nextRequiredStep) {
            JarvisSetupStatus.RequiredStep.MICROPHONE -> "먼저 마이크 사용을 허용해 주세요. 음성 명령을 듣는 데 필요합니다."
            JarvisSetupStatus.RequiredStep.OWNER_VOICE -> "내 목소리를 등록하면 다른 사람의 명령으로 실행되는 일을 줄일 수 있습니다."
            JarvisSetupStatus.RequiredStep.ACCESSIBILITY -> "접근성을 연결하면 카메라와 화면 명령을 안전하게 실행할 수 있습니다."
            null -> "필수 설정을 확인해 주세요."
        }
        primaryButton.text = "설정 계속하기"
    }

    private fun renderReady() {
        heroStatus.text = "사용 준비 완료"
        heroStatus.setTextColor(Color.rgb(151, 244, 194))
        heroStatus.background = JarvisUi.rounded(Color.rgb(25, 78, 74), dp(99).toFloat())
        heroTitle.text = "무엇을 도와드릴까요?"
        heroDescription.setText(R.string.main_ready_description)
        primaryButton.setText(R.string.main_listen_action)
    }

    private fun renderVoiceState(state: JarvisVoiceState) {
        val presentation = when (state) {
            JarvisVoiceState.COMMAND_READY -> VoicePresentation("듣는 중", "지금 말씀해 주세요", "‘자비스 사진 찍어’처럼 말하면 바로 실행합니다.", Color.rgb(151, 244, 194))
            JarvisVoiceState.COMMAND_PROCESSING -> VoicePresentation("처리 중", "명령을 확인하고 있어요", "잠시만 기다려 주세요.", Color.rgb(255, 214, 137))
            JarvisVoiceState.COMMAND_HANDLED -> VoicePresentation("완료", "명령 전달을 마쳤어요", "계속 듣는 중이면 다음 명령도 이어서 말할 수 있습니다.", Color.rgb(155, 205, 255))
            JarvisVoiceState.COMMAND_FAILED -> VoicePresentation("다시 말해 주세요", "명령을 이해하지 못했어요", "‘자비스’와 동작을 또렷하게 이어서 말해 주세요.", Color.rgb(255, 167, 174))
            JarvisVoiceState.IDLE -> VoicePresentation("준비 중", "Jarvis를 깨우고 있어요", "곧 명령을 들을 준비가 됩니다.", Color.rgb(205, 217, 237))
        }
        heroStatus.text = presentation.status
        heroStatus.setTextColor(presentation.statusColor)
        heroStatus.background = JarvisUi.rounded(Color.rgb(38, 68, 113), dp(99).toFloat())
        heroTitle.text = presentation.title
        heroDescription.text = presentation.description
        primaryButton.setText(R.string.main_extend_action)
    }

    private fun setupSummary(setup: JarvisSetupStatus): String {
        if (!setup.canListen) {
            return "필수 설정 ${setup.remainingRequiredSteps}개가 남았습니다. 항목별 안내를 따라 완료해 주세요."
        }
        val recommendations = buildList {
            if (!setup.quickLaunchConfigured) add("전원 버튼 빠른 호출")
            if (!setup.notificationsGranted) add("알림")
            if (!setup.batteryOptimizationDisabled) add("배터리 예외")
        }
        return if (recommendations.isEmpty()) {
            "필수 설정과 안정적인 실행을 위한 권장 설정이 모두 완료됐습니다."
        } else {
            "필수 설정 완료 · 권장: ${recommendations.joinToString(", ")}"
        }
    }

    private fun handlePrimaryAction() {
        clearNotice()
        val setup = JarvisSetupStatus.capture(this)
        if (!setup.canListen) {
            openSetup()
            return
        }

        if (!JarvisVoiceServiceStarter.openCommandWindow(this, "main_activity")) {
            persistentNoticeCode = NOTICE_START_FAILED
            render()
            return
        }

        latestVoiceState = JarvisVoiceState.COMMAND_READY
        render()
    }

    private fun stopListening() {
        stopService(Intent(this, JarvisVoiceService::class.java))
        latestVoiceState = JarvisVoiceState.IDLE
        Toast.makeText(this, "Jarvis 듣기를 중지했습니다.", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun openSetup() {
        startActivity(Intent(this, SetupActivity::class.java))
    }

    private fun consumeNoticeAndOpenSetup() {
        clearNotice()
        openSetup()
    }

    private fun clearNotice() {
        persistentNoticeCode = null
        intent?.removeExtra(EXTRA_NOTICE_CODE)
    }

    private fun noticeMessage(code: String?): String? = when (code) {
        NOTICE_MICROPHONE_REQUIRED -> "Jarvis를 호출하려면 마이크 권한이 필요합니다."
        NOTICE_ACCESSIBILITY_REQUIRED -> "카메라와 화면 명령을 사용하려면 접근성 연결이 필요합니다."
        NOTICE_OWNER_VOICE_REQUIRED -> "Jarvis를 사용하려면 내 목소리를 먼저 등록해 주세요."
        NOTICE_START_FAILED -> "Jarvis를 시작하지 못했습니다. 권한과 배터리 설정을 확인해 주세요."
        else -> null
    }

    private fun noticeResolved(code: String?, setup: JarvisSetupStatus): Boolean = when (code) {
        NOTICE_MICROPHONE_REQUIRED -> setup.microphoneGranted
        NOTICE_ACCESSIBILITY_REQUIRED -> setup.accessibilityReady
        NOTICE_OWNER_VOICE_REQUIRED -> setup.ownerVoiceConfigured
        else -> false
    }

    private fun openCommandList() {
        startActivity(Intent(this, CommandListActivity::class.java))
    }

    private fun openDeveloperMenu() {
        runCatching {
            startActivity(
                Intent().setClassName(packageName, "$packageName.debug.JarvisDeveloperMenuActivity"),
            )
        }.onFailure {
            Toast.makeText(this, "개발자 메뉴를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isDebuggableApp(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun dp(value: Int): Int = JarvisUi.dp(this, value)

    private data class VoicePresentation(
        val status: String,
        val title: String,
        val description: String,
        val statusColor: Int,
    )

    companion object {
        const val EXTRA_NOTICE_CODE = "main_notice_code"
        const val NOTICE_MICROPHONE_REQUIRED = "microphone_required"
        const val NOTICE_ACCESSIBILITY_REQUIRED = "accessibility_required"
        const val NOTICE_OWNER_VOICE_REQUIRED = "owner_voice_required"
        const val NOTICE_START_FAILED = "start_failed"
    }
}
