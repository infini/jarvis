package com.personal.jarvis.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.personal.jarvis.JarvisVoiceService
import com.personal.jarvis.OwnerVoiceStore

class JarvisDebugProfileStatusActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val embeddingCount = OwnerVoiceStore.embeddingCount(this)
        val configured = OwnerVoiceStore.isConfigured(this)
        val phraseId = OwnerVoiceStore.enrollmentPhraseId(this) ?: "unknown"
        Log.i(
            TAG,
            "request_id=$requestId " +
                "profile_configured=$configured " +
                "profile_embeddings=$embeddingCount " +
                "profile_phrase_id=$phraseId " +
                "required_phrase_id=${OwnerVoiceStore.OWNER_ENROLLMENT_PHRASE_ID} " +
                "voice_service_running=${JarvisVoiceService.isRunning}",
        )
        finish()
    }

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
        private const val TAG = "JarvisDebugStatus"
    }
}
