package com.personal.jarvis

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class JarvisCommandExecutor(
    private val context: Context,
) {
    private val executionTracker = CommandExecutionTracker(SystemClock::elapsedRealtime)

    fun run(
        command: String,
        traceId: Long? = null,
        traceStartedAtMs: Long? = null,
        onCompleted: (Result) -> Unit,
    ) {
        val reservation = executionTracker.reserve(command, cooldownMsFor(command))
        if (reservation == null) {
            Log.d(TAG, "Ignored duplicate command: $command")
            onCompleted(
                Result(
                    command = command,
                    keepsCommandWindowOpen = command.keepsCommandWindowOpen(),
                    succeeded = true,
                    wasDispatched = false,
                ),
            )
            return
        }

        Log.d(TAG, "Running command: $command")
        val completionDelivered = AtomicBoolean(false)
        val complete: (Boolean) -> Unit = { succeeded ->
            if (completionDelivered.compareAndSet(false, true)) {
                executionTracker.complete(reservation, succeeded)
                onCompleted(
                    Result(
                        command = command,
                        keepsCommandWindowOpen = command.keepsCommandWindowOpen(),
                        succeeded = succeeded,
                        wasDispatched = true,
                    ),
                )
            }
        }

        when (command) {
            CommandBus.COMMAND_STOP_LISTENING -> {
                Log.d(TAG, "Closing current Jarvis command window")
                complete(true)
            }
            CommandBus.COMMAND_STOP_SERVICE -> {
                Log.d(TAG, "Stopping Jarvis voice service by command")
                complete(true)
            }
            CommandBus.COMMAND_OPEN_CAMERA -> complete(CameraLauncher.open(context))
            CommandBus.COMMAND_OPEN_FRONT_CAMERA,
            CommandBus.COMMAND_OPEN_REAR_CAMERA -> {
                CommandBus.send(command, "voice", traceId, traceStartedAtMs, complete)
            }
            CommandBus.COMMAND_OPEN_CAMERA_AND_TAKE_PHOTO -> {
                CommandBus.send(command, "voice", traceId, traceStartedAtMs, complete)
            }
            CommandBus.COMMAND_WAKE_SCREEN -> complete(ScreenController.wake(context))
            else -> CommandBus.send(command, "voice", traceId, traceStartedAtMs, complete)
        }
    }

    data class Result(
        val command: String,
        val keepsCommandWindowOpen: Boolean,
        val succeeded: Boolean,
        val wasDispatched: Boolean,
    )

    fun hasPendingExecution(): Boolean = executionTracker.hasActiveCommand()

    fun isCommandInFlight(command: String): Boolean = executionTracker.isActive(command)

    fun cancelPendingExecution() {
        executionTracker.cancelActive()
    }

    companion object {
        private const val TAG = "JarvisCommandExecutor"
        private const val COMMAND_COOLDOWN_MS = 1400L
        private const val TAKE_PHOTO_COMMAND_COOLDOWN_MS = 500L

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
