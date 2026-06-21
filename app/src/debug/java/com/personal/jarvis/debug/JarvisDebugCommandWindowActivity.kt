package com.personal.jarvis.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.personal.jarvis.JarvisVoiceService
import com.personal.jarvis.JarvisVoiceServiceStarter

class JarvisDebugCommandWindowActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val windowMs = intent.getLongExtra(EXTRA_WINDOW_MS, DEFAULT_WINDOW_MS)
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
        val serviceIntent = Intent(this, JarvisVoiceService::class.java)
            .putExtra(
                JarvisVoiceServiceStarter.EXTRA_START_SOURCE,
                "debug_command_window_activity",
            )
            .putExtra(JarvisVoiceService.EXTRA_DEBUG_COMMAND_WINDOW_MS, windowMs)
            .putExtra(JarvisVoiceService.EXTRA_DEBUG_REQUEST_ID, requestId)
        if (command.isNotBlank()) {
            serviceIntent.putExtra(JarvisVoiceService.EXTRA_DEBUG_COMMAND, command)
        }
        startForegroundService(serviceIntent)
        finish()
    }

    companion object {
        const val EXTRA_WINDOW_MS = "window_ms"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_COMMAND = "command"
        private const val DEFAULT_WINDOW_MS = 30000L
    }
}
