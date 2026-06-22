package com.personal.jarvis

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object JarvisAccessibilityStatus {
    data class Status(
        val frameworkEnabled: Boolean,
        val serviceConfigured: Boolean,
        val serviceBoundInProcess: Boolean,
    ) {
        val isReadyForAutomation: Boolean
            get() = frameworkEnabled && serviceConfigured && serviceBoundInProcess

        val label: String
            get() = when {
                !frameworkEnabled || !serviceConfigured -> "꺼짐"
                serviceBoundInProcess -> "켜짐"
                else -> "연결 필요"
            }

        val guidance: String?
            get() = when {
                !frameworkEnabled || !serviceConfigured ->
                    "Jarvis 접근성 서비스를 켜야 하이퍼아일랜드와 카메라 세부 제어가 동작합니다. 접근성 설정에서 '앱의 액세스가 거부됨'이 보이면 앱 정보에서 제한된 설정 허용을 먼저 켜세요."
                !serviceBoundInProcess ->
                    "Android가 Jarvis 접근성 서비스를 실행하지 못했습니다. 접근성 설정에서 Jarvis를 껐다가 다시 켜세요."
                else -> null
            }
    }

    fun current(context: Context): Status {
        val appContext = context.applicationContext
        return Status(
            frameworkEnabled = isFrameworkEnabled(appContext),
            serviceConfigured = isServiceConfigured(appContext),
            serviceBoundInProcess = CommandBus.hasDirectReceiver(),
        )
    }

    fun isEnabled(context: Context): Boolean {
        return current(context).let { it.frameworkEnabled && it.serviceConfigured }
    }

    private fun isFrameworkEnabled(context: Context): Boolean {
        return Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
    }

    private fun isServiceConfigured(context: Context): Boolean {
        val appContext = context.applicationContext
        val expected = ComponentName(
            appContext,
            JarvisAccessibilityService::class.java,
        ).flattenToString()
        val enabledServices = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        return enabledServices.split(':').any { service ->
            service.equals(expected, ignoreCase = true)
        }
    }
}
