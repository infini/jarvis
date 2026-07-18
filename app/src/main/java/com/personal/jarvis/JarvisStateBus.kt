package com.personal.jarvis

import java.util.concurrent.CopyOnWriteArraySet

object JarvisStateBus {
    fun interface Listener {
        fun onStateChanged(state: JarvisVoiceState)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    @Volatile private var latestState = JarvisVoiceState.IDLE

    fun send(state: JarvisVoiceState) {
        latestState = state
        listeners.forEach { listener ->
            runCatching { listener.onStateChanged(state) }
        }
    }

    fun addListener(listener: Listener, emitCurrent: Boolean = true) {
        listeners += listener
        if (emitCurrent) listener.onStateChanged(latestState)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun current(): JarvisVoiceState = latestState
}
