package com.smartvideo.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class SmartVideoApp : Application() {

    companion object {
        const val CHANNEL_ID = "smartvideo_processing_channel"
        private const val TAG = "SmartVideoApp"
    }

    override fun onCreate() {
        super.onCreate()
        setupCrashHandler()
        createNotificationChannel()
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "FATAL CRASH on thread ${thread.name}", throwable)
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val crashFile = File(filesDir, "crash_latest.txt")
                crashFile.writeText(sw.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.channel_name)
            val descriptionText = getString(R.string.channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }
}
