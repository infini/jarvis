package com.personal.jarvis.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class JarvisDebugCommandReplayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val replayIntent = Intent(this, JarvisDebugCommandReplayService::class.java)
            .putExtra(EXTRA_REQUEST_ID, intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty())
        startService(replayIntent)
        finish()
    }

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
    }
}
