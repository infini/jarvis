package com.personal.jarvis

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class AccessibilityNodeMatcher(
    private val service: AccessibilityService,
) {
    fun findBestMatchingNode(keywords: List<String>): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        val matches = mutableListOf<AccessibilityNodeInfo>()
        collectMatchingNodes(root, keywords, matches)

        val sorted = matches.sortedWith(
            compareByDescending<AccessibilityNodeInfo> { nodeScore(it, keywords) }
                .thenByDescending { visibleArea(it) },
        )

        return sorted.firstOrNull()
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

    private fun visibleArea(node: AccessibilityNodeInfo): Int {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect.width().coerceAtLeast(0) * rect.height().coerceAtLeast(0)
    }
}
