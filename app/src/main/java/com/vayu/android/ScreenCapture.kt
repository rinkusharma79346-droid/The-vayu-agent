package com.vayu.android

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import java.io.ByteArrayOutputStream

/**
 * Handles screenshot capture, processing, and base64 encoding.
 *
 * CRITICAL FIX: The original repo tried to compress hardware bitmaps
 * directly, which crashes on API 30-32. This version copies to a
 * software bitmap (ARGB_8888) before compressing.
 *
 * Optimization: Downscales to 720p max dimension and uses 40% JPEG quality
 * to reduce data size and speed up API calls on mobile.
 */
object ScreenCapture {

    private const val MAX_DIMENSION = 720       // Downscale to 720p for speed
    private const val JPEG_QUALITY = 40          // 40% quality — good enough for AI, small size

    /**
     * Process a screenshot from the Accessibility Service.
     * Returns base64-encoded JPEG string (no data URI prefix).
     */
    fun processScreenshot(hardwareBuffer: android.hardware.HardwareBuffer, colorSpace: ColorSpace?): String {
        // Step 1: Wrap hardware buffer
        val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace) ?: return ""

        // Step 2: Copy to software bitmap (CRITICAL — hardware bitmaps can't be compressed)
        val softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
        hardwareBitmap.recycle()

        if (softwareBitmap == null) return ""

        // Step 3: Downscale if needed
        val scaledBitmap = downscaleIfNeeded(softwareBitmap)
        if (scaledBitmap !== softwareBitmap) {
            softwareBitmap.recycle()
        }

        // Step 4: Compress to JPEG
        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        scaledBitmap.recycle()

        // Step 5: Base64 encode (NO_WRAP — no line breaks)
        val jpegBytes = outputStream.toByteArray()
        return Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
    }

    /**
     * Downscale bitmap if either dimension exceeds MAX_DIMENSION.
     * Maintains aspect ratio.
     */
    private fun downscaleIfNeeded(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height

        if (w <= MAX_DIMENSION && h <= MAX_DIMENSION) {
            return bitmap // Already small enough
        }

        val ratio = minOf(MAX_DIMENSION.toFloat() / w, MAX_DIMENSION.toFloat() / h)
        val newW = (w * ratio).toInt()
        val newH = (h * ratio).toInt()

        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
