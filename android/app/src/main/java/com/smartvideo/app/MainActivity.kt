package com.smartvideo.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.smartvideo.app.data.model.AppSettings
import com.smartvideo.app.service.VideoProcessingService
import com.smartvideo.app.ui.screens.MainScreen
import com.smartvideo.app.ui.theme.SmartVideoTheme

class MainActivity : ComponentActivity() {

    private var processingService by mutableStateOf<VideoProcessingService?>(null)
    private var isBound by mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as VideoProcessingService.LocalBinder
            processingService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            processingService = null
            isBound = false
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Notification permission granted/denied callback
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 请求通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            SmartVideoTheme {
                var appSettings by remember { mutableStateOf(AppSettings()) }

                MainScreen(
                    service = processingService,
                    settings = appSettings,
                    onUpdateSettings = { appSettings = it }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, VideoProcessingService::class.java)
        startService(intent) // Ensure service lifecycle
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
