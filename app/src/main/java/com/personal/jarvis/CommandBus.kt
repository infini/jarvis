package com.personal.jarvis

import android.content.Context
import android.content.Intent

object CommandBus {
    const val ACTION_COMMAND = "com.personal.jarvis.ACTION_COMMAND"
    const val EXTRA_COMMAND = "command"
    const val EXTRA_SOURCE = "source"

    const val COMMAND_OPEN_CAMERA = "open_camera"
    const val COMMAND_OPEN_FRONT_CAMERA = "open_front_camera"
    const val COMMAND_OPEN_REAR_CAMERA = "open_rear_camera"
    const val COMMAND_OPEN_CAMERA_AND_TAKE_PHOTO = "open_camera_and_take_photo"
    const val COMMAND_TAKE_PHOTO = "take_photo"
    const val COMMAND_OPEN_FILTERS = "open_filters"
    const val COMMAND_SWITCH_CAMERA = "switch_camera"
    const val COMMAND_BACK = "back"
    const val COMMAND_HOME = "home"
    const val COMMAND_STOP_LISTENING = "stop_listening"

    fun send(context: Context, command: String, source: String = "jarvis") {
        val intent = Intent(ACTION_COMMAND)
            .setPackage(context.packageName)
            .putExtra(EXTRA_COMMAND, command)
            .putExtra(EXTRA_SOURCE, source)
        context.sendBroadcast(intent)
    }
}
