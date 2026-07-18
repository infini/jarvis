package com.personal.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean

class CameraAccessibilityController(
    private val service: AccessibilityService,
    private val handler: Handler,
) {
    private val nodeMatcher = AccessibilityNodeMatcher(service)
    private val actionGeneration = SessionGeneration()
    private var pendingCompletion: ((Boolean) -> Unit)? = null
    private var lastKnownCameraFacing: CameraLauncher.CameraFacing? = null

    fun cancelPendingActions() {
        actionGeneration.invalidate()
        val completion = pendingCompletion
        pendingCompletion = null
        completion?.let { runCatching { it(false) } }
    }

    fun openCamera(): Boolean {
        val token = beginAction()
        val opened = CameraLauncher.open(service)
        actionGeneration.tryComplete(token)
        return opened
    }

    fun openCameraFacing(
        targetFacing: CameraLauncher.CameraFacing,
        onCompleted: (Boolean) -> Unit,
    ) {
        val token = beginAction()
        pendingCompletion = onCompleted
        val opened = when (targetFacing) {
            CameraLauncher.CameraFacing.FRONT -> CameraLauncher.openFront(service)
            CameraLauncher.CameraFacing.BACK -> CameraLauncher.openRear(service)
        }
        if (!opened) {
            completeAction(token, false)
            return
        }
        val scheduled = handler.postDelayed(
            { ensureCameraFacing(token, targetFacing, CAMERA_FACING_RETRY_COUNT) },
            CAMERA_OPEN_DELAY_MS,
        )
        if (!scheduled) completeAction(token, false)
    }

    fun openCameraAndTakePhoto(
        traceId: Long? = null,
        traceStartedAtMs: Long = 0L,
        onCompleted: (Boolean) -> Unit,
    ) {
        val token = beginAction()
        pendingCompletion = onCompleted
        if (!CameraLauncher.open(service)) {
            completeAction(token, false)
            return
        }
        val scheduled = handler.postDelayed(
            {
                if (!actionGeneration.isCurrent(token)) return@postDelayed
                tapShutterInternal(token, traceId, traceStartedAtMs) { succeeded ->
                    completeAction(token, succeeded)
                }
            },
            CAMERA_OPEN_DELAY_MS,
        )
        if (!scheduled) completeAction(token, false)
    }

    fun tapShutter(
        traceId: Long? = null,
        traceStartedAtMs: Long = 0L,
        onCompleted: (Boolean) -> Unit,
    ) {
        val token = beginAction()
        pendingCompletion = onCompleted
        tapShutterInternal(token, traceId, traceStartedAtMs) { succeeded ->
            completeAction(token, succeeded)
        }
    }

    private fun tapShutterInternal(
        token: Long,
        traceId: Long?,
        traceStartedAtMs: Long,
        onCompleted: (Boolean) -> Unit,
    ) {
        JarvisLatencyTrace.logExternal(
            traceId = traceId,
            event = "shutter_tap_start",
            detail = "target=SHUTTER totalMs=${totalSince(traceStartedAtMs)}",
        )
        tapFallback(token, CameraControlTarget.SHUTTER, FAST_SHUTTER_TAP_DURATION_MS) { coordinateSucceeded ->
            if (!actionGeneration.isCurrent(token)) return@tapFallback
            if (coordinateSucceeded) {
                logShutterResult(traceId, traceStartedAtMs, "coordinate")
                onCompleted(true)
                return@tapFallback
            }
            tapMatchingNode(token, SHUTTER_KEYWORDS) { nodeSucceeded ->
                if (!actionGeneration.isCurrent(token)) return@tapMatchingNode
                logShutterResult(traceId, traceStartedAtMs, if (nodeSucceeded) "node" else "failed")
                onCompleted(nodeSucceeded)
            }
        }
    }

    private fun logShutterResult(traceId: Long?, traceStartedAtMs: Long, result: String) {
        JarvisLatencyTrace.logExternal(
            traceId = traceId,
            event = "shutter_tap_dispatch",
            detail = "target=SHUTTER result=$result totalMs=${totalSince(traceStartedAtMs)}",
        )
    }

    fun openFilters(onCompleted: (Boolean) -> Unit) {
        val token = beginAction()
        pendingCompletion = onCompleted
        tapMatchingNode(token, FILTER_KEYWORDS) { nodeSucceeded ->
            if (!actionGeneration.isCurrent(token)) return@tapMatchingNode
            if (nodeSucceeded) {
                completeAction(token, true)
            } else {
                tapFallback(token, CameraControlTarget.FILTERS) { fallbackSucceeded ->
                    completeAction(token, fallbackSucceeded)
                }
            }
        }
    }

    fun switchCamera(onCompleted: (Boolean) -> Unit) {
        val token = beginAction()
        pendingCompletion = onCompleted
        clickCameraSwitchButton(token) { switched ->
            if (!actionGeneration.isCurrent(token)) return@clickCameraSwitchButton
            if (!switched) {
                completeAction(token, false)
                return@clickCameraSwitchButton
            }
            lastKnownCameraFacing = when (lastKnownCameraFacing) {
                CameraLauncher.CameraFacing.FRONT -> CameraLauncher.CameraFacing.BACK
                CameraLauncher.CameraFacing.BACK -> CameraLauncher.CameraFacing.FRONT
                null -> null
            }
            val scheduled = handler.postDelayed(
                {
                    if (!actionGeneration.isCurrent(token)) return@postDelayed
                    updateLastKnownCameraFacing()
                    completeAction(token, true)
                },
                CAMERA_FACING_RETRY_DELAY_MS,
            )
            if (!scheduled) completeAction(token, false)
        }
    }

    private fun ensureCameraFacing(
        token: Long,
        targetFacing: CameraLauncher.CameraFacing,
        retriesLeft: Int,
    ) {
        if (!actionGeneration.isCurrent(token)) return
        val currentFacing = currentCameraFacing()
        Log.d(TAG, "Camera facing target=$targetFacing current=$currentFacing")

        when {
            currentFacing == targetFacing -> {
                lastKnownCameraFacing = targetFacing
                completeAction(token, true)
            }
            currentFacing == null && retriesLeft > 0 -> {
                val scheduled = handler.postDelayed(
                    { ensureCameraFacing(token, targetFacing, retriesLeft - 1) },
                    CAMERA_FACING_RETRY_DELAY_MS,
                )
                if (!scheduled) completeAction(token, false)
            }
            currentFacing == null -> {
                Log.w(TAG, "Could not read current camera facing; skipping targeted switch")
                completeAction(token, false)
            }
            else -> {
                clickCameraSwitchButton(token) { switched ->
                    if (!actionGeneration.isCurrent(token)) return@clickCameraSwitchButton
                    if (!switched) {
                        completeAction(token, false)
                        return@clickCameraSwitchButton
                    }
                    val scheduled = handler.postDelayed(
                        {
                            verifyCameraFacingAfterSwitch(
                                token,
                                targetFacing,
                                CAMERA_FACING_VERIFY_RETRY_COUNT,
                            )
                        },
                        CAMERA_FACING_RETRY_DELAY_MS,
                    )
                    if (!scheduled) completeAction(token, false)
                }
            }
        }
    }

    private fun verifyCameraFacingAfterSwitch(
        token: Long,
        targetFacing: CameraLauncher.CameraFacing,
        retriesLeft: Int,
    ) {
        if (!actionGeneration.isCurrent(token)) return
        val currentFacing = currentCameraFacing()
        when {
            currentFacing == targetFacing -> {
                lastKnownCameraFacing = targetFacing
                completeAction(token, true)
            }
            retriesLeft > 0 -> {
                val scheduled = handler.postDelayed(
                    { verifyCameraFacingAfterSwitch(token, targetFacing, retriesLeft - 1) },
                    CAMERA_FACING_RETRY_DELAY_MS,
                )
                if (!scheduled) completeAction(token, false)
            }
            else -> {
                Log.w(TAG, "Camera facing switch could not be verified; target=$targetFacing current=$currentFacing")
                completeAction(token, false)
            }
        }
    }

    private fun beginAction(): Long {
        cancelPendingActions()
        return actionGeneration.begin()
    }

    private fun completeAction(token: Long, succeeded: Boolean) {
        if (!actionGeneration.tryComplete(token)) return
        val completion = pendingCompletion
        pendingCompletion = null
        completion?.let { runCatching { it(succeeded) } }
    }

    private fun tapMatchingNode(
        token: Long,
        keywords: List<String>,
        onCompleted: (Boolean) -> Unit,
    ) {
        val node = nodeMatcher.findBestMatchingNode(keywords)
        if (node == null) {
            onCompleted(false)
            return
        }
        tapNodeCenter(token, node, onCompleted)
    }

    private fun clickCameraSwitchButton(token: Long, onCompleted: (Boolean) -> Unit) {
        val node = findCameraSwitchNode()
        if (node == null) {
            tapFallback(token, CameraControlTarget.SWITCH_CAMERA, onCompleted = onCompleted)
            return
        }
        tapNodeCenter(token, node) { nodeSucceeded ->
            if (!actionGeneration.isCurrent(token)) return@tapNodeCenter
            if (nodeSucceeded) {
                onCompleted(true)
            } else {
                tapFallback(token, CameraControlTarget.SWITCH_CAMERA, onCompleted = onCompleted)
            }
        }
    }

    private fun currentCameraFacing(): CameraLauncher.CameraFacing? {
        val node = findCameraSwitchNode() ?: return null
        val description = node.contentDescription?.toString().orEmpty()
        return CameraFacingLabelParser.currentFacing(description)
    }

    private fun updateLastKnownCameraFacing() {
        val currentFacing = currentCameraFacing() ?: return
        lastKnownCameraFacing = currentFacing
        Log.d(TAG, "Updated camera facing=$currentFacing")
    }

    private fun findCameraSwitchNode(): AccessibilityNodeInfo? {
        return nodeMatcher.findBestMatchingNode(CAMERA_SWITCH_KEYWORDS)
    }

    private fun tapNodeCenter(
        token: Long,
        node: AccessibilityNodeInfo,
        onCompleted: (Boolean) -> Unit,
    ) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) {
            onCompleted(false)
            return
        }
        Log.d(TAG, "Tapping node center: ${node.viewIdResourceName} ${node.contentDescription} $rect")
        tap(token, rect.exactCenterX(), rect.exactCenterY(), onCompleted = onCompleted)
    }

    private fun tapFallback(
        token: Long,
        target: CameraControlTarget,
        durationMs: Long = DEFAULT_TAP_DURATION_MS,
        onCompleted: (Boolean) -> Unit,
    ) {
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
        tap(token, x, y, durationMs, onCompleted)
    }

    private fun tap(
        token: Long,
        x: Float,
        y: Float,
        durationMs: Long = DEFAULT_TAP_DURATION_MS,
        onCompleted: (Boolean) -> Unit,
    ) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val completionDelivered = AtomicBoolean(false)
        val deliver: (Boolean) -> Unit = { succeeded ->
            if (actionGeneration.isCurrent(token) && completionDelivered.compareAndSet(false, true)) {
                onCompleted(succeeded)
            }
        }
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                deliver(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                deliver(false)
            }
        }
        val accepted = service.dispatchGesture(gesture, callback, handler)
        if (!accepted) deliver(false)
    }

    private fun totalSince(traceStartedAtMs: Long): Long {
        return if (traceStartedAtMs > 0L) JarvisLatencyTrace.elapsedSince(traceStartedAtMs) else 0L
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
        private const val CAMERA_FACING_VERIFY_RETRY_COUNT = 2
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
    }
}
