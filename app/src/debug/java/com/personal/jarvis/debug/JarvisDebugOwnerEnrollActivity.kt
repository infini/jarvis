package com.personal.jarvis.debug

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import com.personal.jarvis.JarvisVoiceService
import com.personal.jarvis.JarvisVoiceServiceStarter
import com.personal.jarvis.OwnerVoiceEngine
import com.personal.jarvis.OwnerVoiceStore

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

        JarvisVoiceServiceStarter.setOwnerEnrollmentActive(true)
        stopService(Intent(this, JarvisVoiceService::class.java))
        Log.i(TAG, "request_id=$requestId status=recording durationMs=$durationMs")

        Thread({
            try {
                val samples = OwnerVoiceEngine.recordSamples(
                    durationMs = durationMs,
                    shouldContinue = { true },
                )
                val embeddings = OwnerVoiceEngine.createEnrollmentEmbeddings(this, samples)
                if (embeddings.size < OwnerVoiceEngine.MIN_OWNER_EMBEDDINGS) {
                    Log.e(
                        TAG,
                        "request_id=$requestId status=failed reason=not_enough_embeddings " +
                            "profile_embeddings=${embeddings.size}",
                    )
                    return@Thread
                }

                OwnerVoiceStore.saveEmbeddings(this, embeddings)
                Log.i(TAG, "request_id=$requestId status=completed profile_embeddings=${embeddings.size}")
            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "request_id=$requestId status=failed " +
                        "reason=${error.javaClass.simpleName} message=${error.message}",
                )
            } finally {
                JarvisVoiceServiceStarter.setOwnerEnrollmentActive(false)
                runOnUiThread { finish() }
            }
        }, "JarvisDebugOwnerEnroll").start()
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
