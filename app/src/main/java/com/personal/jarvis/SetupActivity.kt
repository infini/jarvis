package com.personal.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SetupActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var voiceStatusView: TextView
    private lateinit var voiceProgress: ProgressBar
    private lateinit var voiceButton: Button
    private var pendingPermissionAction = PendingPermissionAction.NONE
    private var contentScrollView: ScrollView? = null
    private var firstResume = true
    private val permissionPreferences by lazy {
        getSharedPreferences(PERMISSION_PREFERENCES, MODE_PRIVATE)
    }
    private val beginEnrollmentRunnable = Runnable { beginOwnerEnrollment() }
    private val ownerVoiceEnrollmentController by lazy {
        OwnerVoiceEnrollmentController(
            context = applicationContext,
            postToMain = { action -> runOnUiThread(action) },
            onProgress = { percent ->
                if (::voiceProgress.isInitialized) {
                    val displayed = if (percent >= 100) 100 else (percent / 20) * 20
                    if (voiceProgress.progress != displayed) voiceProgress.progress = displayed
                }
            },
            onStatus = { status ->
                if (::voiceStatusView.isInitialized && shouldDisplayProgressStatus(status)) {
                    voiceStatusView.text = status
                }
            },
            onCompleted = { embeddingCount ->
                stopOwnerEnrollment()
                Toast.makeText(
                    this,
                    "내 목소리 등록 완료 · 음성 특징 ${embeddingCount}개 저장",
                    Toast.LENGTH_LONG,
                ).show()
                refreshContent()
            },
            onFailed = { message ->
                stopOwnerEnrollment(message)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JarvisUi.prepareWindow(this)
        showContent(preserveScroll = false)
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) {
            firstResume = false
        } else if (!ownerVoiceEnrollmentController.isEnrolling) {
            refreshContent()
        }
    }

    override fun onStop() {
        if (ownerVoiceEnrollmentController.isEnrolling) {
            Toast.makeText(this, "화면을 벗어나 목소리 등록을 중지했습니다.", Toast.LENGTH_SHORT).show()
        }
        stopOwnerEnrollment()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(beginEnrollmentRunnable)
        stopOwnerEnrollment()
        super.onDestroy()
    }

    private fun refreshContent() {
        if (isFinishing || isDestroyed) return
        showContent(preserveScroll = true)
    }

    private fun showContent(preserveScroll: Boolean) {
        val scrollY = if (preserveScroll) contentScrollView?.scrollY ?: 0 else 0
        val content = buildContentView()
        contentScrollView = content
        setContentView(content)
        if (preserveScroll && scrollY > 0) {
            content.post { content.scrollTo(0, scrollY) }
        }
    }

    private fun buildContentView(): ScrollView {
        val status = JarvisSetupStatus.capture(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
            setBackgroundColor(JarvisUi.BACKGROUND)
        }

        root.addView(buildHeader(), JarvisUi.matchWrap(this, bottom = 18))
        root.addView(
            JarvisUi.label(this, "Jarvis 시작하기", 28f, JarvisUi.INK, bold = true),
            JarvisUi.matchWrap(this, bottom = 6),
        )
        root.addView(
            JarvisUi.label(
                this,
                "필수 설정을 순서대로 완료하면 바로 음성 명령을 사용할 수 있습니다.",
                15f,
                JarvisUi.MUTED,
            ),
            JarvisUi.matchWrap(this, bottom = 18),
        )
        root.addView(buildProgressCard(status), JarvisUi.matchWrap(this, bottom = 24))
        root.addView(sectionTitle("필수 설정"), JarvisUi.matchWrap(this, bottom = 10))
        root.addView(buildMicrophoneCard(status), JarvisUi.matchWrap(this, bottom = 12))
        root.addView(buildOwnerVoiceCard(status), JarvisUi.matchWrap(this, bottom = 12))
        root.addView(buildAccessibilityCard(status), JarvisUi.matchWrap(this, bottom = 24))

        root.addView(sectionTitle("더 편리하고 안정적으로"), JarvisUi.matchWrap(this, bottom = 10))
        root.addView(buildAssistantCard(status), JarvisUi.matchWrap(this, bottom = 12))
        root.addView(buildNotificationCard(status), JarvisUi.matchWrap(this, bottom = 12))
        root.addView(buildBatteryCard(status), JarvisUi.matchWrap(this, bottom = 12))
        root.addView(buildAppSettingsCard(), JarvisUi.matchWrap(this, bottom = 22))

        root.addView(
            JarvisUi.button(this, if (status.canListen) "설정 완료" else "메인으로 돌아가기", primary = status.canListen) {
                finish()
            },
            JarvisUi.matchWrap(this, bottom = 0),
        )

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
            JarvisUi.applySystemBarPadding(this)
        }
    }

    private fun buildHeader(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val back = JarvisUi.statusPill(context, "‹  뒤로", JarvisUi.INK, JarvisUi.SURFACE).apply {
                minHeight = dp(48)
                isClickable = true
                isFocusable = true
                contentDescription = "메인 화면으로 돌아가기"
                background = JarvisUi.ripple(JarvisUi.SURFACE, JarvisUi.BORDER, context)
                setOnClickListener { finish() }
            }
            addView(back)
        }
    }

    private fun buildProgressCard(status: JarvisSetupStatus): LinearLayout {
        val ready = status.canListen
        return JarvisUi.card(this).apply {
            background = JarvisUi.rounded(
                if (ready) JarvisUi.SOFT_GREEN else JarvisUi.SOFT_BLUE,
                dp(18).toFloat(),
            )
            addView(
                JarvisUi.label(
                    context,
                    if (ready) "필수 설정 완료" else "${status.completedRequiredSteps}/${status.requiredStepCount} 완료",
                    18f,
                    if (ready) JarvisUi.SUCCESS else JarvisUi.PRIMARY,
                    bold = true,
                ),
                JarvisUi.matchWrap(context, bottom = 6),
            )
            addView(
                JarvisUi.label(
                    context,
                    if (ready) {
                        "이제 메인 화면에서 Jarvis에게 말할 수 있습니다."
                    } else {
                        "완료된 항목은 자동으로 확인됩니다. 다음 설정을 이어서 진행해 주세요."
                    },
                    14f,
                    JarvisUi.MUTED,
                ),
                JarvisUi.matchWrap(context, bottom = 0),
            )
        }
    }

    private fun buildMicrophoneCard(status: JarvisSetupStatus): LinearLayout {
        return settingCard(
            number = "1",
            title = "마이크 허용",
            description = if (status.microphoneGranted) {
                "음성 명령을 들을 수 있습니다."
            } else {
                "Jarvis가 사용자가 말한 명령을 들을 수 있도록 허용합니다."
            },
            completed = status.microphoneGranted,
            actionLabel = if (status.microphoneGranted) null else "허용하기",
            onAction = { requestMicrophonePermission(PendingPermissionAction.NONE) },
        )
    }

    private fun buildOwnerVoiceCard(status: JarvisSetupStatus): LinearLayout {
        return settingCard(
            number = "2",
            title = "내 목소리 등록",
            description = if (status.ownerVoiceConfigured) {
                "등록 완료 · 본인 목소리인지 확인한 뒤 명령을 실행합니다."
            } else {
                "조용한 곳에서 ‘${OwnerVoiceStore.OWNER_ENROLLMENT_PHRASE}’를 여러 번 말해 주세요. 약 6초 걸립니다."
            },
            completed = status.ownerVoiceConfigured,
            actionLabel = null,
        ).apply {
            voiceStatusView = JarvisUi.label(
                context,
                if (status.ownerVoiceConfigured) "필요할 때 다시 등록해 인식 상태를 새로 맞출 수 있습니다." else "녹음은 이 기기에서 음성 특징으로 변환해 저장합니다.",
                13f,
                JarvisUi.MUTED,
            )
            addView(voiceStatusView, JarvisUi.matchWrap(context, top = 10, bottom = 8))

            voiceProgress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
                visibility = if (ownerVoiceEnrollmentController.isEnrolling) View.VISIBLE else View.GONE
            }
            addView(voiceProgress, JarvisUi.matchWrap(context, bottom = 10))

            voiceButton = JarvisUi.button(
                context,
                if (ownerVoiceEnrollmentController.isEnrolling) "등록 중지" else if (status.ownerVoiceConfigured) "다시 등록" else "목소리 등록하기",
                primary = !status.ownerVoiceConfigured,
            ) { toggleOwnerEnrollment() }
            addView(voiceButton, JarvisUi.matchWrap(context, bottom = if (status.ownerVoiceConfigured) 8 else 0))

            if (status.ownerVoiceConfigured) {
                val deleteButton = JarvisUi.button(context, "등록된 목소리 삭제", primary = false) {
                    confirmClearOwnerVoiceProfile()
                }.apply { setTextColor(JarvisUi.DANGER) }
                addView(deleteButton, JarvisUi.matchWrap(context, bottom = 0))
            }
        }
    }

    private fun buildAccessibilityCard(status: JarvisSetupStatus): LinearLayout {
        val accessibility = JarvisAccessibilityStatus.current(this)
        return settingCard(
            number = "3",
            title = "접근성 연결",
            description = if (status.accessibilityReady) {
                "카메라와 화면 명령을 실행할 준비가 됐습니다."
            } else {
                accessibility.guidance ?: "Jarvis 접근성 서비스를 켜 주세요."
            },
            completed = status.accessibilityReady,
            actionLabel = if (status.accessibilityReady) "설정 확인" else "접근성 열기",
            onAction = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        )
    }

    private fun buildAssistantCard(status: JarvisSetupStatus): LinearLayout {
        return settingCard(
            number = "A",
            title = "전원 버튼으로 빠르게 호출",
            description = when {
                !status.assistantRoleAvailable -> "이 기기에서는 기본 어시스턴트 역할 설정을 지원하지 않습니다."
                status.assistantRoleHeld -> "전원 버튼을 길게 눌러 Jarvis를 바로 호출할 수 있습니다."
                else -> "Jarvis를 기본 어시스턴트로 선택하면 앱을 열지 않고 호출할 수 있습니다."
            },
            completed = status.assistantRoleHeld,
            actionLabel = if (!status.assistantRoleAvailable) null else if (status.assistantRoleHeld) "설정 확인" else "설정하기",
            onAction = { openAssistantSettings() },
            statusLabel = when {
                !status.assistantRoleAvailable -> "지원 안 함"
                status.assistantRoleHeld -> "완료"
                else -> "권장"
            },
        )
    }

    private fun buildNotificationCard(status: JarvisSetupStatus): LinearLayout {
        return settingCard(
            number = "B",
            title = "상태 알림 허용",
            description = if (status.notificationsGranted) {
                "Jarvis가 듣는 중인지 알림에서 확인할 수 있습니다."
            } else {
                "명령을 듣는 동안 실행 상태와 중지 버튼을 표시합니다."
            },
            completed = status.notificationsGranted,
            actionLabel = if (status.notificationsGranted) null else "알림 허용",
            onAction = { requestNotificationPermission() },
            statusLabel = if (status.notificationsGranted) "완료" else "권장",
        )
    }

    private fun buildBatteryCard(status: JarvisSetupStatus): LinearLayout {
        return settingCard(
            number = "C",
            title = "배터리 제한 확인",
            description = if (status.batteryOptimizationDisabled) {
                "시스템이 명령 듣기를 중간에 종료할 가능성을 줄였습니다."
            } else {
                "배터리 최적화에서 Jarvis를 제한하지 않도록 설정하면 더 안정적입니다."
            },
            completed = status.batteryOptimizationDisabled,
            actionLabel = if (status.batteryOptimizationDisabled) "설정 확인" else "배터리 설정",
            onAction = { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
            statusLabel = if (status.batteryOptimizationDisabled) "완료" else "권장",
        )
    }

    private fun buildAppSettingsCard(): LinearLayout {
        return settingCard(
            number = "D",
            title = "앱 시스템 설정",
            description = "HyperOS 자동 시작, 제한된 설정, 권한을 앱 정보에서 직접 확인합니다.",
            completed = false,
            actionLabel = "앱 정보 열기",
            onAction = {
                openAppDetailsSettings()
            },
            statusLabel = null,
        )
    }

    private fun settingCard(
        number: String,
        title: String,
        description: String,
        completed: Boolean,
        actionLabel: String?,
        onAction: (() -> Unit)? = null,
        statusLabel: String? = if (completed) "완료" else "필요",
    ): LinearLayout {
        return JarvisUi.card(this).apply {
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        JarvisUi.statusPill(
                            context,
                            if (completed) "✓" else number,
                            if (completed) JarvisUi.SUCCESS else JarvisUi.PRIMARY,
                            if (completed) JarvisUi.SOFT_GREEN else JarvisUi.SOFT_BLUE,
                        ),
                        JarvisUi.wrapWrap(context),
                    )
                    addView(
                        JarvisUi.label(context, title, 17f, JarvisUi.INK, bold = true),
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = dp(12)
                        },
                    )
                    if (statusLabel != null) {
                        addView(
                            JarvisUi.label(
                                context,
                                statusLabel,
                                13f,
                                when (statusLabel) {
                                    "완료" -> JarvisUi.SUCCESS
                                    "지원 안 함" -> JarvisUi.MUTED
                                    else -> JarvisUi.WARNING
                                },
                                bold = true,
                            ),
                        )
                    }
                },
                JarvisUi.matchWrap(context, bottom = 9),
            )
            addView(JarvisUi.label(context, description, 14f, JarvisUi.MUTED), JarvisUi.matchWrap(context, bottom = if (actionLabel == null) 0 else 12))
            if (actionLabel != null && onAction != null) {
                addView(
                    JarvisUi.button(context, actionLabel, primary = false, onClick = onAction),
                    JarvisUi.matchWrap(context, bottom = 0),
                )
            }
        }
    }

    private fun toggleOwnerEnrollment() {
        if (ownerVoiceEnrollmentController.isEnrolling) {
            stopOwnerEnrollment("목소리 등록을 중지했습니다.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicrophonePermission(PendingPermissionAction.OWNER_ENROLLMENT)
            return
        }
        if (JarvisVoiceService.isRunning) {
            voiceStatusView.setText(R.string.owner_enrollment_stopping_jarvis)
            stopService(Intent(this, JarvisVoiceService::class.java))
            handler.removeCallbacks(beginEnrollmentRunnable)
            handler.postDelayed(beginEnrollmentRunnable, OWNER_ENROLLMENT_START_DELAY_MS)
            return
        }
        beginOwnerEnrollment()
    }

    private fun beginOwnerEnrollment() {
        if (isFinishing || isDestroyed || ownerVoiceEnrollmentController.isEnrolling) return
        JarvisVoiceServiceStarter.setOwnerEnrollmentActive(true)
        voiceProgress.visibility = View.VISIBLE
        voiceProgress.progress = 0
        voiceButton.text = "등록 중지"
        ownerVoiceEnrollmentController.start(ENROLLMENT_DURATION_MS)
    }

    private fun stopOwnerEnrollment(message: String? = null) {
        handler.removeCallbacks(beginEnrollmentRunnable)
        ownerVoiceEnrollmentController.stop()
        JarvisVoiceServiceStarter.setOwnerEnrollmentActive(false)
        if (::voiceButton.isInitialized) voiceButton.text = "목소리 등록하기"
        if (::voiceProgress.isInitialized) voiceProgress.visibility = View.GONE
        if (message != null && ::voiceStatusView.isInitialized) voiceStatusView.text = message
    }

    private fun confirmClearOwnerVoiceProfile() {
        AlertDialog.Builder(this)
            .setTitle("등록된 목소리를 삭제할까요?")
            .setMessage("삭제하면 Jarvis를 다시 사용하기 전에 내 목소리를 새로 등록해야 합니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                stopService(Intent(this, JarvisVoiceService::class.java))
                stopOwnerEnrollment()
                val cleared = OwnerVoiceStore.clearProfile(this)
                Toast.makeText(
                    this,
                    if (cleared) {
                        "등록된 목소리와 원본 등록 녹음을 삭제했습니다."
                    } else {
                        "목소리 데이터를 모두 삭제하지 못했습니다. 다시 시도해 주세요."
                    },
                    if (cleared) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                ).show()
                refreshContent()
            }
            .show()
    }

    private fun requestMicrophonePermission(afterGrant: PendingPermissionAction) {
        if (permissionMustBeChangedInSettings(
                Manifest.permission.RECORD_AUDIO,
                KEY_MICROPHONE_REQUESTED,
            )
        ) {
            pendingPermissionAction = PendingPermissionAction.NONE
            Toast.makeText(this, "앱 정보의 권한에서 마이크를 허용해 주세요.", Toast.LENGTH_LONG).show()
            openAppDetailsSettings()
            return
        }
        pendingPermissionAction = afterGrant
        permissionPreferences.edit().putBoolean(KEY_MICROPHONE_REQUESTED, true).apply()
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (permissionMustBeChangedInSettings(
                    Manifest.permission.POST_NOTIFICATIONS,
                    KEY_NOTIFICATION_REQUESTED,
                )
            ) {
                Toast.makeText(this, "앱 정보의 알림에서 Jarvis 알림을 허용해 주세요.", Toast.LENGTH_LONG).show()
                openAppDetailsSettings()
                return
            }
            permissionPreferences.edit().putBoolean(KEY_NOTIFICATION_REQUESTED, true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_MICROPHONE -> {
                val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                val pending = pendingPermissionAction
                pendingPermissionAction = PendingPermissionAction.NONE
                refreshContent()
                if (granted && pending == PendingPermissionAction.OWNER_ENROLLMENT) {
                    beginOwnerEnrollment()
                } else if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                    Toast.makeText(this, "마이크 권한은 앱 정보에서 다시 허용할 수 있습니다.", Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_NOTIFICATION -> {
                refreshContent()
                if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                ) {
                    Toast.makeText(this, "알림은 앱 정보에서 다시 허용할 수 있습니다.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun permissionMustBeChangedInSettings(permission: String, requestedKey: String): Boolean {
        return checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED &&
            permissionPreferences.getBoolean(requestedKey, false) &&
            !shouldShowRequestPermissionRationale(permission)
    }

    private fun openAppDetailsSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
            },
        )
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
        runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
    }

    private fun sectionTitle(text: String): TextView =
        JarvisUi.label(this, text, 17f, JarvisUi.INK, bold = true)

    private fun dp(value: Int): Int = JarvisUi.dp(this, value)

    private fun shouldDisplayProgressStatus(status: String): Boolean {
        val percent = PROGRESS_PERCENT.find(status)?.groupValues?.get(1)?.toIntOrNull() ?: return true
        return percent >= 100 || percent % 20 == 0
    }

    private enum class PendingPermissionAction {
        NONE,
        OWNER_ENROLLMENT,
    }

    companion object {
        private const val REQUEST_MICROPHONE = 1101
        private const val REQUEST_NOTIFICATION = 1102
        private const val REQUEST_ASSISTANT_ROLE = 1103
        private const val ENROLLMENT_DURATION_MS = 6000L
        private const val OWNER_ENROLLMENT_START_DELAY_MS = 500L
        private const val PERMISSION_PREFERENCES = "setup_permission_state"
        private const val KEY_MICROPHONE_REQUESTED = "microphone_requested"
        private const val KEY_NOTIFICATION_REQUESTED = "notification_requested"
        private val PROGRESS_PERCENT = Regex("(\\d+)%")
    }
}
