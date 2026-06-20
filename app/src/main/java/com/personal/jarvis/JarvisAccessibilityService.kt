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

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val command = intent?.getStringExtra(CommandBus.EXTRA_COMMAND) ?: return
            handleCommand(command)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter(CommandBus.ACTION_COMMAND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(commandReceiver) }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun handleCommand(command: String) {
        Log.d(TAG, "Handling command: $command")
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
    }

    companion object {
        private const val TAG = "JarvisAccessibility"
    }
}
