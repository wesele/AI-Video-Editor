package com.smartvideo.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.smartvideo.app.MainActivity
import com.smartvideo.app.R
import com.smartvideo.app.SmartVideoApp
import com.smartvideo.app.data.api.GeminiApiClient
import com.smartvideo.app.data.model.AppSettings
import com.smartvideo.app.data.model.Segment
import com.smartvideo.app.media.Media3Engine
import com.smartvideo.app.media.VideoMetadataHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import android.util.Base64

sealed class ProcessStatus {
    object Idle : ProcessStatus()
    data class Analyzing(val step: Int, val progress: Float, val message: String, val partialSegments: List<Segment>) : ProcessStatus()
    data class AnalysisCompleted(val segments: List<Segment>) : ProcessStatus()
    data class Exporting(val progress: Float, val message: String) : ProcessStatus()
    data class ExportCompleted(val outputUri: Uri) : ProcessStatus()
    data class Error(val message: String) : ProcessStatus()
}

class VideoProcessingService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    private val _statusFlow = MutableStateFlow<ProcessStatus>(ProcessStatus.Idle)
    val statusFlow: StateFlow<ProcessStatus> = _statusFlow.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): VideoProcessingService = this@VideoProcessingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartVideo:ProcessingWakeLock")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(3600 * 1000L) // Max 1 hour
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    fun startAnalysis(
        videoUri: Uri,
        settings: AppSettings,
        styleInstruction: String = "通用剪辑",
        customPrompt: String = ""
    ) {
        acquireWakeLock()
        val notification = createNotification("正在准备视频分析...", 0)
        safeStartForeground(notification)

        serviceScope.launch {
            try {
                val media3Engine = Media3Engine(applicationContext)
                val geminiClient = GeminiApiClient(settings.geminiBaseUrl, settings.geminiApiKey)
                val meta = VideoMetadataHelper.extractMetadata(applicationContext, videoUri)
                val totalDur = meta.durationSec

                if (totalDur <= 0) {
                    throw IllegalArgumentException("无法获取有效视频时长")
                }

                // 采用 90 秒动态切片与 2fps 超轻量代理，显著减少分段往返开销
                val chunkWindow = 90.0
                val totalChunks = Math.max(1, Math.ceil(totalDur / chunkWindow).toInt())
                val allStage1Segments = mutableListOf<Segment>()
                val isPortrait = meta.width < meta.height

                // ==========================================
                // Stage 1: 分段视听事实提取（流水线预转码并行架构）
                // ==========================================
                // 预先启动第 1 段的切片代理转码
                var nextProxyDeferred: Deferred<File>? = serviceScope.async(Dispatchers.IO) {
                    val st = 0.0
                    val et = Math.min(totalDur, chunkWindow)
                    media3Engine.sliceChunkProxy(videoUri, st, et, 0, isPortrait)
                }

                for (idx in 0 until totalChunks) {
                    val st = idx * chunkWindow
                    val et = Math.min(totalDur, (idx + 1) * chunkWindow)

                    val msg = "[第 1 步 事实提取] 正在分析第 ${idx + 1}/$totalChunks 段..."
                    val progress = ((idx.toFloat() / totalChunks) * 75f)
                    updateNotification(msg, progress.toInt())
                    _statusFlow.value = ProcessStatus.Analyzing(1, progress, msg, allStage1Segments.toList())

                    // 1. 获取当前段的切片代理文件（若已在流水线后台转码完成，此处为 0 耗时即取）
                    val proxyFile = nextProxyDeferred!!.await()

                    // 2. 转 Base64（超轻量 2fps 视频仅几百 KB，瞬时处理）
                    val videoB64 = withContext(Dispatchers.IO) {
                        val videoBytes = proxyFile.readBytes()
                        val b64 = Base64.encodeToString(videoBytes, Base64.NO_WRAP)
                        if (proxyFile.exists()) proxyFile.delete()
                        b64
                    }

                    // 3. 关键流水线优化：在当前段发起 Gemini 远程分析的同时，硬件后台并行预转码下一段！
                    val nextIdx = idx + 1
                    nextProxyDeferred = if (nextIdx < totalChunks) {
                        serviceScope.async(Dispatchers.IO) {
                            val nst = nextIdx * chunkWindow
                            val net = Math.min(totalDur, (nextIdx + 1) * chunkWindow)
                            media3Engine.sliceChunkProxy(videoUri, nst, net, nextIdx, isPortrait)
                        }
                    } else null

                    // 4. 请求 Gemini 事实抽取（与下一段预转码硬件完全并行）
                    val chunkSegments = geminiClient.extractChunkFacts(
                        modelName = settings.defaultModel,
                        videoBase64 = videoB64,
                        chunkIndex = idx,
                        totalChunks = totalChunks,
                        startTime = st,
                        endTime = et,
                        styleInstruction = styleInstruction,
                        customPrompt = customPrompt
                    )
                    allStage1Segments.addAll(chunkSegments)

                    // 提示 JVM 及时回收单段 Base64 占用的临时堆内存
                    System.gc()

                    val doneMsg = "[第 1 步 事实提取] 第 ${idx + 1}/$totalChunks 段完成，已提取 ${allStage1Segments.size} 个事实事件"
                    val donePct = (((idx + 1).toFloat() / totalChunks) * 75f)
                    updateNotification(doneMsg, donePct.toInt())
                    _statusFlow.value = ProcessStatus.Analyzing(1, donePct, doneMsg, allStage1Segments.toList())
                }

                // ==========================================
                // Stage 2: 全片全局二次宏观研读与 1-10 分价值曲线计算
                // ==========================================
                val s2Msg = "[第 2 步 全局宏观研读] Gemini 正在通盘审视全片并生成 1-10 分价值曲线..."
                updateNotification(s2Msg, 80)
                _statusFlow.value = ProcessStatus.Analyzing(2, 80f, s2Msg, allStage1Segments.toList())

                val finalScoredSegments = geminiClient.globalMacroScore(
                    modelName = settings.defaultModel,
                    totalDuration = totalDur,
                    stage1Segments = allStage1Segments,
                    styleInstruction = styleInstruction,
                    customPrompt = customPrompt,
                    defaultFfSpeed = settings.defaultFfSpeed
                )

                val finishMsg = "大模型全局宏观研读完成！共生成 ${finalScoredSegments.size} 个剪辑分段"
                updateNotification(finishMsg, 100)
                _statusFlow.value = ProcessStatus.AnalysisCompleted(finalScoredSegments)

            } catch (t: Throwable) {
                t.printStackTrace()
                val errMsg = "分析失败: ${t.localizedMessage ?: t.message}"
                updateNotification(errMsg, 0)
                _statusFlow.value = ProcessStatus.Error(errMsg)
            } finally {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_DETACH)
            }
        }
    }

    fun startExport(
        videoUri: Uri,
        segments: List<Segment>,
        targetWidth: Int = 1280,
        targetHeight: Int = 720,
        muteFastForwardAudio: Boolean = false
    ) {
        acquireWakeLock()
        val notification = createNotification("正在准备视频合成导出...", 0)
        safeStartForeground(notification)

        serviceScope.launch {
            try {
                val media3Engine = Media3Engine(applicationContext)
                updateNotification("正在通过系统硬件 MediaCodec 极速变速拼接成片...", 20)
                _statusFlow.value = ProcessStatus.Exporting(20f, "正在拼接保留段与加速快进段...")

                val savedUri = media3Engine.exportComposition(
                    videoUri = videoUri,
                    segments = segments,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    muteFastForwardAudio = muteFastForwardAudio
                ) { pct ->
                    val p = (20f + pct * 0.8f).toInt()
                    updateNotification("成片合成导出中: $p%", p)
                    _statusFlow.value = ProcessStatus.Exporting(p.toFloat(), "成片合成导出中...")
                }

                updateNotification("视频导出成功！已存入系统相册 Movies/SmartVideo", 100)
                _statusFlow.value = ProcessStatus.ExportCompleted(savedUri)

            } catch (t: Throwable) {
                t.printStackTrace()
                val errMsg = "导出失败: ${t.localizedMessage ?: t.message}"
                updateNotification(errMsg, 0)
                _statusFlow.value = ProcessStatus.Error(errMsg)
            } finally {
                releaseWakeLock()
                try {
                    stopForeground(STOP_FOREGROUND_DETACH)
                } catch (_: Exception) {}
            }
        }
    }

    private fun safeStartForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    1001,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(1001, notification)
            }
        } catch (e: Exception) {
            Log.e("VideoProcessingService", "safeStartForeground warning: ${e.message}", e)
        }
    }

    private fun createNotification(contentText: String, progress: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, SmartVideoApp.CHANNEL_ID)
            .setContentTitle("SmartVideo AI 剪辑处理中")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String, progress: Int) {
        try {
            val notification = createNotification(contentText, progress)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(1001, notification)
        } catch (e: Exception) {
            Log.w("VideoProcessingService", "updateNotification failed: ${e.message}")
        }
    }
}
