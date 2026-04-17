package com.vayu.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client for communicating with the brain server (brain_nvidia.py).
 *
 * Endpoints:
 *   GET  /task/pending  → Poll for new tasks
 *   POST /task/submit   → Submit task from UI
 *   POST /act           → Send screenshot + UI tree, receive action
 *   POST /task/result   → Report task completion/failure
 *   GET  /health        → Check brain server status
 *
 * All calls have timeouts and retry logic to handle slow API responses.
 */
object BrainClient {

    private const val BASE_URL = "http://localhost:8082"
    private const val CONNECT_TIMEOUT = 5000   // 5 seconds
    private const val READ_TIMEOUT = 90000     // 90 seconds (model can be slow)
    private const val MAX_RETRIES = 3

    // ─────────────────────────────────────────────────────
    // GET /task/pending — Poll for new tasks
    // ─────────────────────────────────────────────────────
    fun getPendingTask(): JSONObject? {
        return retryCall {
            val conn = createConnection("GET", "/task/pending")
            try {
                val response = readResponse(conn)
                if (conn.responseCode == 200) {
                    val json = JSONObject(response)
                    if (json.optString("task").isNotEmpty()) json else null
                } else null
            } finally {
                conn.disconnect()
            }
        }
    }

    // ─────────────────────────────────────────────────────
    // POST /task/submit — Submit a new task
    // ─────────────────────────────────────────────────────
    fun submitTask(task: String): Boolean {
        return retryCall(defaultValue = false) {
            val body = JSONObject().put("task", task)
            val conn = createConnection("POST", "/task/submit")
            try {
                writeBody(conn, body.toString())
                conn.responseCode == 200
            } finally {
                conn.disconnect()
            }
        }
    }

    // ─────────────────────────────────────────────────────
    // POST /act — Core loop: send screenshot, get action
    // ─────────────────────────────────────────────────────
    fun sendToBrain(
        goal: String,
        screenshotBase64: String,
        uiTree: JSONArray,
        history: JSONArray
    ): JSONObject? {
        return retryCall {
            val body = JSONObject().apply {
                put("goal", goal)
                put("screenshot", screenshotBase64)
                put("ui_tree", uiTree)
                put("history", history)
                put("screen_info", JSONObject(DeviceInfo.toMap()))
            }

            val conn = createConnection("POST", "/act", readTimeout = READ_TIMEOUT)
            try {
                writeBody(conn, body.toString())
                val response = readResponse(conn)
                if (conn.responseCode == 200) {
                    JSONObject(response)
                } else {
                    null
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    // ─────────────────────────────────────────────────────
    // POST /task/result — Report completion/failure
    // ─────────────────────────────────────────────────────
    fun reportResult(taskId: Int, status: String, reason: String) {
        retryCall(defaultValue = Unit) {
            val body = JSONObject().apply {
                put("task_id", taskId)
                put("status", status)
                put("reason", reason)
            }
            val conn = createConnection("POST", "/task/result")
            try {
                writeBody(conn, body.toString())
            } finally {
                conn.disconnect()
            }
        }
    }

    // ─────────────────────────────────────────────────────
    // GET /health — Check if brain server is running
    // ─────────────────────────────────────────────────────
    fun checkHealth(): Boolean {
        return try {
            val conn = createConnection("GET", "/health", connectTimeout = 2000, readTimeout = 2000)
            try {
                conn.responseCode == 200
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }

    // ─────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────

    private fun createConnection(
        method: String,
        path: String,
        connectTimeout: Int = CONNECT_TIMEOUT,
        readTimeout: Int = READ_TIMEOUT
    ): HttpURLConnection {
        val url = URL("$BASE_URL$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeout
        conn.readTimeout = readTimeout
        if (method == "POST") {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
        }
        return conn
    }

    private fun writeBody(conn: HttpURLConnection, body: String) {
        val writer = OutputStreamWriter(conn.outputStream, "UTF-8")
        writer.write(body)
        writer.flush()
        writer.close()
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val reader = BufferedReader(java.io.InputStreamReader(conn.inputStream, "UTF-8"))
        val response = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            response.append(line)
        }
        reader.close()
        return response.toString()
    }

    private fun <T> retryCall(defaultValue: T? = null, maxRetries: Int = MAX_RETRIES, block: () -> T?): T? {
        var lastException: Exception? = null
        for (i in 0 until maxRetries) {
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (i < maxRetries - 1) {
                    Thread.sleep((i + 1) * 1000L) // Exponential backoff: 1s, 2s, 3s
                }
            }
        }
        return defaultValue
    }
}
