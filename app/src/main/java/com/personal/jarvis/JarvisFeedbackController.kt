package com.personal.jarvis

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

class JarvisFeedbackController(
    private val context: Context,
    private val handler: Handler,
) {
    private val toneGenerator by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }
    private var currentState: JarvisVoiceState? = null

    fun showWakeWaiting() {
        hidePassiveStatus()
    }

    fun showOwnerVerifying() {
        hidePassiveStatus()
    }

    fun commandReady() {
        signal(JarvisVoiceState.COMMAND_READY, force = true) {
            Log.d(TAG, "feedback=command_ready")
            vibrate(longArrayOf(0L, 90L))
            tone(ToneGenerator.TONE_PROP_ACK, 90)
        }
    }

    fun commandListening() {
        signal(JarvisVoiceState.COMMAND_READY)
    }

    fun commandProcessing() {
        signal(JarvisVoiceState.COMMAND_PROCESSING, force = true)
    }

    fun commandHandled() {
        signal(JarvisVoiceState.COMMAND_HANDLED, force = true) {
            Log.d(TAG, "feedback=command_handled")
            vibrate(longArrayOf(0L, 55L))
        }
    }

    fun commandFailed() {
        signal(JarvisVoiceState.COMMAND_FAILED, force = true) {
            Log.d(TAG, "feedback=command_failed")
            vibrate(longArrayOf(0L, 55L, 70L, 55L))
            tone(ToneGenerator.TONE_PROP_NACK, 110)
            handler.postDelayed({ tone(ToneGenerator.TONE_PROP_NACK, 110) }, 170L)
        }
    }

    fun commandWindowClosed() {
        signal(JarvisVoiceState.IDLE, force = true) {
            Log.d(TAG, "feedback=command_window_closed")
            vibrate(longArrayOf(0L, 45L, 70L, 45L))
            tone(ToneGenerator.TONE_PROP_BEEP, 90)
        }
    }

    fun release() {
        runCatching { toneGenerator.release() }
    }

    private fun hidePassiveStatus() {
        signal(JarvisVoiceState.IDLE)
    }

    private fun signal(
        state: JarvisVoiceState,
        force: Boolean = false,
        feedback: () -> Unit = {},
    ) {
        if (!force && currentState == state) return

        currentState = state
        JarvisStateBus.send(context, state)
        feedback()
    }

    private fun tone(toneType: Int, durationMs: Int) {
        runCatching { toneGenerator.startTone(toneType, durationMs) }
    }

    private fun vibrate(pattern: LongArray) {
        val deviceVibrator = vibrator ?: return
        if (!deviceVibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            deviceVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            deviceVibrator.vibrate(pattern, -1)
        }
    }

    companion object {
        private const val TAG = "JarvisFeedback"
    }
}
