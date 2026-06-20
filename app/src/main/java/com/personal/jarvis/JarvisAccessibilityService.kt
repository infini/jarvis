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

class JarvisAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
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
                detail = "command=$command totalMs=$totalMs busDelayMs=$busDelayMs",
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
    private val voiceServiceWatchdog = object : Runnable {
        override fun run() {
            ensureVoiceServiceRunning("accessibility_watchdog")
            handler.postDelayed(this, VOICE_SERVICE_WATCHDOG_INTERVAL_MS)
        }
    }
    private var lastVoiceAutoStartBlockReason: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val commandFilter = IntentFilter(CommandBus.ACTION_COMMAND)
        val stateFilter = IntentFilter(JarvisStateBus.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, commandFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(stateReceiver, stateFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, commandFilter)
            registerReceiver(stateReceiver, stateFilter)
        }
        ensureVoiceServiceRunning("accessibility_connected")
        handler.removeCallbacks(voiceServiceWatchdog)
        handler.postDelayed(voiceServiceWatchdog, VOICE_SERVICE_WATCHDOG_INTERVAL_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(voiceServiceWatchdog)
        runCatching { unregisterReceiver(commandReceiver) }
        runCatching { unregisterReceiver(stateReceiver) }
        stateIndicatorController.dispose()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun ensureVoiceServiceRunning(source: String) {
        if (JarvisVoiceService.isRunning) return

        val blockReason = JarvisVoiceServiceStarter.autoStartBlockReason(this)
        if (blockReason != null) {
            if (blockReason != lastVoiceAutoStartBlockReason) {
                Log.d(TAG, "Voice service auto-start blocked: $blockReason")
                lastVoiceAutoStartBlockReason = blockReason
            }
            return
        }

        lastVoiceAutoStartBlockReason = null
        JarvisVoiceServiceStarter.start(this, source)
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
        private const val VOICE_SERVICE_WATCHDOG_INTERVAL_MS = 15000L
    }
}
