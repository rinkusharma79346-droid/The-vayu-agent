package com.vayu.android

import android.app.Application

/**
 * Application class — initializes device info on startup.
 * Screen dimensions are captured once and available globally.
 */
class VayuApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Auto-detect screen size and device info
        DeviceInfo.init(this)

        // Store reference for service access
        instance = this
    }

    companion object {
        @Volatile
        private var instance: VayuApp? = null

        fun getApp(): VayuApp {
            return instance ?: throw IllegalStateException("VayuApp not initialized")
        }
    }
}
