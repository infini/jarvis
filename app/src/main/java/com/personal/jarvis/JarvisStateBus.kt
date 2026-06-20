package com.personal.jarvis

import android.content.Context
import android.content.Intent

object JarvisStateBus {
    const val ACTION_STATE = "com.personal.jarvis.ACTION_STATE"
    const val EXTRA_STATE = "state"

    fun send(context: Context, state: JarvisVoiceState) {
        val intent = Intent(ACTION_STATE)
            .setPackage(context.packageName)
            .putExtra(EXTRA_STATE, state.name)
        context.sendBroadcast(intent)
    }

    fun stateFrom(intent: Intent?): JarvisVoiceState? {
        val stateName = intent?.getStringExtra(EXTRA_STATE) ?: return null
        return runCatching { JarvisVoiceState.valueOf(stateName) }.getOrNull()
    }
}
