package com.personal.jarvis

import android.content.Context
import android.os.Handler
import android.util.Log

class JarvisCommandExecutor(
    private val context: Context,
    private val handler: Handler,
) {
    private var lastCommand: String? = null
    private var lastCommandAt = 0L

    fun run(
        command: String,
        traceId: Long? = null,
        traceStartedAtMs: Long? = null,
    ): Result {
        val now = System.currentTimeMillis()
        if (lastCommand == command && now - lastCommandAt < cooldownMsFor(command)) {
            Log.d(TAG, "Ignored duplicate command: $command")
            return Result(command.keepsCommandWindowOpen())
        }

        lastCommand = command
        lastCommandAt = now
        Log.d(TAG, "Running command: $command")

        when (command) {
            CommandBus.COMMAND_STOP_LISTENING -> Log.d(TAG, "Closing current Jarvis command window")
            CommandBus.COMMAND_STOP_SERVICE -> Log.d(TAG, "Stopping Jarvis voice service by command")
            CommandBus.COMMAND_OPEN_CAMERA -> CameraLauncher.open(context)
            CommandBus.COMMAND_OPEN_FRONT_CAMERA,
            CommandBus.COMMAND_OPEN_REAR_CAMERA -> {
                CommandBus.send(context, command, "voice", traceId, traceStartedAtMs)
            }
            CommandBus.COMMAND_OPEN_CAMERA_AND_TAKE_PHOTO -> {
                CameraLauncher.open(context)
                handler.postDelayed(
                    {
                        CommandBus.send(
                            context = context,
                            command = CommandBus.COMMAND_TAKE_PHOTO,
                            source = "voice",
                            traceId = traceId,
                            traceStartedAtMs = traceStartedAtMs,
                        )
                    },
                    CAMERA_OPEN_DELAY_MS,
                )
            }
            CommandBus.COMMAND_WAKE_SCREEN -> ScreenController.wake(context)
            else -> CommandBus.send(context, command, "voice", traceId, traceStartedAtMs)
        }

        return Result(command.keepsCommandWindowOpen())
    }

    data class Result(
        val keepsCommandWindowOpen: Boolean,
    )

    companion object {
        private const val TAG = "JarvisCommandExecutor"
        private const val COMMAND_COOLDOWN_MS = 1400L
        private const val TAKE_PHOTO_COMMAND_COOLDOWN_MS = 500L
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
        private val VOICE_SERVICE_STOP_COMMANDS = setOf(
            CommandBus.COMMAND_STOP_SERVICE,
        )

        val FAST_PARTIAL_COMMANDS = COMMAND_WINDOW_CONTINUATION_COMMANDS + setOf(
            CommandBus.COMMAND_WAKE_SCREEN,
            CommandBus.COMMAND_SLEEP_SCREEN,
            CommandBus.COMMAND_STOP_LISTENING,
            CommandBus.COMMAND_STOP_SERVICE,
        )

        fun shouldKeepCommandWindowOpen(command: String): Boolean {
            return command in COMMAND_WINDOW_CONTINUATION_COMMANDS
        }

        fun shouldStopVoiceService(command: String): Boolean {
            return command in VOICE_SERVICE_STOP_COMMANDS
        }

        fun cooldownMsFor(command: String): Long {
            return if (command == CommandBus.COMMAND_TAKE_PHOTO) {
                TAKE_PHOTO_COMMAND_COOLDOWN_MS
            } else {
                COMMAND_COOLDOWN_MS
            }
        }

        fun String.keepsCommandWindowOpen(): Boolean {
            return shouldKeepCommandWindowOpen(this)
        }
    }
}
