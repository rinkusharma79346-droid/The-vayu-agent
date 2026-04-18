package com.vayu.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * Executes actions on the phone based on brain server responses.
 * Uses Accessibility Service APIs (NOT ADB) for all actions.
 */
object ActionExecutor {

    private const val DELAY_AFTER_TAP = 1000L
    private const val DELAY_AFTER_LONG_PRESS = 1500L
    private const val DELAY_AFTER_SWIPE = 1200L
    private const val DELAY_AFTER_TYPE = 800L
    private const val DELAY_AFTER_PRESS = 800L
    private const val DELAY_AFTER_OPEN_APP = 3000L
    private const val DELAY_AFTER_SCROLL = 1000L
    private const val DEFAULT_SWIPE_DURATION = 500L

    fun execute(service: AccessibilityService, action: JSONObject): Long {
        val actionType = action.optString("action", "").uppercase()

        return when (actionType) {
            "TAP" -> executeTap(service, action)
            "LONG_PRESS" -> executeLongPress(service, action)
            "SWIPE" -> executeSwipe(service, action)
            "SCROLL" -> executeScroll(service, action)
            "TYPE" -> executeType(service, action)
            "PRESS_BACK" -> executeGlobalAction(service, AccessibilityService.GLOBAL_ACTION_BACK, DELAY_AFTER_PRESS)
            "PRESS_HOME" -> executeGlobalAction(service, AccessibilityService.GLOBAL_ACTION_HOME, DELAY_AFTER_PRESS)
            "PRESS_RECENTS" -> executeGlobalAction(service, AccessibilityService.GLOBAL_ACTION_RECENTS, DELAY_AFTER_PRESS)
            "OPEN_APP" -> executeOpenApp(service, action)
            "WAIT" -> executeWait(action)
            "DONE" -> 0L
            "FAIL" -> 0L
            else -> 500L
        }
    }

    private fun executeTap(service: AccessibilityService, action: JSONObject): Long {
        val x = action.optInt("x", 540)
        val y = action.optInt("y", 960)
        dispatchClick(service, x, y, 100L)
        return DELAY_AFTER_TAP
    }

    private fun executeLongPress(service: AccessibilityService, action: JSONObject): Long {
        val x = action.optInt("x", 540)
        val y = action.optInt("y", 960)
        dispatchClick(service, x, y, 600L)
        return DELAY_AFTER_LONG_PRESS
    }

    private fun executeSwipe(service: AccessibilityService, action: JSONObject): Long {
        val x1 = action.optInt("x1", 540)
        val y1 = action.optInt("y1", 1500)
        val x2 = action.optInt("x2", 540)
        val y2 = action.optInt("y2", 500)
        val duration = action.optLong("duration_ms", DEFAULT_SWIPE_DURATION)

        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()

        service.dispatchGesture(gesture, null, null)
        return DELAY_AFTER_SWIPE
    }

    private fun executeScroll(service: AccessibilityService, action: JSONObject): Long {
        val direction = action.optString("direction", "down").lowercase()
        val screen = DeviceInfo.screenInfo

        val centerX = action.optInt("x", screen.width / 2)
        val centerY = action.optInt("y", screen.height / 2)
        val distance = (screen.height * 0.4).toInt()

        val (x1, y1, x2, y2) = when (direction) {
            "up" -> listOf(centerX, centerY + distance / 2, centerX, centerY - distance / 2)
            "down" -> listOf(centerX, centerY - distance / 2, centerX, centerY + distance / 2)
            "left" -> listOf(centerX + distance / 2, centerY, centerX - distance / 2, centerY)
            "right" -> listOf(centerX - distance / 2, centerY, centerX + distance / 2, centerY)
            else -> listOf(centerX, centerY - distance / 2, centerX, centerY + distance / 2)
        }

        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, DEFAULT_SWIPE_DURATION))
            .build()

        service.dispatchGesture(gesture, null, null)
        return DELAY_AFTER_SCROLL
    }

    private fun executeType(service: AccessibilityService, action: JSONObject): Long {
        val text = action.optString("text", "")
        if (text.isEmpty()) return 300L

        val rootNode = service.rootInActiveWindow ?: return 300L
        val focusedNode = findFocusNode(rootNode)

        if (focusedNode != null && focusedNode.isEditable) {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focusedNode.recycle()
        } else {
            focusedNode?.recycle()
        }

        rootNode.recycle()
        return DELAY_AFTER_TYPE
    }

    private fun executeOpenApp(service: AccessibilityService, action: JSONObject): Long {
        val packageName = action.optString("package", "")
        if (packageName.isEmpty()) return 500L

        try {
            val pm = service.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                service.startActivity(launchIntent)
            }
        } catch (e: Exception) {
            // App not found
        }

        return DELAY_AFTER_OPEN_APP
    }

    private fun executeWait(action: JSONObject): Long {
        return action.optLong("ms", 2000L)
    }

    private fun executeGlobalAction(service: AccessibilityService, action: Int, delay: Long): Long {
        service.performGlobalAction(action)
        return delay
    }

    private fun dispatchClick(service: AccessibilityService, x: Int, y: Int, duration: Long) {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()

        service.dispatchGesture(gesture, null, null)
    }

    private fun findFocusNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
    }
}
