package com.vayu.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * VAYU Main Activity — Chat-style UI for interacting with the agent.
 *
 * Features:
 *   - Chat-style message display (agent thoughts + user commands)
 *   - Task input field + Execute button
 *   - Stop button to cancel running tasks
 *   - Service status indicator (RUNNING / OFFLINE)
 *   - Brain server health check
 *   - One-click accessibility settings shortcut
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollContainer: ScrollView
    private lateinit var taskInput: EditText
    private lateinit var btnExecute: Button
    private lateinit var btnStop: Button
    private lateinit var btnSettings: Button
    private lateinit var brainStatusText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val statusRefreshInterval = 3000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        startStatusRefresh()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    // ─────────────────────────────────────────────────────
    // View Initialization
    // ─────────────────────────────────────────────────────

    private fun initViews() {
        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)
        chatContainer = findViewById(R.id.chatContainer)
        scrollContainer = findViewById(R.id.scrollContainer)
        taskInput = findViewById(R.id.taskInput)
        btnExecute = findViewById(R.id.btnExecute)
        btnStop = findViewById(R.id.btnStop)
        btnSettings = findViewById(R.id.btnSettings)
        brainStatusText = findViewById(R.id.brainStatusText)
    }

    private fun setupListeners() {
        // Execute button — submit task to brain server
        btnExecute.setOnClickListener {
            val task = taskInput.text.toString().trim()
            if (task.isEmpty()) {
                Toast.makeText(this, "Please enter a task", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, "Please enable VAYU Accessibility Service first", Toast.LENGTH_LONG).show()
                openAccessibilitySettings()
                return@setOnClickListener
            }

            addMessage("👤 $task", isUser = true)
            val success = BrainClient.submitTask(task)
            if (success) {
                addMessage("🤖 Task accepted — starting execution...", isUser = false)
                taskInput.text.clear()
            } else {
                addMessage("⚠️ Failed to submit task — is the brain server running?", isUser = false)
            }
        }

        // Stop button — cancel running task
        btnStop.setOnClickListener {
            VayuService.stopRequested = true
            addMessage("⏹️ Stop requested — finishing current step...", isUser = false)
        }

        // Settings button — open accessibility settings
        btnSettings.setOnClickListener {
            openAccessibilitySettings()
        }
    }

    // ─────────────────────────────────────────────────────
    // Status Refresh
    // ─────────────────────────────────────────────────────

    private fun startStatusRefresh() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                refreshStatus()
                handler.postDelayed(this, statusRefreshInterval)
            }
        }, statusRefreshInterval)
    }

    private fun refreshStatus() {
        // Check accessibility service status
        val isServiceRunning = VayuService.isRunning
        val status = VayuService.currentStatus
        val step = VayuService.currentStep
        val task = VayuService.currentTaskDescription

        runOnUiThread {
            if (isServiceRunning) {
                statusIndicator.setBackgroundResource(R.drawable.status_dot_green)
                when (status) {
                    "EXECUTING" -> {
                        statusText.text = "Step $step/30 — ${task.take(30)}"
                        btnStop.visibility = View.VISIBLE
                    }
                    "DONE" -> {
                        statusText.text = "Task Complete ✓"
                        addMessage("✅ Task complete!", isUser = false)
                        btnStop.visibility = View.GONE
                    }
                    "FAIL" -> {
                        statusText.text = "Task Failed ✗"
                        addMessage("❌ Task failed. Check logs.", isUser = false)
                        btnStop.visibility = View.GONE
                    }
                    else -> {
                        statusText.text = "Ready"
                        btnStop.visibility = View.GONE
                    }
                }
            } else {
                statusIndicator.setBackgroundResource(R.drawable.status_dot_red)
                statusText.text = if (isAccessibilityEnabled()) "Connecting..." else "Service OFF"
                btnStop.visibility = View.GONE
            }

            // Check brain server health
            Thread {
                val healthy = BrainClient.checkHealth()
                runOnUiThread {
                    brainStatusText.text = if (healthy) "Brain: Online" else "Brain: Offline"
                    brainStatusText.setTextColor(
                        if (healthy) getColor(android.R.color.holo_green_dark)
                        else getColor(android.R.color.holo_red_dark)
                    )
                }
            }.start()
        }
    }

    // ─────────────────────────────────────────────────────
    // Chat Messages
    // ─────────────────────────────────────────────────────

    private fun addMessage(text: String, isUser: Boolean) {
        val textView = TextView(this).apply {
            this.text = text
            setPadding(24, 16, 24, 16)
            textSize = 14f

            if (isUser) {
                setTextColor(getColor(android.R.color.white))
                setBackgroundResource(R.drawable.msg_bubble_user)
            } else {
                setTextColor(getColor(android.R.color.black))
                setBackgroundResource(R.drawable.msg_bubble_agent)
            }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
                bottomMargin = 8
                gravity = if (isUser) android.view.Gravity.END else android.view.Gravity.START
            }
            layoutParams = params
        }

        chatContainer.addView(textView)
        scrollContainer.post { scrollContainer.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ─────────────────────────────────────────────────────
    // Accessibility Service Check
    // ─────────────────────────────────────────────────────

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.contains("com.vayu.android/com.vayu.android.VayuService")
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }
}
