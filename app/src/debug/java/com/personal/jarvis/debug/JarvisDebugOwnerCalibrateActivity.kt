package com.personal.jarvis.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class JarvisDebugOwnerCalibrateActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val calibrateIntent = Intent(this, JarvisDebugOwnerCalibrateService::class.java)
            .putExtra(EXTRA_REQUEST_ID, intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty())
        startService(calibrateIntent)
        finish()
    }

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
    }
}
