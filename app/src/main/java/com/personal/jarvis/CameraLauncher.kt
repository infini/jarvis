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
    fun openRear(context: Context): Boolean = open(context, CameraFacing.BACK)

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
        if (facing == null) return

        val front = facing == CameraFacing.FRONT
        putExtra("android.intent.extra.USE_FRONT_CAMERA", front)
        putExtra("android.intent.extras.CAMERA_FACING", if (front) 1 else 0)
        putExtra("android.intent.extras.LENS_FACING_FRONT", if (front) 1 else 0)
        putExtra("android.intent.extras.LENS_FACING_BACK", if (front) 0 else 1)
        putExtra("com.google.assistant.extra.USE_FRONT_CAMERA", front)
    }

    enum class CameraFacing {
        FRONT,
        BACK,
    }
}
