#!/usr/bin/env python3
"""
vayu_mcp_server.py — VAYU MCP Bridge Server
=============================================
This server acts as a bridge between:
  - The VAYU Android APK (Accessibility Service)
  - An external AI brain (Super Z / any MCP client)

ARCHITECTURE:
  Phone APK  ←→  This Server (Termux)  ←→  ngrok tunnel  ←→  AI Brain
  
The APK sends screenshots and waits for actions.
The AI brain fetches screenshots, analyzes them, and sends back actions.
NO NVIDIA API NEEDED — the AI IS the brain.

APK Endpoints (same protocol as brain_nvidia.py):
  GET  /health          — Health check
  POST /task/submit     — Submit task from UI
  GET  /task/pending    — Poll for new tasks
  POST /act             — Send screenshot, block until action available
  POST /task/result     — Report task completion/failure

Brain Endpoints (for external AI):
  GET  /brain/state     — Get current pending screenshot + context
  POST /brain/action    — Set action for current step
  POST /brain/task      — Submit task directly (AI can start tasks)
  GET  /brain/status    — Get overall system status
"""

import os, sys, json, time, traceback, threading
from flask import Flask, request, jsonify

# ═══════════════════════════════════════════════════════════════════
# CONFIGURATION
# ═══════════════════════════════════════════════════════════════════

PORT = 8082
ACT_TIMEOUT = 120  # seconds to wait for brain to provide action

# ═══════════════════════════════════════════════════════════════════
# SHARED STATE
# ═══════════════════════════════════════════════════════════════════

# Task queue
task_queue = []
task_counter = 0
task_results = {}
task_lock = threading.Lock()

# Brain state — screenshot + context from APK, waiting for action
brain_lock = threading.Lock()
brain_state = None         # {goal, screenshot, ui_tree, history, screen_info, step, timestamp}
brain_action = None        # The action provided by the AI brain
brain_action_ready = threading.Event()  # Signals when action is available
brain_waiting = False      # Is /act currently blocking?

# Execution state
execution_status = "IDLE"  # IDLE, EXECUTING, DONE, FAIL
current_goal = ""

# ═══════════════════════════════════════════════════════════════════
# FLASK APP
# ═══════════════════════════════════════════════════════════════════

app = Flask(__name__)

# ───────────────────────────────────────────────────────────────────
# APK ENDPOINTS (same protocol as before)
# ───────────────────────────────────────────────────────────────────

@app.route("/health", methods=["GET"])
def health():
    return jsonify({
        "status": "ok",
        "mode": "mcp_bridge",
        "brain_waiting": brain_waiting,
        "execution_status": execution_status,
        "current_goal": current_goal,
        "pending_tasks": len(task_queue),
    })


@app.route("/task/submit", methods=["POST"])
def task_submit():
    global task_counter
    data = request.get_json(force=True) if request.is_json else request.form.to_dict()
    task_desc = data.get("task") or data.get("description") or data.get("goal", "")
    if not task_desc:
        return jsonify({"error": "No task description provided"}), 400
    with task_lock:
        task_counter += 1
        task_id = task_counter
        task_queue.append({"id": task_id, "task": task_desc, "submitted_at": time.time()})
    print(f"[Task] New task #{task_id}: {task_desc}")
    return jsonify({"status": "accepted", "task_id": task_id, "task": task_desc})


@app.route("/task/pending", methods=["GET"])
def task_pending():
    with task_lock:
        if task_queue:
            task = task_queue.pop(0)
            print(f"[Poll] Serving task #{task['id']}: {task['task']}")
            return jsonify({"task": task["task"], "id": task["id"]})
    return jsonify({"task": None})


@app.route("/task/result", methods=["POST"])
def task_result():
    global execution_status, current_goal
    data = request.get_json(force=True) if request.is_json else request.form.to_dict()
    task_id = data.get("task_id", 0)
    status = data.get("status", "UNKNOWN")
    reason = data.get("reason", "")
    with task_lock:
        task_results[task_id] = {"status": status, "reason": reason, "completed_at": time.time()}
    print(f"[Result] Task #{task_id}: {status} — {reason}")
    execution_status = status
    if status in ("DONE", "FAIL"):
        current_goal = ""
    return jsonify({"status": "recorded"})


@app.route("/act", methods=["POST"])
def act():
    """
    APK sends screenshot + context here.
    Server blocks until the AI brain provides an action.
    This is the key difference from brain_nvidia.py — instead of calling
    NVIDIA API, we wait for an external brain to analyze and respond.
    """
    global brain_state, brain_action, brain_waiting, execution_status, current_goal

    data = request.get_json(force=True) if request.is_json else {}

    goal = data.get("goal", "")
    screenshot_b64 = data.get("screenshot", "")
    ui_tree = data.get("ui_tree", [])
    history = data.get("history", [])
    screen_info = data.get("screen_info", {})
    step = data.get("step", 0)

    if not goal:
        return jsonify({"action": "FAIL", "reason": "No goal provided"})

    # Store state for the brain to pick up
    with brain_lock:
        brain_state = {
            "goal": goal,
            "screenshot": screenshot_b64,
            "ui_tree": ui_tree,
            "history": history,
            "screen_info": screen_info,
            "step": step,
            "timestamp": time.time(),
        }
        brain_action = None
        brain_action_ready.clear()
        brain_waiting = True

    execution_status = "EXECUTING"
    current_goal = goal

    has_screenshot = "YES" if screenshot_b64 else "NO"
    print(f"[Act] Step {step}: Waiting for brain — goal: {goal[:50]}... (screenshot: {has_screenshot})")

    # Wait for the brain to provide an action
    got_action = brain_action_ready.wait(timeout=ACT_TIMEOUT)

    with brain_lock:
        brain_waiting = False
        action = brain_action
        brain_state = None  # Clear state after action is consumed

    if not got_action or action is None:
        print(f"[Act] TIMEOUT — no action from brain in {ACT_TIMEOUT}s")
        return jsonify({"action": "FAIL", "reason": f"Brain timeout ({ACT_TIMEOUT}s)"})

    action_type = action.get("action", "UNKNOWN")
    print(f"[Act] Step {step}: Brain → {action_type}: {json.dumps(action)[:100]}")
    return jsonify(action)


# Legacy endpoint
@app.route("/step", methods=["POST"])
def step():
    return act()


# ───────────────────────────────────────────────────────────────────
# BRAIN ENDPOINTS (for external AI to interact with)
# ───────────────────────────────────────────────────────────────────

@app.route("/brain/state", methods=["GET"])
def brain_get_state():
    """
    External AI calls this to get the current screenshot + context.
    Returns the pending state that the APK sent via /act.
    """
    with brain_lock:
        if brain_state is None:
            return jsonify({"status": "no_pending_state", "message": "No screenshot waiting"})

        # Return everything except the raw screenshot (too large for JSON)
        # Instead return screenshot metadata + UI tree + goal
        state = {
            "status": "pending",
            "goal": brain_state["goal"],
            "has_screenshot": bool(brain_state["screenshot"]),
            "screenshot_size": len(brain_state["screenshot"]) if brain_state["screenshot"] else 0,
            "ui_tree": brain_state["ui_tree"],
            "history": brain_state["history"],
            "screen_info": brain_state["screen_info"],
            "step": brain_state["step"],
            "timestamp": brain_state["timestamp"],
        }
        return jsonify(state)


@app.route("/brain/screenshot", methods=["GET"])
def brain_get_screenshot():
    """
    External AI calls this to download the raw screenshot as base64.
    This is separate from /brain/state because screenshots are large.
    """
    with brain_lock:
        if brain_state is None or not brain_state.get("screenshot"):
            return jsonify({"error": "No screenshot available"}), 404

        return jsonify({
            "screenshot": brain_state["screenshot"],
            "format": "jpeg_base64",
        })


@app.route("/brain/screenshot_image", methods=["GET"])
def brain_get_screenshot_image():
    """
    Returns the screenshot as a raw JPEG image (binary).
    Useful for direct viewing or analysis tools.
    """
    import base64
    with brain_lock:
        if brain_state is None or not brain_state.get("screenshot"):
            return "No screenshot available", 404

        jpeg_data = base64.b64decode(brain_state["screenshot"])
        from flask import Response
        return Response(jpeg_data, mimetype="image/jpeg")


@app.route("/brain/action", methods=["POST"])
def brain_set_action():
    """
    External AI calls this to set the action for the current step.
    This unblocks the /act call and the APK receives the action.
    """
    data = request.get_json(force=True) if request.is_json else {}

    action = data.get("action")
    if not action:
        return jsonify({"error": "No action provided"}), 400

    with brain_lock:
        brain_action = action
        brain_action_ready.set()

    action_type = action.get("action", "UNKNOWN")
    print(f"[Brain] Action set: {action_type} — {json.dumps(action)[:100]}")
    return jsonify({"status": "action_set", "action": action_type})


@app.route("/brain/task", methods=["POST"])
def brain_submit_task():
    """
    External AI can submit a task directly.
    This is useful when the AI wants to start a task without
    the user typing it in the app.
    """
    global task_counter
    data = request.get_json(force=True) if request.is_json else {}
    task_desc = data.get("task") or data.get("goal", "")
    if not task_desc:
        return jsonify({"error": "No task description provided"}), 400

    with task_lock:
        task_counter += 1
        task_id = task_counter
        task_queue.append({"id": task_id, "task": task_desc, "submitted_at": time.time()})

    print(f"[Brain] Task submitted by AI: #{task_id}: {task_desc}")
    return jsonify({"status": "accepted", "task_id": task_id, "task": task_desc})


@app.route("/brain/status", methods=["GET"])
def brain_status():
    """
    Get overall system status — useful for the AI to check
    if there's a task running, if APK is connected, etc.
    """
    with brain_lock:
        has_pending = brain_state is not None
        pending_goal = brain_state["goal"] if brain_state else None
        pending_step = brain_state["step"] if brain_state else 0

    return jsonify({
        "execution_status": execution_status,
        "current_goal": current_goal or pending_goal,
        "current_step": pending_step,
        "brain_waiting_for_action": has_pending,
        "pending_tasks_in_queue": len(task_queue),
        "last_task_result": list(task_results.values())[-1] if task_results else None,
    })


# ═══════════════════════════════════════════════════════════════════
# ENTRY POINT
# ═══════════════════════════════════════════════════════════════════

if __name__ == "__main__":
    print("╔══════════════════════════════════════════════════════════╗")
    print("║  VAYU MCP Bridge Server                                 ║")
    print("║  Mode: External AI Brain (no NVIDIA API needed!)        ║")
    print(f"║  Port: {PORT:<45}║")
    print("║                                                         ║")
    print("║  APK Endpoints:                                         ║")
    print("║    GET  /health, /task/pending, /brain/status           ║")
    print("║    POST /task/submit, /act, /task/result                ║")
    print("║                                                         ║")
    print("║  Brain Endpoints (for AI):                              ║")
    print("║    GET  /brain/state, /brain/screenshot,                ║")
    print("║         /brain/screenshot_image, /brain/status          ║")
    print("║    POST /brain/action, /brain/task                      ║")
    print("╚══════════════════════════════════════════════════════════╝")
    print()
    print("  Next steps:")
    print("  1. Make sure VAYU APK is running with Accessibility Service")
    print("  2. Start ngrok: ngrok http 8082")
    print("  3. Give the ngrok URL to your AI brain")
    print("  4. Tell the AI your task — it will control your phone!")
    print()

    app.run(host="0.0.0.0", port=PORT, debug=False, threaded=True)
