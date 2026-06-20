package com.personal.jarvis

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
    private lateinit var statusView: TextView
    private lateinit var ownerVoiceStatusView: TextView
    private lateinit var enrollProgress: ProgressBar
    private lateinit var enrollButton: Button
    @Volatile private var enrolling = false
    private var enrollmentThread: Thread? = null

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
        root.addView(button("배터리 최적화 설정 열기") { openBatteryOptimizationSettings() })
        root.addView(button("앱 자동 시작/배터리 설정 열기") { openAppSettings() })
        root.addView(button("Jarvis 시작") { startJarvis() })
        root.addView(button("Jarvis 중지") { stopService(Intent(this, JarvisVoiceService::class.java)) })
        root.addView(button("테스트: 카메라 열기") { CameraLauncher.open(this) })
        root.addView(button("테스트: 셀피 카메라 열기") { CameraLauncher.openFront(this) })
        root.addView(button("테스트: 후면 카메라 열기") { CameraLauncher.openRear(this) })
        root.addView(button("테스트: 셔터 누르기") { CommandBus.send(this, CommandBus.COMMAND_TAKE_PHOTO) })
        root.addView(button("테스트: 화면 켜기") { ScreenController.wake(this) })

        val notes = TextView(this).apply {
            text = "접근성 서비스를 켠 뒤 Jarvis 시작을 누르세요.\n명령 예: 자비스, 카메라 셀피 모드로 실행해 / 자비스, 찍어 / 자비스, 필터"
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
        if (enrolling) {
            stopOwnerEnrollment("목소리 등록을 중지했습니다.")
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermissions()
            return
        }

        stopService(Intent(this, JarvisVoiceService::class.java))
        enrolling = true
        enrollProgress.progress = 0
        enrollButton.text = "내 목소리 등록 중지"
        ownerVoiceStatusView.text = "목소리 등록 중: 조용한 곳에서 6초 동안 자연스럽게 말하세요."

        enrollmentThread = Thread({
            try {
                val samples = OwnerVoiceEngine.recordSamples(
                    durationMs = ENROLLMENT_DURATION_MS,
                    shouldContinue = { enrolling },
                    onProgress = { progress ->
                        runOnUiThread {
                            if (enrolling) {
                                val percent = (progress * 100f).toInt().coerceIn(0, 100)
                                enrollProgress.progress = percent
                                ownerVoiceStatusView.text = "목소리 등록 중: $percent%"
                            }
                        }
                    },
                )

                if (!enrolling) return@Thread

                runOnUiThread {
                    ownerVoiceStatusView.text = "목소리 등록 중: 음성 특징을 계산하는 중입니다."
                }

                val embedding = OwnerVoiceEngine.createEmbedding(applicationContext, samples)
                    ?: throw IllegalStateException("충분한 음성 특징을 만들지 못했습니다. 더 또렷하게 다시 등록하세요.")
                OwnerVoiceStore.saveEmbedding(this, embedding)

                runOnUiThread {
                    Toast.makeText(this, "내 목소리 등록 완료", Toast.LENGTH_SHORT).show()
                    stopOwnerEnrollment("내 목소리 등록 완료. 이제 Jarvis 명령은 등록된 목소리 확인 후 실행됩니다.")
                    updateStatus()
                }
            } catch (e: Exception) {
                if (enrolling) {
                    runOnUiThread {
                        showOwnerVoiceError("목소리 등록 실패: ${e.message}")
                        stopOwnerEnrollment()
                    }
                }
            }
        }, "JarvisOwnerEnrollment").also { it.start() }
    }

    private fun stopOwnerEnrollment(message: String? = null) {
        enrolling = false
        enrollmentThread?.interrupt()
        enrollmentThread = null
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

    private fun startJarvis() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermissions()
            return
        }

        val intent = Intent(this, JarvisVoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateStatus()
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
        if (enrolling) return

        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notification = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val accessibility = isJarvisAccessibilityEnabled()
        val hasOwnerProfile = OwnerVoiceStore.hasProfile(this)

        statusView.text = buildString {
            appendLine("마이크 권한: ${if (mic) "허용됨" else "필요함"}")
            appendLine("알림 권한: ${if (notification) "허용됨" else "필요함"}")
            appendLine("접근성 서비스: ${if (accessibility) "켜짐" else "꺼짐"}")
            appendLine()
            append("기본 카메라 제어는 접근성 서비스가 켜져 있어야 동작합니다.")
        }

        ownerVoiceStatusView.text = buildString {
            appendLine("소유자 목소리 인증: ${if (hasOwnerProfile) "등록됨" else "미등록"}")
            appendLine("음성 엔진: sherpa-onnx / 3D-Speaker CAM++")
            appendLine("기본 threshold: ${OwnerVoiceStore.DEFAULT_ACCEPT_THRESHOLD}")
            append("등록이 완료되면 Jarvis는 소유자 목소리 확인 후 명령을 받습니다.")
        }
    }

    private fun isJarvisAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, JarvisAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
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
        private const val ENROLLMENT_DURATION_MS = 6000L
    }
}
