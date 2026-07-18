package com.personal.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

class JarvisAssistantActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val accessibilityStatus = JarvisAccessibilityStatus.current(this)
        when {
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                openMainActivity(
                    MainActivity.NOTICE_MICROPHONE_REQUIRED,
                    "Jarvis 마이크 권한이 필요합니다.",
                )
            }
            !accessibilityStatus.isReadyForAutomation -> {
                openMainActivity(
                    MainActivity.NOTICE_ACCESSIBILITY_REQUIRED,
                    accessibilityStatus.guidance
                        ?: "Jarvis 접근성 서비스를 확인해야 합니다.",
                )
            }
            !OwnerVoiceStore.isConfigured(this) -> {
                openMainActivity(
                    MainActivity.NOTICE_OWNER_VOICE_REQUIRED,
                    "소유자 목소리 등록을 먼저 완료하세요.",
                )
            }
            !JarvisVoiceServiceStarter.openCommandWindow(this, SOURCE) -> {
                openMainActivity(
                    MainActivity.NOTICE_START_FAILED,
                    "Jarvis 명령 대기를 시작하지 못했습니다.",
                )
            }
        }

        finish()
    }

    private fun openMainActivity(noticeCode: String, message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_NOTICE_CODE, noticeCode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    companion object {
        private const val SOURCE = "assistant_activity"
    }
}
