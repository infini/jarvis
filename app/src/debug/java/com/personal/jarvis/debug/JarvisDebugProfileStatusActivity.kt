package com.personal.jarvis.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.personal.jarvis.JarvisVoiceService
import com.personal.jarvis.OwnerVoiceStore

class JarvisDebugProfileStatusActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val embeddingCount = OwnerVoiceStore.embeddingCount(this)
        Log.i(
            TAG,
            "profile_configured=${embeddingCount > 0} " +
                "profile_embeddings=$embeddingCount " +
                "voice_service_running=${JarvisVoiceService.isRunning}",
        )
        finish()
    }

    companion object {
        private const val TAG = "JarvisDebugStatus"
    }
}
