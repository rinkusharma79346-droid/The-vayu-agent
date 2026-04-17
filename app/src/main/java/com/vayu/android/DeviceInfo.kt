package com.vayu.android

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Auto-detects and stores device screen information.
 * Called once on app startup via VayuApp.
 *
 * This info is sent to the brain server with every /act request,
 * so the model knows the exact screen dimensions and can calculate
 * accurate tap/swipe coordinates.
 */
object DeviceInfo {

    data class ScreenInfo(
        val width: Int,
        val height: Int,
        val density: Float,
        val densityDpi: Int,
        val scaledDensity: Float,
        val isPortrait: Boolean
    )

    private var _screenInfo: ScreenInfo? = null
    val screenInfo: ScreenInfo
        get() = _screenInfo ?: ScreenInfo(1080, 2400, 2.625f, 420, 2.625f, true)

    fun init(context: Context) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)

        _screenInfo = ScreenInfo(
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            density = metrics.density,
            densityDpi = metrics.densityDpi,
            scaledDensity = metrics.scaledDensity,
            isPortrait = metrics.heightPixels > metrics.widthPixels
        )
    }

    /** Update when screen rotates */
    fun update(context: Context) {
        init(context)
    }

    /** JSON-serializable map for sending to brain server */
    fun toMap(): Map<String, Any> {
        val info = screenInfo
        return mapOf(
            "width" to info.width,
            "height" to info.height,
            "density" to info.density,
            "density_dpi" to info.densityDpi,
            "scaled_density" to info.scaledDensity,
            "orientation" to if (info.isPortrait) "portrait" else "landscape"
        )
    }

    override fun toString(): String {
        val info = screenInfo
        return "${info.width}x${info.height} @ ${info.densityDpi}dpi (${if (info.isPortrait) "portrait" else "landscape"})"
    }
}
