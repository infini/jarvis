package com.personal.jarvis.debug

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
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

        JarvisVoiceServiceStarter.start(this, "debug_start_activity")
        finish()
    }
}
