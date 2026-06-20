package com.personal.jarvis

import android.content.Context
import android.content.Intent
import android.provider.MediaStore

object CameraLauncher {
    fun open(context: Context, facing: CameraFacing? = null): Boolean {
        val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFacingExtras(facing)
        }

        return try {
            if (cameraIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(cameraIntent)
                true
            } else {
                openXiaomiCamera(context, facing)
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    fun openFront(context: Context): Boolean = open(context, CameraFacing.FRONT)

    private fun openXiaomiCamera(context: Context, facing: CameraFacing?): Boolean {
        val xiaomiCamera = context.packageManager.getLaunchIntentForPackage("com.android.camera")
            ?: return false
        return try {
            xiaomiCamera.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            xiaomiCamera.addFacingExtras(facing)
            context.startActivity(xiaomiCamera)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun Intent.addFacingExtras(facing: CameraFacing?) {
        if (facing != CameraFacing.FRONT) return

        putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        putExtra("android.intent.extras.CAMERA_FACING", 1)
        putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
        putExtra("android.intent.extras.LENS_FACING_BACK", 0)
        putExtra("com.google.assistant.extra.USE_FRONT_CAMERA", true)
    }

    enum class CameraFacing {
        FRONT,
    }
}
