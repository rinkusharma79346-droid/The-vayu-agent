package com.vayu.android

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

/**
 * Handles screenshot capture, processing, and base64 encoding.
 * Copies hardware bitmap to software bitmap before compressing (crash fix).
 * Downscales to 720p max and uses 40% JPEG quality for speed.
 */
object ScreenCapture {

    private const val MAX_DIMENSION = 720
    private const val JPEG_QUALITY = 40

    /**
     * Process a screenshot from HardwareBuffer.
     * Returns base64-encoded JPEG string (no data URI prefix).
     */
    fun processScreenshot(hardwareBuffer: HardwareBuffer, colorSpace: ColorSpace?): String {
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

    private fun downscaleIfNeeded(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height

        if (w <= MAX_DIMENSION && h <= MAX_DIMENSION) {
            return bitmap
        }

        val ratio = minOf(MAX_DIMENSION.toFloat() / w, MAX_DIMENSION.toFloat() / h)
        val newW = (w * ratio).toInt()
        val newH = (h * ratio).toInt()

        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
