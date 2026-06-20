package com.personal.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

object JarvisVoiceServiceStarter {
    const val EXTRA_START_SOURCE = "start_source"
    private const val TAG = "JarvisVoiceStarter"
    @Volatile private var ownerEnrollmentActive = false

    fun setOwnerEnrollmentActive(active: Boolean) {
        ownerEnrollmentActive = active
    }

    fun start(context: Context, source: String): Boolean {
        val appContext = context.applicationContext
        autoStartBlockReason(appContext)?.let { reason ->
            Log.w(TAG, "Blocked JarvisVoiceService start from $source: $reason")
            return false
        }
        if (JarvisVoiceService.isRunning) return true

        val intent = Intent(appContext, JarvisVoiceService::class.java)
            .putExtra(EXTRA_START_SOURCE, source)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
            Log.d(TAG, "Requested JarvisVoiceService start: source=$source")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start JarvisVoiceService from $source: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    fun autoStartBlockReason(context: Context): String? {
        val appContext = context.applicationContext
        if (ownerEnrollmentActive) {
            return "owner_voice_enrollment_active"
        }
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return "record_audio_permission_missing"
        }
        if (!OwnerVoiceStore.hasProfile(appContext)) {
            return "owner_voice_profile_missing"
        }
        if (!OwnerVoiceStore.isConfigured(appContext)) {
            return "owner_voice_profile_legacy"
        }
        return null
    }

}
