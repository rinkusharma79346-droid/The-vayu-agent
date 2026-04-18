package com.vayu.android

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.util.Base64
import java.io.ByteArrayOutputStream

object ScreenCapture {

    private const val MAX_DIMENSION = 1080
    private const val JPEG_QUALITY = 60

    fun processScreenshot(hardwareBuffer: HardwareBuffer, colorSpace: ColorSpace?): String {
        val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace) ?: return ""
        val softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
        hardwareBitmap.recycle()
        if (softwareBitmap == null) return ""

        val scaledBitmap = downscaleIfNeeded(softwareBitmap)
        if (scaledBitmap !== softwareBitmap) softwareBitmap.recycle()

        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        scaledBitmap.recycle()

        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun downscaleIfNeeded(bitmap: Bitmap): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        if (w <= MAX_DIMENSION && h <= MAX_DIMENSION) return bitmap
        val ratio = minOf(MAX_DIMENSION.toFloat() / w, MAX_DIMENSION.toFloat() / h)
        return Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt(), (h * ratio).toInt(), true)
    }
}
