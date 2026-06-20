package com.personal.jarvis

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import ai.picovoice.android.voiceprocessor.VoiceProcessor
import ai.picovoice.android.voiceprocessor.VoiceProcessorException
import ai.picovoice.eagle.EagleActivationException
import ai.picovoice.eagle.EagleActivationLimitException
import ai.picovoice.eagle.EagleActivationRefusedException
import ai.picovoice.eagle.EagleActivationThrottledException
import ai.picovoice.eagle.EagleException
import ai.picovoice.eagle.EagleProfiler

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var ownerVoiceStatusView: TextView
    private lateinit var accessKeyInput: EditText
    private lateinit var enrollProgress: ProgressBar
    private lateinit var enrollButton: Button
    private val voiceProcessor = VoiceProcessor.getInstance()
    private var eagleProfiler: EagleProfiler? = null
    private var enrolling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
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

        accessKeyInput = EditText(this).apply {
            hint = "Picovoice AccessKey"
            textSize = 15f
            setSingleLine(true)
            setText(OwnerVoiceStore.getAccessKey(this@MainActivity))
        }
        root.addView(accessKeyInput, matchWrap())

        root.addView(button("Picovoice AccessKey 저장") { saveAccessKey() })

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
        root.addView(button("Jarvis 시작") { startJarvis() })
        root.addView(button("Jarvis 중지") { stopService(Intent(this, JarvisVoiceService::class.java)) })
        root.addView(button("테스트: 카메라 열기") { CameraLauncher.open(this) })
        root.addView(button("테스트: 셀피 카메라 열기") { CameraLauncher.openFront(this) })
        root.addView(button("테스트: 셔터 누르기") { CommandBus.send(this, CommandBus.COMMAND_TAKE_PHOTO) })

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

    private fun saveAccessKey() {
        OwnerVoiceStore.saveAccessKey(this, accessKeyInput.text.toString())
        updateStatus()
        Toast.makeText(this, "AccessKey 저장됨", Toast.LENGTH_SHORT).show()
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

        val accessKey = accessKeyInput.text.toString().trim()
        if (accessKey.isBlank()) {
            Toast.makeText(this, "Picovoice AccessKey를 먼저 입력하세요.", Toast.LENGTH_SHORT).show()
            return
        }

        OwnerVoiceStore.saveAccessKey(this, accessKey)
        stopService(Intent(this, JarvisVoiceService::class.java))

        try {
            val profiler = EagleProfiler.Builder()
                .setAccessKey(accessKey)
                .build(applicationContext)
            eagleProfiler = profiler
            enrollProgress.progress = 0
            enrolling = true
            enrollButton.text = "내 목소리 등록 중지"
            ownerVoiceStatusView.text = "목소리 등록 중: 조용한 곳에서 자연스럽게 여러 문장을 말하세요."
            voiceProcessor.addFrameListener(::enrollOwnerFrame)
            voiceProcessor.start(profiler.frameLength, profiler.sampleRate)
        } catch (e: EagleException) {
            handleOwnerVoiceError(e)
            stopOwnerEnrollment()
        } catch (e: VoiceProcessorException) {
            showOwnerVoiceError("마이크 녹음을 시작하지 못했습니다: ${e.message}")
            stopOwnerEnrollment()
        }
    }

    private fun enrollOwnerFrame(frame: ShortArray) {
        val profiler = eagleProfiler ?: return
        try {
            val percentage = profiler.enroll(frame)
            runOnUiThread {
                enrollProgress.progress = percentage.toInt().coerceIn(0, 100)
                ownerVoiceStatusView.text = "목소리 등록 중: ${percentage.toInt().coerceIn(0, 100)}%"
            }

            if (percentage >= 100f) {
                val profile = profiler.export()
                OwnerVoiceStore.saveProfile(this, profile.bytes)
                profile.delete()
                runOnUiThread {
                    Toast.makeText(this, "내 목소리 등록 완료", Toast.LENGTH_SHORT).show()
                    stopOwnerEnrollment("내 목소리 등록 완료. 이제 Jarvis 명령은 등록된 목소리 확인 후 실행됩니다.")
                    updateStatus()
                }
            }
        } catch (e: EagleException) {
            runOnUiThread {
                handleOwnerVoiceError(e)
                stopOwnerEnrollment()
            }
        }
    }

    private fun stopOwnerEnrollment(message: String? = null) {
        if (enrolling) {
            runCatching {
                voiceProcessor.stop()
                voiceProcessor.clearFrameListeners()
            }
        }
        eagleProfiler?.delete()
        eagleProfiler = null
        enrolling = false
        enrollButton.text = "내 목소리 등록 시작"
        message?.let { ownerVoiceStatusView.text = it }
    }

    private fun clearOwnerVoiceProfile() {
        OwnerVoiceStore.clearProfile(this)
        enrollProgress.progress = 0
        updateStatus()
        Toast.makeText(this, "내 목소리 등록 삭제됨", Toast.LENGTH_SHORT).show()
    }

    private fun handleOwnerVoiceError(error: EagleException) {
        val message = when (error) {
            is EagleActivationException -> "AccessKey 활성화 오류입니다."
            is EagleActivationLimitException -> "AccessKey 기기 한도에 도달했습니다."
            is EagleActivationRefusedException -> "AccessKey가 거부되었습니다."
            is EagleActivationThrottledException -> "AccessKey가 일시 제한되었습니다."
            else -> "목소리 엔진 오류: ${error.message}"
        }
        showOwnerVoiceError(message)
    }

    private fun showOwnerVoiceError(message: String) {
        ownerVoiceStatusView.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notification = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val accessibility = isJarvisAccessibilityEnabled()
        val hasAccessKey = OwnerVoiceStore.hasAccessKey(this)
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
            appendLine("Picovoice AccessKey: ${if (hasAccessKey) "저장됨" else "필요함"}")
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
    }
}
