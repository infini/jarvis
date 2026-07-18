package com.personal.jarvis

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class JarvisAccessibilityService : AccessibilityService(), CommandBus.DirectReceiver {
    private val handler = Handler(Looper.getMainLooper())
    private val cameraController by lazy {
        CameraAccessibilityController(this, handler)
    }
    private val stateIndicatorController by lazy {
        JarvisStateIndicatorController(this)
    }

    private val stateListener = JarvisStateBus.Listener(stateIndicatorController::update)

    override fun onServiceConnected() {
        super.onServiceConnected()
        CommandBus.registerDirectReceiver(this)
        JarvisStateBus.addListener(stateListener)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        releaseServiceBindings()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        releaseServiceBindings()
        super.onDestroy()
    }

    private fun releaseServiceBindings() {
        cameraController.cancelPendingActions()
        CommandBus.unregisterDirectReceiver(this)
        JarvisStateBus.removeListener(stateListener)
        stateIndicatorController.dispose()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun cancelPendingCommands() {
        cameraController.cancelPendingActions()
    }

    override fun onDirectCommand(
        command: String,
        source: String,
        traceId: Long?,
        traceStartedAtMs: Long,
        sentAtMs: Long,
        onCompleted: (Boolean) -> Unit,
    ) {
        val busDelayMs = JarvisLatencyTrace.elapsedSince(sentAtMs)
        val totalMs = if (traceStartedAtMs > 0L) {
            JarvisLatencyTrace.elapsedSince(traceStartedAtMs)
        } else {
            0L
        }
        JarvisLatencyTrace.logExternal(
            traceId = traceId,
            event = "accessibility_command_received",
            detail = "command=$command totalMs=$totalMs busDelayMs=$busDelayMs transport=direct source=$source",
        )
        handleCommand(command, traceId, traceStartedAtMs, onCompleted)
    }

    private fun handleCommand(
        command: String,
        traceId: Long?,
        traceStartedAtMs: Long,
        onCompleted: (Boolean) -> Unit,
    ) {
        Log.d(TAG, "Handling command: $command")
        JarvisLatencyTrace.logExternal(
            traceId = traceId,
            event = "accessibility_command_dispatch_start",
            detail = "command=$command totalMs=${totalSince(traceStartedAtMs)}",
        )
        if (command == CommandBus.COMMAND_OPEN_CAMERA_AND_TAKE_PHOTO) {
            cameraController.openCameraAndTakePhoto(traceId, traceStartedAtMs) { succeeded ->
                completeDispatch(command, traceId, traceStartedAtMs, succeeded, onCompleted)
            }
            return
        }
        val targetFacing = when (command) {
            CommandBus.COMMAND_OPEN_FRONT_CAMERA -> CameraLauncher.CameraFacing.FRONT
            CommandBus.COMMAND_OPEN_REAR_CAMERA -> CameraLauncher.CameraFacing.BACK
            else -> null
        }
        if (targetFacing != null) {
            cameraController.openCameraFacing(targetFacing) { succeeded ->
                completeDispatch(command, traceId, traceStartedAtMs, succeeded, onCompleted)
            }
            return
        }
        val asynchronousCameraAction: (((Boolean) -> Unit) -> Unit)? = when (command) {
            CommandBus.COMMAND_TAKE_PHOTO -> { completion ->
                cameraController.tapShutter(traceId, traceStartedAtMs, completion)
            }
            CommandBus.COMMAND_OPEN_FILTERS -> cameraController::openFilters
            CommandBus.COMMAND_SWITCH_CAMERA -> cameraController::switchCamera
            else -> null
        }
        if (asynchronousCameraAction != null) {
            asynchronousCameraAction { succeeded ->
                completeDispatch(command, traceId, traceStartedAtMs, succeeded, onCompleted)
            }
            return
        }

        val succeeded = when (command) {
            CommandBus.COMMAND_OPEN_CAMERA -> cameraController.openCamera()
            CommandBus.COMMAND_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            CommandBus.COMMAND_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            CommandBus.COMMAND_WAKE_SCREEN -> ScreenController.wake(this)
            CommandBus.COMMAND_SLEEP_SCREEN -> ScreenController.sleep(this)
            else -> false
        }
        completeDispatch(command, traceId, traceStartedAtMs, succeeded, onCompleted)
    }

    private fun completeDispatch(
        command: String,
        traceId: Long?,
        traceStartedAtMs: Long,
        succeeded: Boolean,
        onCompleted: (Boolean) -> Unit,
    ) {
        JarvisLatencyTrace.logExternal(
            traceId = traceId,
            event = "accessibility_command_dispatch_return",
            detail = "command=$command succeeded=$succeeded totalMs=${totalSince(traceStartedAtMs)}",
        )
        runCatching { onCompleted(succeeded) }
    }

    private fun totalSince(traceStartedAtMs: Long): Long {
        return if (traceStartedAtMs > 0L) JarvisLatencyTrace.elapsedSince(traceStartedAtMs) else 0L
    }

    companion object {
        private const val TAG = "JarvisAccessibility"
    }
}
