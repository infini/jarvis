package com.personal.jarvis

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class JarvisAccessibilityService : AccessibilityService(), CommandBus.DirectReceiver {
    private val handler = Handler(Looper.getMainLooper())
    private var receiversRegistered = false
    private val cameraController by lazy {
        CameraAccessibilityController(this, handler)
    }
    private val stateIndicatorController by lazy {
        JarvisStateIndicatorController(this)
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val command = intent?.getStringExtra(CommandBus.EXTRA_COMMAND) ?: return
            val traceId = traceIdFrom(intent)
            val sentAtMs = intent.getLongExtra(CommandBus.EXTRA_SENT_AT_MS, 0L)
            val traceStartedAtMs = intent.getLongExtra(CommandBus.EXTRA_TRACE_STARTED_AT_MS, 0L)
            val busDelayMs = if (sentAtMs > 0L) JarvisLatencyTrace.elapsedSince(sentAtMs) else 0L
            val totalMs = if (traceStartedAtMs > 0L) {
                JarvisLatencyTrace.elapsedSince(traceStartedAtMs)
            } else {
                0L
            }
            JarvisLatencyTrace.logExternal(
                traceId = traceId,
                event = "accessibility_command_received",
                detail = "command=$command totalMs=$totalMs busDelayMs=$busDelayMs transport=broadcast",
            )
            handleCommand(command, traceId)
        }
    }
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = JarvisStateBus.stateFrom(intent) ?: return
            stateIndicatorController.update(state)
        }
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        registerServiceReceivers()
        CommandBus.registerDirectReceiver(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        releaseServiceBindings()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        releaseServiceBindings()
        super.onDestroy()
    }

    private fun registerServiceReceivers() {
        if (receiversRegistered) return

        val commandFilter = IntentFilter(CommandBus.ACTION_COMMAND)
        val stateFilter = IntentFilter(JarvisStateBus.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, commandFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(stateReceiver, stateFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, commandFilter)
            registerReceiver(stateReceiver, stateFilter)
        }
        receiversRegistered = true
    }

    private fun releaseServiceBindings() {
        CommandBus.unregisterDirectReceiver(this)
        if (receiversRegistered) {
            runCatching { unregisterReceiver(commandReceiver) }
            runCatching { unregisterReceiver(stateReceiver) }
            receiversRegistered = false
        }
        stateIndicatorController.dispose()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDirectCommand(
        command: String,
        source: String,
        traceId: Long?,
        traceStartedAtMs: Long,
        sentAtMs: Long,
    ): Boolean {
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
        handleCommand(command, traceId)
        return true
    }

    private fun handleCommand(command: String, traceId: Long?) {
        Log.d(TAG, "Handling command: $command")
        JarvisLatencyTrace.logExternal(traceId, "accessibility_command_dispatch_start", "command=$command")
        when (command) {
            CommandBus.COMMAND_OPEN_CAMERA -> cameraController.openCamera()
            CommandBus.COMMAND_OPEN_FRONT_CAMERA -> cameraController.openCameraFacing(CameraLauncher.CameraFacing.FRONT)
            CommandBus.COMMAND_OPEN_REAR_CAMERA -> cameraController.openCameraFacing(CameraLauncher.CameraFacing.BACK)
            CommandBus.COMMAND_OPEN_CAMERA_AND_TAKE_PHOTO -> cameraController.openCameraAndTakePhoto()
            CommandBus.COMMAND_TAKE_PHOTO -> cameraController.tapShutter()
            CommandBus.COMMAND_OPEN_FILTERS -> cameraController.openFilters()
            CommandBus.COMMAND_SWITCH_CAMERA -> cameraController.switchCamera()
            CommandBus.COMMAND_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            CommandBus.COMMAND_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            CommandBus.COMMAND_WAKE_SCREEN -> ScreenController.wake(this)
            CommandBus.COMMAND_SLEEP_SCREEN -> ScreenController.sleep(this)
        }
        JarvisLatencyTrace.logExternal(traceId, "accessibility_command_dispatch_return", "command=$command")
    }

    private fun traceIdFrom(intent: Intent): Long? {
        if (!intent.hasExtra(CommandBus.EXTRA_TRACE_ID)) return null
        return intent.getLongExtra(CommandBus.EXTRA_TRACE_ID, 0L).takeIf { it > 0L }
    }

    companion object {
        private const val TAG = "JarvisAccessibility"
    }
}
