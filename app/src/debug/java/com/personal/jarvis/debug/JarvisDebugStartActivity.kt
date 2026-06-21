package com.personal.jarvis.debug

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import com.personal.jarvis.JarvisVoiceService
import com.personal.jarvis.JarvisVoiceServiceStarter

class JarvisDebugStartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }

        if (intent?.getBooleanExtra(EXTRA_RESET_VOICE_SERVICE, false) == true) {
            val appContext = applicationContext
            stopService(Intent(this, JarvisVoiceService::class.java))
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    JarvisVoiceServiceStarter.start(appContext, "debug_start_activity")
                },
                RESET_START_DELAY_MS,
            )
            Log.d(TAG, "Scheduled JarvisVoiceService reset restart")
            finish()
        } else {
            JarvisVoiceServiceStarter.start(this, "debug_start_activity")
            finish()
        }
    }

    companion object {
        private const val TAG = "JarvisDebugStart"
        const val EXTRA_RESET_VOICE_SERVICE = "reset_voice_service"
        private const val RESET_START_DELAY_MS = 1000L
    }
}
