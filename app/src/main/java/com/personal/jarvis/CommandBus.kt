package com.personal.jarvis

import android.os.SystemClock

object CommandBus {
    const val COMMAND_OPEN_CAMERA = "open_camera"
    const val COMMAND_OPEN_FRONT_CAMERA = "open_front_camera"
    const val COMMAND_OPEN_REAR_CAMERA = "open_rear_camera"
    const val COMMAND_OPEN_CAMERA_AND_TAKE_PHOTO = "open_camera_and_take_photo"
    const val COMMAND_TAKE_PHOTO = "take_photo"
    const val COMMAND_OPEN_FILTERS = "open_filters"
    const val COMMAND_SWITCH_CAMERA = "switch_camera"
    const val COMMAND_BACK = "back"
    const val COMMAND_HOME = "home"
    const val COMMAND_WAKE_SCREEN = "wake_screen"
    const val COMMAND_SLEEP_SCREEN = "sleep_screen"
    const val COMMAND_STOP_LISTENING = "stop_listening"
    const val COMMAND_STOP_SERVICE = "stop_service"

    @Volatile private var directReceiver: DirectReceiver? = null

    interface DirectReceiver {
        fun cancelPendingCommands()

        fun onDirectCommand(
            command: String,
            source: String,
            traceId: Long?,
            traceStartedAtMs: Long,
            sentAtMs: Long,
            onCompleted: (Boolean) -> Unit,
        )
    }

    fun registerDirectReceiver(receiver: DirectReceiver) {
        directReceiver = receiver
    }

    fun unregisterDirectReceiver(receiver: DirectReceiver) {
        if (directReceiver === receiver) directReceiver = null
    }

    fun hasDirectReceiver(): Boolean {
        return directReceiver != null
    }

    fun cancelPending() {
        directReceiver?.let { receiver -> runCatching(receiver::cancelPendingCommands) }
    }

    fun send(
        command: String,
        source: String = "jarvis",
        traceId: Long? = null,
        traceStartedAtMs: Long? = null,
        onCompleted: (Boolean) -> Unit = {},
    ): Boolean {
        val sentAtMs = SystemClock.elapsedRealtime()
        val receiver = directReceiver
        if (receiver == null) {
            runCatching { onCompleted(false) }
            return false
        }
        return runCatching {
            receiver.onDirectCommand(
                command = command,
                source = source,
                traceId = traceId,
                traceStartedAtMs = traceStartedAtMs ?: 0L,
                sentAtMs = sentAtMs,
                onCompleted = onCompleted,
            )
            true
        }.getOrElse {
            runCatching { onCompleted(false) }
            false
        }
    }
}
