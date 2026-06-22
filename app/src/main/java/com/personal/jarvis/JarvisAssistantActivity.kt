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

        when {
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                openMainActivity("Jarvis 마이크 권한이 필요합니다.")
            }
            !JarvisAccessibilityStatus.isEnabled(this) -> {
                openMainActivity("Jarvis 접근성 서비스를 켜야 상태 표시와 카메라 제어가 동작합니다.")
            }
            !OwnerVoiceStore.isConfigured(this) -> {
                openMainActivity("소유자 목소리 등록을 먼저 완료하세요.")
            }
            !JarvisVoiceServiceStarter.openCommandWindow(this, SOURCE) -> {
                openMainActivity("Jarvis 명령 대기를 시작하지 못했습니다.")
            }
        }

        finish()
    }

    private fun openMainActivity(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    companion object {
        private const val SOURCE = "assistant_activity"
    }
}
