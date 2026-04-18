package com.vayu.android

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a JSON representation of the on-screen UI tree.
 */
object UiTreeBuilder {

    private const val MAX_DEPTH = 15
    private const val MAX_NODES = 80

    fun build(rootNode: AccessibilityNodeInfo?): JSONArray {
        val tree = JSONArray()
        if (rootNode == null) return tree
        traverseNode(rootNode, tree, 0)
        return tree
    }

    private fun traverseNode(node: AccessibilityNodeInfo, tree: JSONArray, depth: Int) {
        if (depth > MAX_DEPTH || tree.length() >= MAX_NODES) return
        if (!node.isVisibleToUser) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        val className = node.className?.toString() ?: ""
        val isInteractive = node.isClickable || node.isFocusable || node.isEditable ||
                node.isCheckable || node.isScrollable || node.isLongClickable
        val hasContent = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
        val isLayoutContainer = isLayoutClass(className) && !hasContent && !isInteractive

        if (!isLayoutContainer) {
            val nodeJson = JSONObject().apply {
                put("class", simplifyClassName(className))
                put("text", node.text?.toString() ?: "")
                put("desc", node.contentDescription?.toString() ?: "")
                put("bounds", formatBounds(bounds))
                put("clickable", node.isClickable)
                put("editable", node.isEditable)
                put("focusable", node.isFocusable)
                put("scrollable", node.isScrollable)
                put("checkable", node.isCheckable)
                put("checked", node.isChecked)
                put("selected", node.isSelected)
            }
            tree.put(nodeJson)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, tree, depth + 1)
            child.recycle()
        }
    }

    private fun formatBounds(rect: Rect): String {
        return "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]"
    }

    private fun simplifyClassName(className: String): String {
        if (className.isEmpty()) return "View"
        val simple = className.substringAfterLast(".")
        return when {
            simple.startsWith("AppCompat") -> simple.removePrefix("AppCompat")
            simple.startsWith("Material") -> simple.removePrefix("Material")
            else -> simple
        }
    }

    private fun isLayoutClass(className: String): Boolean {
        val layoutClasses = setOf(
            "android.widget.LinearLayout",
            "android.widget.FrameLayout",
            "android.widget.RelativeLayout",
            "android.widget.ConstraintLayout",
            "androidx.constraintlayout.widget.ConstraintLayout",
            "androidx.coordinatorlayout.widget.CoordinatorLayout",
            "android.widget.ScrollView",
            "android.widget.HorizontalScrollView",
            "androidx.core.widget.NestedScrollView",
            "android.view.View"
        )
        return className in layoutClasses
    }
}
