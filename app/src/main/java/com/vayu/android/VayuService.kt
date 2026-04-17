package com.vayu.android

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.ColorSpace
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * VAYU Accessibility Service — The "hands" of the autonomous agent.
 *
 * This service runs in the foreground (won't be killed by Android) and:
 *   1. Polls the brain server for tasks via GET /task/pending
 *   2. Executes a ReAct loop: screenshot → brain → action → repeat
 *   3. Captures screenshots via takeScreenshot()
 *   4. Sends them to the brain via POST /act
 *   5. Executes the returned action (tap, type, swipe, etc.)
 *   6. Reports results back via POST /task/result
 *
 * The service keeps running even when the VAYU app is in the background,
 * so it can control other apps (YouTube, CapCut, etc.) while executing tasks.
 */
class VayuService : AccessibilityService() {

    companion object {
        private const val TAG = "VAYU"
        private const val NOTIFICATION_CHANNEL_ID = "vayu_service"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_STEPS = 30
        private const val POLL_INTERVAL = 2000L       // Poll every 2 seconds
        private const val SCREENSHOT_DELAY = 1500L    // Wait before taking screenshot

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var currentStatus = "IDLE"
            private set

        @Volatile
        var currentStep = 0
            private set

        @Volatile
        var currentTaskDescription = ""
            private set

        // Stop signal from UI
        @Volatile
        var stopRequested = false

        // Callback to notify activity of status changes
        var statusListener: ((String, Int, String) -> Unit)? = null
    }

    private var pollThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isExecuting = false

    // ─────────────────────────────────────────────────────
    // Service Lifecycle
    // ─────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        currentStatus = "RUNNING"

        // Start as foreground service (Android won't kill it)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("VAYU is ready"))

        // Acquire wake lock (phone won't sleep during task execution)
        acquireWakeLock()

        // Update device info in case screen rotated
        DeviceInfo.update(this)

        // Start polling for tasks
        startPolling()

        Log.d(TAG, "VAYU service connected — ready for tasks")
        notifyStatus("RUNNING", 0, "")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        currentStatus = "STOPPED"
        stopPolling()
        releaseWakeLock()
        Log.d(TAG, "VAYU service destroyed")
        notifyStatus("STOPPED", 0, "")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for event-driven approach — we use polling
    }

    override fun onInterrupt() {
        Log.d(TAG, "VAYU service interrupted")
    }

    // ─────────────────────────────────────────────────────
    // Task Polling
    // ─────────────────────────────────────────────────────

    private fun startPolling() {
        pollThread = Thread({
            while (!Thread.currentThread().isInterrupted && isRunning) {
                try {
                    if (!isExecuting) {
                        val task = BrainClient.getPendingTask()
                        if (task != null) {
                            val taskDesc = task.optString("task", "")
                            val taskId = task.optInt("id", 0)
                            if (taskDesc.isNotEmpty()) {
                                Log.d(TAG, "Got task: $taskDesc")
                                executeTask(taskId, taskDesc)
                            }
                        }
                    }
                    Thread.sleep(POLL_INTERVAL)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Polling error: ${e.message}")
                    try { Thread.sleep(POLL_INTERVAL) } catch (_: InterruptedException) { break }
                }
            }
        }, "VAYU-Poll")
        pollThread?.isDaemon = true
        pollThread?.start()
    }

    private fun stopPolling() {
        pollThread?.interrupt()
        pollThread = null
    }

    // ─────────────────────────────────────────────────────
    // Task Execution — ReAct Loop
    // ─────────────────────────────────────────────────────

    private fun executeTask(taskId: Int, goal: String) {
        isExecuting = true
        currentTaskDescription = goal
        stopRequested = false

        updateNotification("Executing: ${goal.take(40)}")
        notifyStatus("EXECUTING", 0, goal)

        val history = JSONArray()

        Thread {
            try {
                for (step in 1..MAX_STEPS) {
                    if (stopRequested) {
                        Log.d(TAG, "Task stopped by user")
                        BrainClient.reportResult(taskId, "FAIL", "Stopped by user")
                        break
                    }

                    currentStep = step
                    notifyStatus("EXECUTING", step, goal)

                    // Step 1: Wait for UI to settle
                    Thread.sleep(SCREENSHOT_DELAY)

                    // Step 2: Take screenshot
                    val screenshotB64 = captureScreenshot()
                    if (screenshotB64.isEmpty()) {
                        Log.e(TAG, "Failed to capture screenshot")
                        Thread.sleep(2000)
                        continue
                    }

                    // Step 3: Build UI tree
                    val rootNode = rootInActiveWindow
                    val uiTree = UiTreeBuilder.build(rootNode)
                    rootNode?.recycle()

                    // Step 4: Send to brain
                    val action = BrainClient.sendToBrain(goal, screenshotB64, uiTree, history)
                    if (action == null) {
                        Log.e(TAG, "Brain returned null — retrying")
                        Thread.sleep(3000)
                        continue
                    }

                    // Step 5: Parse and execute action
                    val actionType = action.optString("action", "").uppercase()
                    Log.d(TAG, "Step $step: $actionType — ${action.toString().take(100)}")

                    // Check for completion
                    if (actionType == "DONE") {
                        val reason = action.optString("reason", "Task completed")
                        Log.d(TAG, "Task DONE: $reason")
                        BrainClient.reportResult(taskId, "DONE", reason)
                        notifyStatus("DONE", step, goal)
                        break
                    }

                    if (actionType == "FAIL") {
                        val reason = action.optString("reason", "Task failed")
                        Log.d(TAG, "Task FAIL: $reason")
                        BrainClient.reportResult(taskId, "FAIL", reason)
                        notifyStatus("FAIL", step, goal)
                        break
                    }

                    // Execute the action
                    val delay = ActionExecutor.execute(this, action)
                    Thread.sleep(delay)

                    // Add to history
                    history.put(action)
                }

                // Max steps reached
                if (currentStep >= MAX_STEPS && !stopRequested) {
                    BrainClient.reportResult(taskId, "FAIL", "Max steps ($MAX_STEPS) reached")
                    notifyStatus("FAIL", MAX_STEPS, goal)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Task execution error: ${e.message}", e)
                BrainClient.reportResult(taskId, "FAIL", "Error: ${e.message}")
                notifyStatus("FAIL", currentStep, goal)
            } finally {
                isExecuting = false
                currentTaskDescription = ""
                currentStep = 0
                updateNotification("VAYU is ready")
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────
    // Screenshot Capture
    // ─────────────────────────────────────────────────────

    private fun captureScreenshot(): String {
        return try {
            val screenshot = takeScreenshot(
                Display.DEFAULT_DISPLAY,
                this.mainLooper,
                { result ->
                    // Callback handled in the return path below
                },
                null
            )
            // Use the synchronous approach instead
            ""
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot capture error: ${e.message}")
            ""
        }
    }

    /**
     * Take screenshot using the Accessibility Service API.
     * This is the proper async approach with callback.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun takeScreenCapture(onResult: (String) -> Unit) {
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainLooper, { result ->
                try {
                    val hardwareBuffer = result.hardwareBuffer
                    val colorSpace = result.colorSpace
                    val base64 = ScreenCapture.processScreenshot(hardwareBuffer, colorSpace)
                    hardwareBuffer.close()
                    result.hardwareBuffer.close()
                    onResult(base64)
                } catch (e: Exception) {
                    Log.e(TAG, "Screenshot processing error: ${e.message}")
                    onResult("")
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot take error: ${e.message}")
            onResult("")
        }
    }

    // ─────────────────────────────────────────────────────
    // Foreground Service & Notification
    // ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "VAYU Agent",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "VAYU is running in the background"
            setShowBadge(false)
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("VAYU Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            // Service might be destroyed
        }
    }

    // ─────────────────────────────────────────────────────
    // WakeLock — Prevent phone from sleeping during tasks
    // ─────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "VAYU::TaskWakeLock"
            )
            wakeLock?.acquire(30 * 60 * 1000L) // 30 min max
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock error: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            // Ignore
        }
    }

    // ─────────────────────────────────────────────────────
    // Status Notification
    // ─────────────────────────────────────────────────────

    private fun notifyStatus(status: String, step: Int, task: String) {
        currentStatus = status
        currentStep = step
        currentTaskDescription = task
        statusListener?.invoke(status, step, task)
    }
}
