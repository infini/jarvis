package com.personal.jarvis

import android.content.Context
import android.content.Intent
import android.provider.MediaStore

object CameraLauncher {
    fun open(context: Context): Boolean {
        val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            if (cameraIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(cameraIntent)
                true
            } else {
                openXiaomiCamera(context)
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun openXiaomiCamera(context: Context): Boolean {
        val xiaomiCamera = context.packageManager.getLaunchIntentForPackage("com.android.camera")
            ?: return false
        return try {
            xiaomiCamera.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(xiaomiCamera)
            true
        } catch (_: RuntimeException) {
            false
        }
    }
}
