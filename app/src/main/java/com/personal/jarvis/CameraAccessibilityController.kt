package com.personal.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class CameraAccessibilityController(
    private val service: AccessibilityService,
    private val handler: Handler,
) {
    private val nodeMatcher = AccessibilityNodeMatcher(service)
    private var lastKnownCameraFacing: CameraLauncher.CameraFacing? = null

    fun openCamera() {
        CameraLauncher.open(service)
    }

    fun openCameraFacing(targetFacing: CameraLauncher.CameraFacing) {
        when (targetFacing) {
            CameraLauncher.CameraFacing.FRONT -> CameraLauncher.openFront(service)
            CameraLauncher.CameraFacing.BACK -> CameraLauncher.openRear(service)
        }
        handler.postDelayed(
            { ensureCameraFacing(targetFacing, CAMERA_FACING_RETRY_COUNT) },
            CAMERA_OPEN_DELAY_MS,
        )
    }

    fun openCameraAndTakePhoto() {
        CameraLauncher.open(service)
        handler.postDelayed({ tapShutter() }, CAMERA_OPEN_DELAY_MS)
    }

    fun tapShutter() {
        val tapped = tapFallback(CameraControlTarget.SHUTTER, FAST_SHUTTER_TAP_DURATION_MS)
        if (!tapped) tapMatchingNode(SHUTTER_KEYWORDS)
    }

    fun openFilters() {
        val tapped = tapMatchingNode(FILTER_KEYWORDS)
        if (!tapped) tapFallback(CameraControlTarget.FILTERS)
    }

    fun switchCamera() {
        if (clickCameraSwitchButton()) {
            lastKnownCameraFacing = when (lastKnownCameraFacing) {
                CameraLauncher.CameraFacing.FRONT -> CameraLauncher.CameraFacing.BACK
                CameraLauncher.CameraFacing.BACK -> CameraLauncher.CameraFacing.FRONT
                null -> null
            }
            handler.postDelayed({ updateLastKnownCameraFacing() }, CAMERA_FACING_RETRY_DELAY_MS)
        }
    }

    private fun ensureCameraFacing(targetFacing: CameraLauncher.CameraFacing, retriesLeft: Int) {
        val currentFacing = currentCameraFacing()
        Log.d(TAG, "Camera facing target=$targetFacing current=$currentFacing")

        when {
            currentFacing == targetFacing -> {
                lastKnownCameraFacing = targetFacing
            }
            currentFacing == null && retriesLeft > 0 -> {
                handler.postDelayed(
                    { ensureCameraFacing(targetFacing, retriesLeft - 1) },
                    CAMERA_FACING_RETRY_DELAY_MS,
                )
            }
            currentFacing == null -> {
                Log.w(TAG, "Could not read current camera facing; skipping targeted switch")
            }
            clickCameraSwitchButton() -> {
                lastKnownCameraFacing = targetFacing
                handler.postDelayed({ updateLastKnownCameraFacing() }, CAMERA_FACING_RETRY_DELAY_MS)
            }
        }
    }

    private fun tapMatchingNode(keywords: List<String>): Boolean {
        val node = nodeMatcher.findBestMatchingNode(keywords) ?: return false
        return tapNodeCenter(node)
    }

    private fun clickCameraSwitchButton(): Boolean {
        val node = findCameraSwitchNode()
        if (node != null && tapNodeCenter(node)) return true

        return tapFallback(CameraControlTarget.SWITCH_CAMERA)
    }

    private fun currentCameraFacing(): CameraLauncher.CameraFacing? {
        val node = findCameraSwitchNode() ?: return null
        val description = node.contentDescription?.toString().orEmpty()
        val stateText = description
            .substringAfterLast(",", description)
            .substringAfterLast("，", description)
            .lowercase(Locale.KOREAN)
            .replace("\\s+".toRegex(), "")

        return when {
            FRONT_FACING_LABELS.any(stateText::contains) -> CameraLauncher.CameraFacing.FRONT
            REAR_FACING_LABELS.any(stateText::contains) -> CameraLauncher.CameraFacing.BACK
            else -> null
        }
    }

    private fun updateLastKnownCameraFacing() {
        val currentFacing = currentCameraFacing() ?: return
        lastKnownCameraFacing = currentFacing
        Log.d(TAG, "Updated camera facing=$currentFacing")
    }

    private fun findCameraSwitchNode(): AccessibilityNodeInfo? {
        return nodeMatcher.findBestMatchingNode(CAMERA_SWITCH_KEYWORDS)
    }

    private fun tapNodeCenter(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        Log.d(TAG, "Tapping node center: ${node.viewIdResourceName} ${node.contentDescription} $rect")
        return tap(rect.exactCenterX(), rect.exactCenterY())
    }

    private fun tapFallback(
        target: CameraControlTarget,
        durationMs: Long = DEFAULT_TAP_DURATION_MS,
    ): Boolean {
        val metrics = service.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val portrait = height >= width

        val x: Float
        val y: Float
        when (target) {
            CameraControlTarget.SHUTTER -> {
                x = if (portrait) width * 0.5f else width * 0.88f
                y = if (portrait) height * 0.88f else height * 0.5f
            }
            CameraControlTarget.FILTERS -> {
                x = if (portrait) width * 0.25f else width * 0.78f
                y = if (portrait) height * 0.88f else height * 0.72f
            }
            CameraControlTarget.SWITCH_CAMERA -> {
                x = if (portrait) width * 0.90f else width * 0.88f
                y = if (portrait) height * 0.87f else height * 0.18f
            }
        }

        Log.d(TAG, "Tapping fallback target=$target x=$x y=$y")
        return tap(x, y, durationMs)
    }

    private fun tap(
        x: Float,
        y: Float,
        durationMs: Long = DEFAULT_TAP_DURATION_MS,
    ): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private enum class CameraControlTarget {
        SHUTTER,
        FILTERS,
        SWITCH_CAMERA,
    }

    companion object {
        private const val TAG = "CameraAccessibility"
        private const val CAMERA_OPEN_DELAY_MS = 1500L
        private const val CAMERA_FACING_RETRY_DELAY_MS = 500L
        private const val CAMERA_FACING_RETRY_COUNT = 2
        private const val DEFAULT_TAP_DURATION_MS = 80L
        private const val FAST_SHUTTER_TAP_DURATION_MS = 45L
        private val SHUTTER_KEYWORDS = listOf(
            "com.android.camera:id/shutter_button",
            "shutter",
            "capture",
            "take photo",
            "take picture",
            "camera shutter",
            "촬영",
            "셔터",
            "사진 촬영",
            "사진 찍기",
            "拍照",
        )
        private val FILTER_KEYWORDS = listOf(
            "filter",
            "filters",
            "effect",
            "effects",
            "leica",
            "필터",
            "효과",
            "색감",
        )
        private val CAMERA_SWITCH_KEYWORDS = listOf(
            "com.android.camera:id/v9_camera_picker",
            "switch camera",
            "flip camera",
            "camera switch",
            "front camera",
            "rear camera",
            "전후면 카메라 전환",
            "카메라 전환",
            "렌즈 전환",
            "전환",
        )
        private val FRONT_FACING_LABELS = listOf("전면", "셀피", "셀카", "front", "selfie", "前置", "前摄")
        private val REAR_FACING_LABELS = listOf("후면", "후방", "rear", "back", "后置", "後置", "后摄")
    }
}
