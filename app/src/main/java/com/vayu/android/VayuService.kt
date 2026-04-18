package com.vayu.android

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VayuService : AccessibilityService() {

    companion object {
        private const val TAG = "VAYU"
        private const val NOTIFICATION_CHANNEL_ID = "vayu_service"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_STEPS = 30
        private const val POLL_INTERVAL = 1500L
        private const val SCREENSHOT_DELAY = 800L
        private const val DISPLAY_ID = 0

        @Volatile var isRunning = false; private set
        @Volatile var currentStatus = "IDLE"; private set
        @Volatile var currentStep = 0; private set
        @Volatile var currentTaskDescription = ""; private set

        val stopRequested = AtomicBoolean(false)

        var statusListener: ((String, Int, String) -> Unit)? = null
    }

    private var pollThread: Thread? = null
    private var executionThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isExecuting = false
    private val mainHandler = Handler(Looper.getMainLooper())

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
        killExecution()
        releaseWakeLock()
        notifyStatus("STOPPED", 0, "")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun startPolling() {
        pollThread = Thread({
            while (!Thread.currentThread().isInterrupted && isRunning) {
                try {
                    if (!isExecuting && !stopRequested.get()) {
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
                } catch (e: InterruptedException) { break
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

    fun immediateStop() {
        Log.d(TAG, "IMMEDIATE STOP requested")
        stopRequested.set(true)
        executionThread?.interrupt()
    }

    private fun killExecution() {
        executionThread?.interrupt()
        executionThread = null
    }

    private fun executeTask(taskId: Int, goal: String) {
        isExecuting = true
        currentTaskDescription = goal
        stopRequested.set(false)
        updateNotification("Executing: ${goal.take(40)}")
        notifyStatus("EXECUTING", 0, goal)
        FloatingIndicator.show(this, "Working...")

        val history = JSONArray()

        executionThread = Thread({
            try {
                for (step in 1..MAX_STEPS) {
                    if (stopRequested.get()) {
                        Log.d(TAG, "STOPPED by user at step $step")
                        BrainClient.reportResult(taskId, "FAIL", "Stopped by user")
                        notifyStatus("STOPPED", step, goal)
                        break
                    }

                    currentStep = step
                    notifyStatus("EXECUTING", step, goal)

                    try { Thread.sleep(SCREENSHOT_DELAY) } catch (_: InterruptedException) {
                        BrainClient.reportResult(taskId, "FAIL", "Stopped by user")
                        break
                    }

                    val screenshotB64 = captureScreenshot()
                    if (screenshotB64.isEmpty()) {
                        Log.w(TAG, "Screenshot empty at step $step")
                        try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                        continue
                    }

                    if (processStep(taskId, goal, screenshotB64, history, step) == null) break
                }

                if (currentStep >= MAX_STEPS && !stopRequested.get()) {
                    BrainClient.reportResult(taskId, "FAIL", "Max steps reached")
                    notifyStatus("FAIL", MAX_STEPS, goal)
                }
            } catch (e: InterruptedException) {
                Log.d(TAG, "Execution interrupted - stopped by user")
                BrainClient.reportResult(taskId, "FAIL", "Stopped by user")
                notifyStatus("STOPPED", currentStep, goal)
            } catch (e: Exception) {
                Log.e(TAG, "Task error: ${e.message}", e)
                BrainClient.reportResult(taskId, "FAIL", "Error: ${e.message}")
                notifyStatus("FAIL", currentStep, goal)
            } finally {
                isExecuting = false
                currentTaskDescription = ""
                currentStep = 0
                stopRequested.set(false)
                updateNotification("VAYU is ready")
                FloatingIndicator.hide(this)
            }
        }, "VAYU-Exec")
        executionThread?.start()
    }

    private fun processStep(taskId: Int, goal: String, screenshotB64: String, history: JSONArray, step: Int): Boolean? {
        if (stopRequested.get()) return null

        val rootNode = rootInActiveWindow
        val uiTree = UiTreeBuilder.build(rootNode)
        rootNode?.recycle()

        val action = BrainClient.sendToBrain(goal, screenshotB64, uiTree, history)
        if (action == null) {
            Log.e(TAG, "Brain returned null at step $step")
            return true
        }

        val actionType = action.optString("action", "").uppercase()
        Log.d(TAG, "Step $step: $actionType -> $action")

        if (actionType == "DONE") {
            BrainClient.reportResult(taskId, "DONE", action.optString("reason", "Done"))
            notifyStatus("DONE", step, goal)
            return null
        }
        if (actionType == "FAIL") {
            BrainClient.reportResult(taskId, "FAIL", action.optString("reason", "Failed"))
            notifyStatus("FAIL", step, goal)
            return null
        }

        val delay = ActionExecutor.execute(this, action)
        if (stopRequested.get()) return null
        try { Thread.sleep(delay) } catch (_: InterruptedException) { return null }
        history.put(action)
        return true
    }

    private fun captureScreenshot(): String {
        val latch = CountDownLatch(1)
        val result = arrayOf("")

        mainHandler.post {
            try {
                takeScreenshot(DISPLAY_ID, { cmd -> cmd.run() },
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            try {
                                val hwBuffer = screenshot.hardwareBuffer
                                val cs = screenshot.colorSpace
                                result[0] = ScreenCapture.processScreenshot(hwBuffer, cs)
                                hwBuffer.close()
                            } catch (e: Exception) {
                                Log.e(TAG, "Process screenshot error: ${e.message}")
                            } finally {
                                latch.countDown()
                            }
                        }
                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "Screenshot failed: $errorCode")
                            latch.countDown()
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "takeScreenshot error: ${e.message}")
                latch.countDown()
            }
        }

        latch.await(3, TimeUnit.SECONDS)
        return result[0]
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "VAYU Agent", NotificationManager.IMPORTANCE_LOW)
        channel.description = "VAYU is running in the background"
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("VAYU Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text)) }
        catch (_: Exception) {}
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VAYU::TaskWakeLock")
            wakeLock?.acquire(30 * 60 * 1000L)
        } catch (e: Exception) { Log.e(TAG, "WakeLock error: ${e.message}") }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
        catch (_: Exception) {}
    }

    private fun notifyStatus(status: String, step: Int, task: String) {
        currentStatus = status; currentStep = step; currentTaskDescription = task
        statusListener?.invoke(status, step, task)
    }
}
