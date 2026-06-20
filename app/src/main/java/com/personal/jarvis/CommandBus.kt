package com.personal.jarvis

import android.content.Context
import android.content.Intent
import android.os.SystemClock

object CommandBus {
    const val ACTION_COMMAND = "com.personal.jarvis.ACTION_COMMAND"
    const val EXTRA_COMMAND = "command"
    const val EXTRA_SOURCE = "source"
    const val EXTRA_TRACE_ID = "trace_id"
    const val EXTRA_TRACE_STARTED_AT_MS = "trace_started_at_ms"
    const val EXTRA_SENT_AT_MS = "sent_elapsed_ms"

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

    fun send(
        context: Context,
        command: String,
        source: String = "jarvis",
        traceId: Long? = null,
        traceStartedAtMs: Long? = null,
    ) {
        val intent = Intent(ACTION_COMMAND)
            .setPackage(context.packageName)
            .putExtra(EXTRA_COMMAND, command)
            .putExtra(EXTRA_SOURCE, source)
            .putExtra(EXTRA_SENT_AT_MS, SystemClock.elapsedRealtime())
        if (traceId != null) {
            intent.putExtra(EXTRA_TRACE_ID, traceId)
        }
        if (traceStartedAtMs != null) {
            intent.putExtra(EXTRA_TRACE_STARTED_AT_MS, traceStartedAtMs)
        }
        context.sendBroadcast(intent)
    }
}
