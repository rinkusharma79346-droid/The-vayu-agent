#!/usr/bin/env python3
"""
brain_nvidia.py — VAYU Brain Server (NVIDIA API Edition)
Matches the APK's actual protocol:
    GET  /task/pending  → Poll for new tasks
    POST /task/submit   → Submit task from UI
    POST /act           → Send screenshot + UI tree, receive action
    POST /task/result   → Report task completion/failure
    GET  /health        → Health check

Model: microsoft/phi-4-multimodal-instruct (vision-capable)
API:   NVIDIA Playground (OpenAI-compatible)
       POST https://integrate.api.nvidia.com/v1/chat/completions

CRITICAL: Content format must be:
  - Plain STRING when no screenshot (array format causes HTTP 403 on phi-4)
  - ARRAY format only when screenshot is included
"""

import os
import sys
import json
import time
import base64
import re
import traceback
import threading

from flask import Flask, request, jsonify

try:
    import requests as req_lib
    HAS_REQUESTS = True
except ImportError:
    HAS_REQUESTS = False
    import urllib.request
    import urllib.error

# ═══════════════════════════════════════════════════════════════════
# CONFIGURATION
# ═══════════════════════════════════════════════════════════════════

NVIDIA_API_KEY = os.environ.get("NVIDIA_API_KEY", "")
NVIDIA_BASE_URL = "https://integrate.api.nvidia.com/v1/chat/completions"
NVIDIA_MODEL = "microsoft/phi-4-multimodal-instruct"

MAX_STEPS = 30
MAX_TOKENS = 1024
TEMPERATURE = 0.2
PORT = 8082

# ═══════════════════════════════════════════════════════════════════
# SYSTEM PROMPT — Must match APK's expected action format
# ═══════════════════════════════════════════════════════════════════

SYSTEM_PROMPT = """You are VAYU, an autonomous AI agent controlling an Android phone.
You receive a screenshot of the phone screen and a UI tree describing interactive elements.
Your job is to decide the next action to complete the user's task.

RESPOND WITH ONLY A JSON OBJECT — no markdown, no explanation, no extra text.

Available actions:
- TAP: {"action":"TAP","x":500,"y":300}
- LONG_PRESS: {"action":"LONG_PRESS","x":500,"y":300}
- SWIPE: {"action":"SWIPE","x1":540,"y1":1500,"x2":540,"y2":500,"duration_ms":500}
- SCROLL: {"action":"SCROLL","direction":"down"}  (direction: up|down|left|right)
- TYPE: {"action":"TYPE","text":"hello world"}
- PRESS_BACK: {"action":"PRESS_BACK"}
- PRESS_HOME: {"action":"PRESS_HOME"}
- PRESS_RECENTS: {"action":"PRESS_RECENTS"}
- OPEN_APP: {"action":"OPEN_APP","package":"com.google.android.youtube"}
- WAIT: {"action":"WAIT","ms":2000}
- DONE: {"action":"DONE","reason":"Task completed successfully"}
- FAIL: {"action":"FAIL","reason":"Cannot proceed because..."}

IMPORTANT RULES:
1. ALWAYS respond with valid JSON only — no markdown fences, no commentary
2. Use the UI tree to find exact coordinates — bounds are [left,top][right,bottom]
3. For TAP, aim for the CENTER of the target element
4. For SCROLL, use "down" to see more content, "up" to go back
5. TYPE replaces all text in the focused input field
6. OPEN_APP requires the exact package name (e.g., com.google.android.youtube)
7. Use WAIT after opening apps or navigating (they need time to load)
8. Use DONE when the task is fully completed
9. Use FAIL only if you cannot proceed after multiple attempts
10. The screen_info tells you the device resolution — use it for coordinate calculations"""

# ═══════════════════════════════════════════════════════════════════
# GLOBALS — Task Queue
# ═══════════════════════════════════════════════════════════════════

task_queue = []
task_counter = 0
task_results = {}
lock = threading.Lock()

# ═══════════════════════════════════════════════════════════════════
# NVIDIA API CALL
# ═══════════════════════════════════════════════════════════════════

def call_nvidia_api(system_prompt, context_text, screenshot_b64=None):
    """
    Call NVIDIA API with proper content format:
      - No screenshot → content is a plain STRING (array format causes 403)
      - With screenshot → content is an ARRAY with text + image_url
    """
    if not NVIDIA_API_KEY:
        return None, "NVIDIA_API_KEY environment variable is not set"

    # Build user content
    if screenshot_b64:
        user_content = [
            {"type": "text", "text": context_text},
            {
                "type": "image_url",
                "image_url": {
                    "url": f"data:image/jpeg;base64,{screenshot_b64}"
                }
            }
        ]
    else:
        # CRITICAL: plain string when no screenshot (array format causes 403)
        user_content = context_text

    payload = {
        "model": NVIDIA_MODEL,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_content}
        ],
        "max_tokens": MAX_TOKENS,
        "temperature": TEMPERATURE,
    }

    headers = {
        "Authorization": f"Bearer {NVIDIA_API_KEY}",
        "Content-Type": "application/json",
    }

    try:
        if HAS_REQUESTS:
            resp = req_lib.post(
                NVIDIA_BASE_URL,
                headers=headers,
                json=payload,
                timeout=90
            )
        else:
            req = urllib.request.Request(
                NVIDIA_BASE_URL,
                data=json.dumps(payload).encode("utf-8"),
                headers=headers,
                method="POST"
            )
            resp_obj = urllib.request.urlopen(req, timeout=90)
            class UrllibResp:
                def __init__(self, r):
                    self.status_code = r.getcode()
                    self._data = r.read().decode("utf-8")
                def json(self):
                    return json.loads(self._data)
                @property
                def text(self):
                    return self._data
            resp = UrllibResp(resp_obj)

        if resp.status_code != 200:
            error_text = resp.text if hasattr(resp, 'text') else str(resp.status_code)
            print(f"[API] HTTP {resp.status_code}: {error_text[:500]}")
            return None, f"HTTP {resp.status_code}: {error_text[:200]}"

        data = resp.json()
        content = data.get("choices", [{}])[0].get("message", {}).get("content", "")
        return content, None

    except Exception as e:
        print(f"[API] Exception: {e}")
        traceback.print_exc()
        return None, str(e)


# ═══════════════════════════════════════════════════════════════════
# JSON EXTRACTION — Multi-tier strategy
# ═══════════════════════════════════════════════════════════════════

def extract_json(text):
    """Extract JSON from model response using multi-tier strategy."""
    if not text:
        return None

    # Tier 1: Direct parse
    try:
        return json.loads(text.strip())
    except json.JSONDecodeError:
        pass

    # Tier 2: Extract from markdown fence
    fence_match = re.search(r'```(?:json)?\s*\n?(.*?)\n?```', text, re.DOTALL)
    if fence_match:
        try:
            return json.loads(fence_match.group(1).strip())
        except json.JSONDecodeError:
            pass

    # Tier 3: Find first { ... } block
    brace_match = re.search(r'\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}', text, re.DOTALL)
    if brace_match:
        try:
            return json.loads(brace_match.group(0))
        except json.JSONDecodeError:
            pass

    # Tier 4: Outermost { to }
    start = text.find('{')
    end = text.rfind('}')
    if start != -1 and end > start:
        try:
            return json.loads(text[start:end+1])
        except json.JSONDecodeError:
            pass

    return None


# ═══════════════════════════════════════════════════════════════════
# CONTEXT BUILDER — Format the UI tree + screen info for the model
# ═══════════════════════════════════════════════════════════════════

def build_context(goal, ui_tree, history, screen_info):
    """Build the text context sent to the model alongside the screenshot."""
    parts = []

    # Task
    parts.append(f"TASK: {goal}")

    # Screen info
    if screen_info:
        w = screen_info.get("width", "?")
        h = screen_info.get("height", "?")
        dpi = screen_info.get("density_dpi", "?")
        orientation = screen_info.get("orientation", "?")
        parts.append(f"SCREEN: {w}x{h} @ {dpi}dpi ({orientation})")

    # UI Tree (formatted for readability)
    if ui_tree and len(ui_tree) > 0:
        parts.append("UI ELEMENTS ON SCREEN:")
        for i, node in enumerate(ui_tree[:50]):  # Cap at 50 nodes
            cls = node.get("class", "View")
            text = node.get("text", "")
            desc = node.get("desc", "")
            bounds = node.get("bounds", "")
            clickable = "clickable" if node.get("clickable") else ""
            editable = "editable" if node.get("editable") else ""
            scrollable = "scrollable" if node.get("scrollable") else ""

            flags = ", ".join(filter(None, [clickable, editable, scrollable]))
            label = text or desc or ""
            if label:
                parts.append(f"  [{i}] {cls} \"{label}\" {bounds} {flags}")
            else:
                parts.append(f"  [{i}] {cls} {bounds} {flags}")

    # History (last 5 actions)
    if history and len(history) > 0:
        parts.append("RECENT ACTIONS:")
        for h in history[-5:]:
            action = h.get("action", "?")
            if action == "TAP":
                parts.append(f"  - TAP ({h.get('x','?')}, {h.get('y','?')})")
            elif action == "TYPE":
                parts.append(f"  - TYPE \"{h.get('text','')}\"")
            elif action == "SWIPE":
                parts.append(f"  - SWIPE ({h.get('x1','?')},{h.get('y1','?')}) -> ({h.get('x2','?')},{h.get('y2','?')})")
            elif action == "SCROLL":
                parts.append(f"  - SCROLL {h.get('direction','?')}")
            elif action == "OPEN_APP":
                parts.append(f"  - OPEN_APP {h.get('package','?')}")
            else:
                parts.append(f"  - {action}")

    parts.append("\nLook at the screenshot and decide the next action. Respond with JSON only.")

    return "\n".join(parts)


# ═══════════════════════════════════════════════════════════════════
# FLASK APP
# ═══════════════════════════════════════════════════════════════════

app = Flask(__name__)


@app.route("/health", methods=["GET"])
def health():
    """Health check endpoint."""
    return jsonify({
        "status": "ok",
        "model": NVIDIA_MODEL,
        "api_key_set": bool(NVIDIA_API_KEY),
        "pending_tasks": len(task_queue),
    })


@app.route("/task/submit", methods=["POST"])
def task_submit():
    """Submit a new task for VAYU to execute."""
    global task_counter

    data = request.get_json(force=True) if request.is_json else request.form.to_dict()
    task_desc = data.get("task") or data.get("description") or data.get("goal", "")

    if not task_desc:
        return jsonify({"error": "No task description provided"}), 400

    with lock:
        task_counter += 1
        task_id = task_counter
        task_queue.append({
            "id": task_id,
            "task": task_desc,
            "submitted_at": time.time(),
        })

    print(f"[Task] New task #{task_id}: {task_desc}")
    return jsonify({"status": "accepted", "task_id": task_id, "task": task_desc})


@app.route("/task/pending", methods=["GET"])
def task_pending():
    """Poll for the next pending task (called by VayuService every 2 seconds)."""
    with lock:
        if task_queue:
            task = task_queue.pop(0)
            return jsonify({"task": task["task"], "id": task["id"]})

    return jsonify({"task": None})


@app.route("/task/result", methods=["POST"])
def task_result():
    """Receive task completion/failure result from VayuService."""
    data = request.get_json(force=True) if request.is_json else request.form.to_dict()

    task_id = data.get("task_id", 0)
    status = data.get("status", "UNKNOWN")
    reason = data.get("reason", "")

    with lock:
        task_results[task_id] = {
            "status": status,
            "reason": reason,
            "completed_at": time.time(),
        }

    print(f"[Result] Task #{task_id}: {status} — {reason}")
    return jsonify({"status": "recorded"})


@app.route("/act", methods=["POST"])
def act():
    """
    Core endpoint — called by VayuService with screenshot + UI tree.
    Returns the next action to execute.

    Expected request format:
    {
        "goal": "Open YouTube",
        "screenshot": "base64_jpeg_string",
        "ui_tree": [...],
        "history": [...],
        "screen_info": {"width": 1080, "height": 2400, ...}
    }

    Returns action in APK format:
    {"action": "TAP", "x": 500, "y": 300}
    {"action": "DONE", "reason": "Task completed"}
    """
    data = request.get_json(force=True) if request.is_json else {}

    goal = data.get("goal", "")
    screenshot_b64 = data.get("screenshot", "")
    ui_tree = data.get("ui_tree", [])
    history = data.get("history", [])
    screen_info = data.get("screen_info", {})

    if not goal:
        return jsonify({"action": "FAIL", "reason": "No goal provided"})

    # Build context text for the model
    context_text = build_context(goal, ui_tree, history, screen_info)

    # Call NVIDIA API
    print(f"[Act] Calling {NVIDIA_MODEL} for goal: {goal[:50]}...")

    response_text, error = call_nvidia_api(
        system_prompt=SYSTEM_PROMPT,
        context_text=context_text,
        screenshot_b64=screenshot_b64 if screenshot_b64 else None
    )

    if error:
        print(f"[Act] API Error: {error}")
        return jsonify({"action": "FAIL", "reason": f"Brain API error: {error}"})

    # Parse response
    parsed = extract_json(response_text)

    if parsed and isinstance(parsed, dict):
        # Validate action type
        action = parsed.get("action", "").upper()
        valid_actions = [
            "TAP", "LONG_PRESS", "SWIPE", "SCROLL", "TYPE",
            "PRESS_BACK", "PRESS_HOME", "PRESS_RECENTS",
            "OPEN_APP", "WAIT", "DONE", "FAIL"
        ]

        if action not in valid_actions:
            print(f"[Act] Invalid action '{action}' — returning FAIL")
            return jsonify({"action": "FAIL", "reason": f"Invalid action: {action}"})

        # Ensure action is uppercase (model sometimes returns lowercase)
        parsed["action"] = action

        print(f"[Act] → {action}: {json.dumps(parsed)[:100]}")
        return jsonify(parsed)
    else:
        print(f"[Act] Could not parse JSON: {response_text[:200]}")
        return jsonify({"action": "FAIL", "reason": f"Could not parse model response"})


# Keep /step endpoint for backwards compatibility
@app.route("/step", methods=["POST"])
def step():
    """Legacy endpoint — redirects to /act logic."""
    return act()


# ═══════════════════════════════════════════════════════════════════
# ENTRY POINT
# ═══════════════════════════════════════════════════════════════════

if __name__ == "__main__":
    if not NVIDIA_API_KEY:
        print("╔══════════════════════════════════════════════════════════╗")
        print("║  ERROR: NVIDIA_API_KEY not set!                         ║")
        print("║  Run: export NVIDIA_API_KEY='nvapi-your-key-here'       ║")
        print("╚══════════════════════════════════════════════════════════╝")
        sys.exit(1)

    print("╔══════════════════════════════════════════════════════════╗")
    print("║  VAYU Brain Server — NVIDIA API Edition                 ║")
    print(f"║  Model:  {NVIDIA_MODEL:<45}║")
    print(f"║  Port:   {PORT:<45}║")
    print(f"║  API Key: {'***' + NVIDIA_API_KEY[-6:] if len(NVIDIA_API_KEY) > 6 else 'SET':<44}║")
    print("║  Endpoints: /act, /task/pending, /task/submit,         ║")
    print("║             /task/result, /health                       ║")
    print("╚══════════════════════════════════════════════════════════╝")

    app.run(host="0.0.0.0", port=PORT, debug=False)
