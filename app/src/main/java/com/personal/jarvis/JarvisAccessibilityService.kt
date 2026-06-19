package com.personal.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class JarvisAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var foregroundPackage: String? = null

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            foregroundPackage = event.packageName?.toString()
        }
    }

    override fun onInterrupt() = Unit

    private fun handleCommand(command: String) {
        when (command) {
            CommandBus.COMMAND_OPEN_CAMERA -> CameraLauncher.open(this)
            CommandBus.COMMAND_OPEN_CAMERA_AND_TAKE_PHOTO -> {
                CameraLauncher.open(this)
                handler.postDelayed({ tapShutter() }, CAMERA_OPEN_DELAY_MS)
            }
            CommandBus.COMMAND_TAKE_PHOTO -> tapShutter()
            CommandBus.COMMAND_OPEN_FILTERS -> openFilters()
            CommandBus.COMMAND_SWITCH_CAMERA -> switchCamera()
            CommandBus.COMMAND_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            CommandBus.COMMAND_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun tapShutter() {
        val clicked = clickMatchingNode(
            listOf(
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
            ),
        )
        if (!clicked) tapFallback(CameraControlTarget.SHUTTER)
    }

    private fun openFilters() {
        val clicked = clickMatchingNode(
            listOf(
                "filter",
                "filters",
                "effect",
                "effects",
                "leica",
                "필터",
                "효과",
                "색감",
            ),
        )
        if (!clicked) tapFallback(CameraControlTarget.FILTERS)
    }

    private fun switchCamera() {
        val clicked = clickMatchingNode(
            listOf(
                "switch camera",
                "flip camera",
                "camera switch",
                "front camera",
                "rear camera",
                "전면",
                "후면",
                "카메라 전환",
                "렌즈 전환",
                "전환",
            ),
        )
        if (!clicked) tapFallback(CameraControlTarget.SWITCH_CAMERA)
    }

    private fun clickMatchingNode(keywords: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        val matches = mutableListOf<AccessibilityNodeInfo>()
        collectMatchingNodes(root, keywords, matches)

        val sorted = matches.sortedWith(
            compareByDescending<AccessibilityNodeInfo> { nodeScore(it, keywords) }
                .thenByDescending { visibleArea(it) },
        )

        for (node in sorted) {
            if (clickNodeOrClickableParent(node)) return true
        }
        return false
    }

    private fun collectMatchingNodes(
        node: AccessibilityNodeInfo,
        keywords: List<String>,
        matches: MutableList<AccessibilityNodeInfo>,
    ) {
        if (node.isVisibleToUser && nodeMatches(node, keywords)) {
            matches += node
        }

        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                collectMatchingNodes(child, keywords, matches)
            }
        }
    }

    private fun nodeMatches(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val haystack = nodeText(node)
        return keywords.any { haystack.contains(it.lowercase(Locale.KOREAN)) }
    }

    private fun nodeScore(node: AccessibilityNodeInfo, keywords: List<String>): Int {
        val haystack = nodeText(node)
        var score = 0
        if (node.isClickable) score += 30
        if (node.isEnabled) score += 10
        if (node.contentDescription != null) score += 20
        if (node.viewIdResourceName != null) score += 20
        keywords.forEach { keyword ->
            if (haystack.contains(keyword.lowercase(Locale.KOREAN))) score += keyword.length
        }
        return score
    }

    private fun nodeText(node: AccessibilityNodeInfo): String {
        return listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString(),
            node.viewIdResourceName,
            node.className?.toString(),
        ).joinToString(" ")
            .lowercase(Locale.KOREAN)
    }

    private fun clickNodeOrClickableParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_PARENT_SEARCH_DEPTH) {
            val candidate = current ?: return false
            if (candidate.isEnabled && candidate.isClickable) {
                return candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = candidate.parent
        }
        return false
    }

    private fun visibleArea(node: AccessibilityNodeInfo): Int {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect.width().coerceAtLeast(0) * rect.height().coerceAtLeast(0)
    }

    private fun tapFallback(target: CameraControlTarget) {
        val metrics = resources.displayMetrics
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
                x = if (portrait) width * 0.86f else width * 0.88f
                y = if (portrait) height * 0.14f else height * 0.18f
            }
        }

        tap(x, y)
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private enum class CameraControlTarget {
        SHUTTER,
        FILTERS,
        SWITCH_CAMERA,
    }

    companion object {
        private const val MAX_PARENT_SEARCH_DEPTH = 6
        private const val CAMERA_OPEN_DELAY_MS = 1500L
    }
}
