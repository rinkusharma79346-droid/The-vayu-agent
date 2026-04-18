package com.vayu.android

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * VAYU Accessibility Service — The "hands" of the autonomous agent.
 */
class VayuService : AccessibilityService() {

    companion object {
        private const val TAG = "VAYU"
        private const val NOTIFICATION_CHANNEL_ID = "vayu_service"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_STEPS = 30
        private const val POLL_INTERVAL = 2000L
        private const val SCREENSHOT_DELAY = 1500L

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

        @Volatile
        var stopRequested = false

        var statusListener: ((String, Int, String) -> Unit)? = null
    }

    private var pollThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isExecuting = false
    private val serviceHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        currentStatus = "RUNNING"

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("VAYU is ready"))
        acquireWakeLock()
        DeviceInfo.update(this)
        startPolling()

        Log.d(TAG, "VAYU service connected")
        notifyStatus("RUNNING", 0, "")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        currentStatus = "STOPPED"
        stopPolling()
        releaseWakeLock()
        notifyStatus("STOPPED", 0, "")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.d(TAG, "VAYU service interrupted")
    }

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
                        BrainClient.reportResult(taskId, "FAIL", "Stopped by user")
                        break
                    }

                    currentStep = step
                    notifyStatus("EXECUTING", step, goal)

                    Thread.sleep(SCREENSHOT_DELAY)

                    val screenshotB64 = captureScreenshotSync()
                    if (screenshotB64.isEmpty()) {
                        Log.e(TAG, "Failed to capture screenshot, retrying...")
                        Thread.sleep(2000)
                        continue
                    }

                    val rootNode = rootInActiveWindow
                    val uiTree = UiTreeBuilder.build(rootNode)
                    rootNode?.recycle()

                    val action = BrainClient.sendToBrain(goal, screenshotB64, uiTree, history)
                    if (action == null) {
                        Log.e(TAG, "Brain returned null, retrying...")
                        Thread.sleep(3000)
                        continue
                    }

                    val actionType = action.optString("action", "").uppercase()
                    Log.d(TAG, "Step $step: $actionType")

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

                    val delay = ActionExecutor.execute(this, action)
                    Thread.sleep(delay)

                    history.put(action)
                }

                if (currentStep >= MAX_STEPS && !stopRequested) {
                    BrainClient.reportResult(taskId, "FAIL", "Max steps reached")
                    notifyStatus("FAIL", MAX_STEPS, goal)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Task error: ${e.message}", e)
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

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureScreenshotSync(): String {
        val latch = CountDownLatch(1)
        var result = ""

        serviceHandler.post {
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    { cmd -> cmd.run() },
                    object : TakeScreenshotCallback {
                        override fun onScreenshot(screenshot: ScreenshotResult) {
                            try {
                                val hardwareBuffer = screenshot.hardwareBuffer
                                val colorSpace = screenshot.colorSpace
                                result = ScreenCapture.processScreenshot(hardwareBuffer, colorSpace)
                                hardwareBuffer.close()
                                screenshot.hardwareBuffer.close()
                            } catch (e: Exception) {
                                Log.e(TAG, "Screenshot processing error: ${e.message}")
                            } finally {
                                latch.countDown()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "Screenshot failed with code: $errorCode")
                            latch.countDown()
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot take error: ${e.message}")
                latch.countDown()
            }
        }

        latch.await(5, TimeUnit.SECONDS)
        return result
    }

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
        } catch (_: Exception) {}
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VAYU::TaskWakeLock")
            wakeLock?.acquire(30 * 60 * 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock error: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (_: Exception) {}
    }

    private fun notifyStatus(status: String, step: Int, task: String) {
        currentStatus = status
        currentStep = step
        currentTaskDescription = task
        statusListener?.invoke(status, step, task)
    }
}
