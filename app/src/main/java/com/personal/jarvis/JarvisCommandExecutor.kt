package com.personal.jarvis

import android.content.Context
import android.os.Handler
import android.util.Log

class JarvisCommandExecutor(
    private val context: Context,
    private val handler: Handler,
    private val onStopListening: () -> Unit,
) {
    private var lastCommand: String? = null
    private var lastCommandAt = 0L

    fun run(command: String): Result {
        val now = System.currentTimeMillis()
        if (lastCommand == command && now - lastCommandAt < COMMAND_COOLDOWN_MS) {
            Log.d(TAG, "Ignored duplicate command: $command")
            return Result(command.keepsCommandWindowOpen())
        }

        lastCommand = command
        lastCommandAt = now
        Log.d(TAG, "Running command: $command")

        when (command) {
            CommandBus.COMMAND_STOP_LISTENING -> onStopListening()
            CommandBus.COMMAND_OPEN_CAMERA -> CameraLauncher.open(context)
            CommandBus.COMMAND_OPEN_FRONT_CAMERA,
            CommandBus.COMMAND_OPEN_REAR_CAMERA -> CommandBus.send(context, command, "voice")
            CommandBus.COMMAND_OPEN_CAMERA_AND_TAKE_PHOTO -> {
                CameraLauncher.open(context)
                handler.postDelayed(
                    { CommandBus.send(context, CommandBus.COMMAND_TAKE_PHOTO, "voice") },
                    CAMERA_OPEN_DELAY_MS,
                )
            }
            CommandBus.COMMAND_WAKE_SCREEN -> ScreenController.wake(context)
            else -> CommandBus.send(context, command, "voice")
        }

        return Result(command.keepsCommandWindowOpen())
    }

    data class Result(
        val keepsCommandWindowOpen: Boolean,
    )

    companion object {
        private const val TAG = "JarvisCommandExecutor"
        private const val COMMAND_COOLDOWN_MS = 1400L
        private const val CAMERA_OPEN_DELAY_MS = 1500L

        val CAMERA_SESSION_COMMANDS = setOf(
            CommandBus.COMMAND_OPEN_CAMERA,
            CommandBus.COMMAND_OPEN_FRONT_CAMERA,
            CommandBus.COMMAND_OPEN_REAR_CAMERA,
            CommandBus.COMMAND_OPEN_CAMERA_AND_TAKE_PHOTO,
            CommandBus.COMMAND_TAKE_PHOTO,
            CommandBus.COMMAND_OPEN_FILTERS,
            CommandBus.COMMAND_SWITCH_CAMERA,
        )

        val COMMAND_WINDOW_CONTINUATION_COMMANDS = CAMERA_SESSION_COMMANDS + setOf(
            CommandBus.COMMAND_HOME,
            CommandBus.COMMAND_BACK,
        )

        val FAST_PARTIAL_COMMANDS = COMMAND_WINDOW_CONTINUATION_COMMANDS

        fun shouldKeepCommandWindowOpen(command: String): Boolean {
            return command in COMMAND_WINDOW_CONTINUATION_COMMANDS
        }

        fun String.keepsCommandWindowOpen(): Boolean {
            return shouldKeepCommandWindowOpen(this)
        }
    }
}
