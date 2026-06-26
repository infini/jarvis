package com.personal.jarvis.debug

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log

class JarvisDebugOwnerEnrollActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "status=failed reason=record_audio_permission_missing")
            finish()
            return
        }

        val durationMs = intent
            ?.getLongExtra(EXTRA_DURATION_MS, DEFAULT_DURATION_MS)
            ?.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
            ?: DEFAULT_DURATION_MS
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID).orEmpty()

        val serviceIntent = Intent(this, JarvisDebugOwnerEnrollService::class.java)
            .putExtra(EXTRA_DURATION_MS, durationMs)
            .putExtra(EXTRA_REQUEST_ID, requestId)
        startForegroundService(serviceIntent)
        finish()
    }

    companion object {
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_REQUEST_ID = "request_id"
        private const val TAG = "JarvisDebugEnroll"
        private const val DEFAULT_DURATION_MS = 6_000L
        private const val MIN_DURATION_MS = 3_000L
        private const val MAX_DURATION_MS = 12_000L
    }
}
