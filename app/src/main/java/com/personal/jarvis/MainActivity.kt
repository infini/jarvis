package com.personal.jarvis

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var ownerVoiceStatusView: TextView
    private lateinit var enrollProgress: ProgressBar
    private lateinit var enrollButton: Button
    private val ownerVoiceEnrollmentController by lazy {
        OwnerVoiceEnrollmentController(
            context = applicationContext,
            postToMain = { action -> runOnUiThread(action) },
            onProgress = { percent -> enrollProgress.progress = percent },
            onStatus = { status -> ownerVoiceStatusView.text = status },
            onCompleted = { embeddingCount ->
                stopOwnerEnrollment("내 목소리 등록 완료: ${embeddingCount}개 음성 특징 저장됨")
                updateStatus()
                restartJarvisAfterOwnerEnrollment(embeddingCount)
            },
            onFailed = { message ->
                showOwnerVoiceError(message)
                stopOwnerEnrollment()
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        stopOwnerEnrollment()
        super.onDestroy()
    }

    private fun buildContentView(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
            setBackgroundColor(Color.rgb(246, 247, 249))
        }

        val title = TextView(this).apply {
            text = "JARVIS"
            textSize = 32f
            setTextColor(Color.rgb(16, 20, 24))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(title, matchWrap())

        val subtitle = TextView(this).apply {
            text = "개인 Android 비서 MVP"
            textSize = 16f
            setTextColor(Color.rgb(68, 76, 86))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(24))
        }
        root.addView(subtitle, matchWrap())

        statusView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(32, 38, 44))
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(statusView, matchWrap(bottomMargin = dp(18)))

        ownerVoiceStatusView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(32, 38, 44))
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(ownerVoiceStatusView, matchWrap(bottomMargin = dp(18)))

        enrollProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        root.addView(enrollProgress, matchWrap())

        enrollButton = button("내 목소리 등록 시작") { toggleOwnerEnrollment() }
        root.addView(enrollButton, matchWrap())
        root.addView(button("내 목소리 등록 삭제") { clearOwnerVoiceProfile() })

        root.addView(button("마이크/알림 권한 요청") { requestRuntimePermissions() })
        root.addView(button("접근성 설정 열기") { openAccessibilitySettings() })
        root.addView(button("기본 어시스턴트 설정") { openAssistantSettings() })
        root.addView(button("배터리 최적화 설정 열기") { openBatteryOptimizationSettings() })
        root.addView(button("앱 자동 시작/배터리 설정 열기") { openAppSettings() })
        root.addView(button("Jarvis 명령 듣기") { startJarvisCommandWindow() })
        root.addView(button("명령어 리스트") { openCommandList() })
        root.addView(button("테스트: 카메라 열기") { CameraLauncher.open(this) })
        root.addView(button("테스트: 셀피 카메라 열기") { CommandBus.send(this, CommandBus.COMMAND_OPEN_FRONT_CAMERA) })
        root.addView(button("테스트: 후면 카메라 열기") { CommandBus.send(this, CommandBus.COMMAND_OPEN_REAR_CAMERA) })
        root.addView(button("테스트: 셔터 누르기") { CommandBus.send(this, CommandBus.COMMAND_TAKE_PHOTO) })
        root.addView(button("테스트: 화면 켜기") { ScreenController.wake(this) })
        root.addView(button("테스트: 화면 끄기") { CommandBus.send(this, CommandBus.COMMAND_SLEEP_SCREEN) })

        val notes = TextView(this).apply {
            text = "접근성 서비스를 켠 뒤 기본 어시스턴트로 Jarvis를 선택하세요.\n전원 버튼 길게 누르기나 Jarvis 명령 듣기를 누른 뒤 '자비스 카메라 실행'처럼 말합니다."
            textSize = 14f
            setTextColor(Color.rgb(76, 86, 96))
            setPadding(0, dp(18), 0, 0)
        }
        root.addView(notes, matchWrap())

        return ScrollView(this).apply { addView(root) }
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        requestPermissions(permissions.toTypedArray(), REQUEST_PERMISSIONS)
    }

    private fun toggleOwnerEnrollment() {
        if (ownerVoiceEnrollmentController.isEnrolling) {
            stopOwnerEnrollment("목소리 등록을 중지했습니다.")
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermissions()
            return
        }

        if (JarvisVoiceService.isRunning) {
            val message = "Jarvis 서비스를 잠시 중지하고 목소리 등록을 시작합니다."
            ownerVoiceStatusView.text = message
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            stopService(Intent(this, JarvisVoiceService::class.java))
            handler.postDelayed({ beginOwnerEnrollment() }, OWNER_ENROLLMENT_START_DELAY_MS)
            return
        }

        beginOwnerEnrollment()
    }

    private fun beginOwnerEnrollment() {
        if (ownerVoiceEnrollmentController.isEnrolling) return

        JarvisVoiceServiceStarter.setOwnerEnrollmentActive(true)
        enrollButton.text = "내 목소리 등록 중지"
        ownerVoiceEnrollmentController.start(ENROLLMENT_DURATION_MS)
    }

    private fun stopOwnerEnrollment(message: String? = null) {
        ownerVoiceEnrollmentController.stop()
        JarvisVoiceServiceStarter.setOwnerEnrollmentActive(false)
        if (::enrollButton.isInitialized) enrollButton.text = "내 목소리 등록 시작"
        message?.let { ownerVoiceStatusView.text = it }
    }

    private fun clearOwnerVoiceProfile() {
        OwnerVoiceStore.clearProfile(this)
        enrollProgress.progress = 0
        updateStatus()
        Toast.makeText(this, "내 목소리 등록 삭제됨", Toast.LENGTH_SHORT).show()
    }

    private fun showOwnerVoiceError(message: String) {
        ownerVoiceStatusView.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openAssistantSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
            ) {
                startActivityForResult(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT),
                    REQUEST_ASSISTANT_ROLE,
                )
                return
            }
        }

        runCatching {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    private fun openBatteryOptimizationSettings() {
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        )
        startActivity(intent)
    }

    private fun openCommandList() {
        startActivity(Intent(this, CommandListActivity::class.java))
    }

    private fun startJarvisCommandWindow() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermissions()
            return
        }
        val accessibilityStatus = JarvisAccessibilityStatus.current(this)
        if (!accessibilityStatus.isReadyForAutomation) {
            val message = accessibilityStatus.guidance
                ?: "Jarvis 접근성 서비스를 먼저 확인하세요. 하이퍼아일랜드와 카메라 세부 제어에 필요합니다."
            statusView.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            updateStatus()
            return
        }
        if (!OwnerVoiceStore.isConfigured(this)) {
            val message = ownerVoiceStartBlockMessage()
            ownerVoiceStatusView.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            if (JarvisVoiceService.isRunning) stopService(Intent(this, JarvisVoiceService::class.java))
            updateStatus()
            return
        }

        val started = JarvisVoiceServiceStarter.openCommandWindow(this, "main_activity")
        if (!started) {
            Toast.makeText(this, "Jarvis 명령 대기를 시작하지 못했습니다. 알림/배터리 설정을 확인하세요.", Toast.LENGTH_LONG).show()
        }
        updateStatus()
    }

    private fun restartJarvisAfterOwnerEnrollment(embeddingCount: Int) {
        if (!OwnerVoiceStore.isConfigured(this)) return

        val message = "내 목소리 등록 완료: ${embeddingCount}개 저장. 전원 버튼 길게 누르기나 Jarvis 명령 듣기로 호출하세요."
        ownerVoiceStatusView.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun ownerVoiceStartBlockMessage(): String {
        return if (OwnerVoiceStore.hasProfile(this)) {
            "저장된 소유자 목소리가 '${OwnerVoiceStore.OWNER_ENROLLMENT_PHRASE}' activation용 프로필이 아닙니다. 내 목소리 등록을 다시 완료해야 Jarvis가 대기합니다."
        } else {
            "소유자 목소리를 먼저 등록하세요."
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) updateStatus()
    }

    private fun updateStatus() {
        if (ownerVoiceEnrollmentController.isEnrolling) return

        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notification = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val accessibilityStatus = JarvisAccessibilityStatus.current(this)
        val hasOwnerProfile = OwnerVoiceStore.hasProfile(this)
        val ownerProfileConfigured = OwnerVoiceStore.isConfigured(this)
        val ownerEmbeddingCount = OwnerVoiceStore.embeddingCount(this)
        val ownerPhraseId = OwnerVoiceStore.enrollmentPhraseId(this)

        statusView.text = buildString {
            appendLine("마이크 권한: ${if (mic) "허용됨" else "필요함"}")
            appendLine("알림 권한: ${if (notification) "허용됨" else "필요함"}")
            appendLine("접근성 서비스: ${accessibilityStatus.label}")
            appendLine("Jarvis 명령 대기: ${if (JarvisVoiceService.isRunning) "실행 중" else "꺼짐"}")
            appendLine()
            append(
                accessibilityStatus.guidance
                    ?: "하이퍼아일랜드와 카메라 세부 제어를 실행할 준비가 되어 있습니다.",
            )
        }

        ownerVoiceStatusView.text = buildString {
            appendLine(
                "소유자 목소리 인증: " + when {
                    ownerProfileConfigured -> "등록됨"
                    hasOwnerProfile -> "재등록 필요"
                    else -> "미등록"
                },
            )
            if (hasOwnerProfile) appendLine("저장된 음성 특징: ${ownerEmbeddingCount}개")
            if (hasOwnerProfile) {
                appendLine("등록 문구: ${ownerPhraseId ?: "이전 버전/알 수 없음"}")
            }
            if (hasOwnerProfile && !ownerProfileConfigured) {
                appendLine("등록 문구 '${OwnerVoiceStore.OWNER_ENROLLMENT_PHRASE}'로 다시 등록해야 Jarvis 대기를 시작합니다.")
                appendLine("내 목소리 등록 시작을 눌러 다시 등록하세요.")
            }
            appendLine("음성 엔진: sherpa-onnx / 3D-Speaker CAM++ / Korean streaming ASR")
            appendLine("기본 threshold: ${OwnerVoiceStore.DEFAULT_ACCEPT_THRESHOLD}")
            appendLine("소유자 확인 보정: 고신뢰 1회, 근접 2회 또는 soft score")
            append("등록이 완료되면 Jarvis는 시스템 어시스턴트 호출 또는 앱 버튼으로만 명령을 듣습니다.")
        }
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 16f
            setAllCaps(false)
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { onClick() }
        }
    }

    private fun matchWrap(bottomMargin: Int = dp(10)): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 0, 0, bottomMargin) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
        private const val REQUEST_ASSISTANT_ROLE = 1002
        private const val ENROLLMENT_DURATION_MS = 6000L
        private const val OWNER_ENROLLMENT_START_DELAY_MS = 500L
    }
}
