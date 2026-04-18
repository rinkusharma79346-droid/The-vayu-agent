package com.vayu.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable

object FloatingIndicator {

    private const val TAG = "VAYU-Overlay"

    @Volatile
    private var isShowing = false
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context, text: String) {
        if (isShowing) {
            updateText(text)
            return
        }

        handler.post {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager = wm

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 80
                }

                val bgDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 24f
                    setColor(Color.parseColor("#CC1A1A2E"))
                    setStroke(2, Color.parseColor("#6C63FF"))
                }

                val container = FrameLayout(context).apply {
                    background = bgDrawable
                    setPadding(24, 12, 24, 12)

                    addView(TextView(context).apply {
                        this.text = "VAYU: $text"
                        setTextColor(Color.parseColor("#6C63FF"))
                        textSize = 13f
                        setShadowLayer(2f, 0f, 0f, Color.BLACK)
                    })
                }

                var initialX = 0
                var initialY = 0
                var touchX = 0f
                var touchY = 0f
                var isDragging = false

                container.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            touchX = event.rawX
                            touchY = event.rawY
                            isDragging = false
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - touchX
                            val dy = event.rawY - touchY
                            if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                                isDragging = true
                                params.x = initialX + dx.toInt()
                                params.y = initialY + dy.toInt()
                                params.gravity = Gravity.TOP or Gravity.START
                                try { wm.updateViewLayout(v, params) } catch (_: Exception) {}
                            }
                            true
                        }
                        else -> false
                    }
                }

                overlayView = container
                wm.addView(container, params)
                isShowing = true
                Log.d(TAG, "Overlay shown: $text")
            } catch (e: Exception) {
                Log.e(TAG, "Cannot show overlay: ${e.message}")
            }
        }
    }

    fun updateText(text: String) {
        handler.post {
            try {
                val view = overlayView
                if (view != null && view is FrameLayout) {
                    val tv = view.getChildAt(0) as? TextView
                    tv?.text = "VAYU: $text"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update overlay error: ${e.message}")
            }
        }
    }

    fun hide(context: Context) {
        handler.post {
            try {
                overlayView?.let {
                    windowManager?.removeView(it)
                }
                overlayView = null
                windowManager = null
                isShowing = false
                Log.d(TAG, "Overlay hidden")
            } catch (e: Exception) {
                Log.e(TAG, "Hide overlay error: ${e.message}")
            }
        }
    }
}
