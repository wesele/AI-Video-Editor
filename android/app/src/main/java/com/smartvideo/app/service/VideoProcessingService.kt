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
import com.google.gson.Gson
import com.smartvideo.app.data.api.GeminiApiClient
import com.smartvideo.app.data.model.AnalysisCheckpoint
import com.smartvideo.app.data.model.AnalysisResult
import com.smartvideo.app.data.model.AppSettings
import com.smartvideo.app.data.model.Segment
import com.smartvideo.app.media.Media3Engine
import com.smartvideo.app.media.VideoMetadataHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
    private var exportJob: Job? = null
    private var analysisJob: Job? = null
    private var activeMedia3Engine: Media3Engine? = null

    private val _statusFlow = MutableStateFlow<ProcessStatus>(ProcessStatus.Idle)
    val statusFlow: StateFlow<ProcessStatus> = _statusFlow.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): VideoProcessingService = this@VideoProcessingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val gson = Gson()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("SmartVideo 视频处理服务已就绪", 0)
        safeStartForeground(notification)
        return START_NOT_STICKY
    }

    private fun getCheckpointFile(videoUriStr: String): File {
        val dir = File(applicationContext.filesDir, "checkpoints")
        if (!dir.exists()) dir.mkdirs()
        val safeHash = Math.abs(videoUriStr.hashCode())
        return File(dir, "checkpoint_$safeHash.json")
    }

    fun saveCheckpoint(cp: AnalysisCheckpoint) {
        try {
            val file = getCheckpointFile(cp.videoUri)
            val json = gson.toJson(cp)
            file.writeText(json)
            Log.i("SmartVideoCP", "Checkpoint saved: chunk ${cp.completedChunks}/${cp.totalChunks}, segments=${cp.stage1Segments.size}")
        } catch (e: Exception) {
            Log.w("VideoProcessingService", "saveCheckpoint failed: ${e.message}")
        }
    }

    fun getCheckpoint(videoUriStr: String?): AnalysisCheckpoint? {
        if (videoUriStr.isNullOrEmpty()) return null
        return try {
            val file = getCheckpointFile(videoUriStr)
            if (file.exists()) {
                val json = file.readText()
                val cp = gson.fromJson(json, AnalysisCheckpoint::class.java)
                Log.i("SmartVideoCP", "Checkpoint loaded: chunk ${cp.completedChunks}/${cp.totalChunks}, segments=${cp.stage1Segments.size}")
                cp
            } else {
                Log.i("SmartVideoCP", "No checkpoint file found for uri: $videoUriStr")
                null
            }
        } catch (e: Exception) {
            Log.w("VideoProcessingService", "getCheckpoint error: ${e.message}")
            null
        }
    }

    fun clearCheckpoint(videoUriStr: String?) {
        if (videoUriStr.isNullOrEmpty()) return
        try {
            val file = getCheckpointFile(videoUriStr)
            if (file.exists()) {
                file.delete()
                Log.i("SmartVideoCP", "Checkpoint deleted for uri: $videoUriStr")
            }
        } catch (e: Exception) {
            Log.w("VideoProcessingService", "clearCheckpoint error: ${e.message}")
        }
    }

    private fun getResultFile(videoUriStr: String): File {
        val dir = File(applicationContext.filesDir, "results")
        if (!dir.exists()) dir.mkdirs()
        val safeHash = Math.abs(videoUriStr.hashCode())
        return File(dir, "result_$safeHash.json")
    }

    fun saveAnalysisResult(result: AnalysisResult) {
        try {
            val file = getResultFile(result.videoUri)
            val json = gson.toJson(result)
            file.writeText(json)
            Log.i("SmartVideoResult", "Analysis result permanently saved: ${result.segments.size} segments to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w("VideoProcessingService", "saveAnalysisResult failed: ${e.message}")
        }
    }

    fun getAnalysisResult(videoUriStr: String?): AnalysisResult? {
        if (videoUriStr.isNullOrEmpty()) return null
        return try {
            val file = getResultFile(videoUriStr)
            if (file.exists()) {
                val json = file.readText()
                val res = gson.fromJson(json, AnalysisResult::class.java)
                Log.i("SmartVideoResult", "Analysis result loaded: ${res.segments.size} segments")
                res
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w("VideoProcessingService", "getAnalysisResult error: ${e.message}")
            null
        }
    }

    fun clearAnalysisResult(videoUriStr: String?) {
        if (videoUriStr.isNullOrEmpty()) return
        try {
            val file = getResultFile(videoUriStr)
            if (file.exists()) {
                file.delete()
                Log.i("SmartVideoResult", "Analysis result deleted for uri: $videoUriStr")
            }
        } catch (e: Exception) {
            Log.w("VideoProcessingService", "clearAnalysisResult error: ${e.message}")
        }
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
        customPrompt: String = "",
        resume: Boolean = false,
        existingSegments: List<Segment> = emptyList()
    ) {
        acquireWakeLock()
        val notification = createNotification("正在准备视频分析...", 0)
        safeStartForeground(notification)

        analysisJob = serviceScope.launch {
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

                var concurrency = settings.concurrency.coerceIn(1, 8)
                val completedMap = java.util.concurrent.ConcurrentHashMap<Int, List<Segment>>()

                if (resume) {
                    val savedCp = getCheckpoint(videoUri.toString())
                    if (savedCp != null) {
                        if (savedCp.completedChunkIndices.isNotEmpty() && savedCp.stage1Segments.isNotEmpty()) {
                            savedCp.stage1Segments.forEach { seg ->
                                val chIdx = (seg.startTime / chunkWindow).toInt().coerceIn(0, totalChunks - 1)
                                val list = completedMap.getOrPut(chIdx) { mutableListOf() } as MutableList<Segment>
                                list.add(seg)
                            }
                            savedCp.completedChunkIndices.forEach { chIdx ->
                                if (!completedMap.containsKey(chIdx)) {
                                    completedMap[chIdx] = emptyList()
                                }
                            }
                            Log.i("VideoProcessingService", "Resuming from checkpoint with ${completedMap.size}/$totalChunks chunks")
                        } else if (savedCp.completedChunks > 0 && savedCp.completedChunks <= totalChunks) {
                            for (i in 0 until savedCp.completedChunks) {
                                val validStart = i * chunkWindow
                                val validEnd = Math.min(totalDur, (i + 1) * chunkWindow)
                                val segs = savedCp.stage1Segments.filter { it.startTime >= validStart - 0.5 && it.endTime <= validEnd + 0.5 }
                                completedMap[i] = segs
                            }
                            Log.i("VideoProcessingService", "Resuming from legacy checkpoint at chunk ${savedCp.completedChunks}/$totalChunks")
                        }
                    } else if (existingSegments.isNotEmpty()) {
                        val maxEnd = existingSegments.maxOfOrNull { it.endTime } ?: 0.0
                        val completedFromMem = (maxEnd / chunkWindow).toInt().coerceAtMost(totalChunks)
                        for (i in 0 until completedFromMem) {
                            val validStart = i * chunkWindow
                            val validEnd = Math.min(totalDur, (i + 1) * chunkWindow)
                            completedMap[i] = existingSegments.filter { it.startTime >= validStart - 0.5 && it.endTime <= validEnd + 0.5 }
                        }
                    }
                } else {
                    clearCheckpoint(videoUri.toString())
                }

                fun getSortedSegments(): List<Segment> {
                    val list = mutableListOf<Segment>()
                    completedMap.keys.sorted().forEach { k ->
                        completedMap[k]?.let { list.addAll(it) }
                    }
                    return list
                }

                // ==========================================
                // Stage 1: 分段视听事实提取（并发调度架构，支持 1-8 线程，默认 4）
                // ==========================================
                val pendingChunks = (0 until totalChunks).filter { !completedMap.containsKey(it) }
                if (pendingChunks.isNotEmpty()) {
                    val sem = Semaphore(concurrency)
                    val cpMutex = Mutex()
                    var completedCount = completedMap.size

                    val initSorted = getSortedSegments()
                    if (completedCount > 0) {
                        val initProgress = ((completedCount.toFloat() / totalChunks) * 75f)
                        val initMsg = "[第 1 步 事实提取] 已复用已保存的 $completedCount/$totalChunks 段事实..."
                        updateNotification(initMsg, initProgress.toInt())
                        _statusFlow.value = ProcessStatus.Analyzing(1, initProgress, initMsg, initSorted)
                    }

                    val jobs = pendingChunks.map { idx ->
                        serviceScope.async(Dispatchers.IO) {
                            sem.withPermit {
                                val st = idx * chunkWindow
                                val et = Math.min(totalDur, (idx + 1) * chunkWindow)

                                val msg = "[第 1 步 事实提取] 正在切片与分析第 ${idx + 1}/$totalChunks 段 (并发: $concurrency)..."
                                val progress = ((completedCount.toFloat() / totalChunks) * 75f)
                                updateNotification(msg, progress.toInt())
                                _statusFlow.value = ProcessStatus.Analyzing(1, progress, msg, getSortedSegments())

                                val proxyFile = media3Engine.sliceChunkProxy(videoUri, st, et, idx, isPortrait)
                                val videoB64 = try {
                                    val videoBytes = proxyFile.readBytes()
                                    Base64.encodeToString(videoBytes, Base64.NO_WRAP)
                                } finally {
                                    if (proxyFile.exists()) proxyFile.delete()
                                }

                                var chunkSegments: List<Segment>? = null
                                var lastError: Throwable? = null
                                for (attempt in 1..3) {
                                    try {
                                        chunkSegments = geminiClient.extractChunkFacts(
                                            modelName = settings.defaultModel,
                                            videoBase64 = videoB64,
                                            chunkIndex = idx,
                                            totalChunks = totalChunks,
                                            startTime = st,
                                            endTime = et,
                                            styleInstruction = styleInstruction,
                                            customPrompt = customPrompt
                                        )
                                        break
                                    } catch (e: Exception) {
                                        lastError = e
                                        Log.w("VideoProcessingService", "Chunk $idx attempt $attempt failed: ${e.message}")
                                        if (attempt < 3) {
                                            delay(1500L * attempt)
                                        }
                                    }
                                }

                                val resolvedSegments = chunkSegments ?: throw (lastError ?: RuntimeException("第 ${idx + 1} 段研读失败"))

                                cpMutex.withLock {
                                    completedMap[idx] = resolvedSegments
                                    completedCount++
                                    val sortedSegs = getSortedSegments()
                                    val cp = AnalysisCheckpoint(
                                        videoUri = videoUri.toString(),
                                        totalDuration = totalDur,
                                        chunkWindow = chunkWindow,
                                        totalChunks = totalChunks,
                                        completedChunks = completedMap.size,
                                        completedChunkIndices = completedMap.keys.sorted(),
                                        stage1Segments = sortedSegs,
                                        styleInstruction = styleInstruction,
                                        customPrompt = customPrompt
                                    )
                                    saveCheckpoint(cp)

                                    val doneMsg = "[第 1 步 事实提取] 已完成 ${completedCount}/$totalChunks 段！已提取 ${sortedSegs.size} 个事实事件 (并发: $concurrency)"
                                    val donePct = ((completedCount.toFloat() / totalChunks) * 75f)
                                    updateNotification(doneMsg, donePct.toInt())
                                    _statusFlow.value = ProcessStatus.Analyzing(1, donePct, doneMsg, sortedSegs)
                                }
                            }
                        }
                    }
                    jobs.awaitAll()
                }

                allStage1Segments.clear()
                allStage1Segments.addAll(getSortedSegments())

                // ==========================================
                // Stage 2: 全片全局二次宏观研读与 1-10 分价值曲线计算
                // ==========================================
                val s2Msg = "[第 2 步 全局宏观研读] Gemini 正在通盘审视全片并生成 1-10 分价值曲线..."
                updateNotification(s2Msg, 80)
                _statusFlow.value = ProcessStatus.Analyzing(2, 80f, s2Msg, allStage1Segments.toList())

                var finalScoredSegments: List<Segment>? = null
                var s2Error: Throwable? = null
                for (attempt in 1..3) {
                    try {
                        finalScoredSegments = geminiClient.globalMacroScore(
                            modelName = settings.defaultModel,
                            totalDuration = totalDur,
                            stage1Segments = allStage1Segments,
                            styleInstruction = styleInstruction,
                            customPrompt = customPrompt,
                            defaultFfSpeed = settings.defaultFfSpeed
                        )
                        break
                    } catch (e: Exception) {
                        s2Error = e
                        Log.w("VideoProcessingService", "Stage 2 attempt $attempt failed: ${e.message}")
                        if (attempt < 3) {
                            delay(2000L * attempt)
                        }
                    }
                }

                val finalSegments = finalScoredSegments ?: throw (s2Error ?: RuntimeException("全局宏观研读失败"))

                // 1. 永久保存最终完成的粗剪研读与价值曲线分段结果，彻底防止进程重启或导出失败丢失
                val finalResult = AnalysisResult(
                    videoUri = videoUri.toString(),
                    totalDuration = totalDur,
                    segments = finalSegments,
                    styleInstruction = styleInstruction,
                    customPrompt = customPrompt
                )
                saveAnalysisResult(finalResult)

                // 2. 清理中间断点文件（因为已经有了完整终态成果）
                clearCheckpoint(videoUri.toString())

                val finishMsg = "大模型全局宏观研读完成！共生成 ${finalSegments.size} 个剪辑分段"
                updateNotification(finishMsg, 100)
                _statusFlow.value = ProcessStatus.AnalysisCompleted(finalSegments)

            } catch (ce: CancellationException) {
                // 用户主动取消分析
            } catch (t: Throwable) {
                t.printStackTrace()
                val errMsg = "分析失败: ${t.localizedMessage ?: t.message}"
                updateNotification(errMsg, 0)
                _statusFlow.value = ProcessStatus.Error(errMsg)
            } finally {
                analysisJob = null
                releaseWakeLock()
                try {
                    stopForeground(STOP_FOREGROUND_DETACH)
                } catch (_: Exception) {}
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

        val engine = Media3Engine(applicationContext)
        activeMedia3Engine = engine

        exportJob = serviceScope.launch {
            try {
                updateNotification("正在通过系统硬件 MediaCodec 极速变速拼接成片...", 0)
                _statusFlow.value = ProcessStatus.Exporting(0f, "正在初始化硬件编码器...")

                val savedUri = engine.exportComposition(
                    videoUri = videoUri,
                    segments = segments,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    muteFastForwardAudio = muteFastForwardAudio
                ) { pct ->
                    val p = pct.toInt().coerceIn(0, 100)
                    updateNotification("成片硬件合成中: $p%", p)
                    _statusFlow.value = ProcessStatus.Exporting(pct, "成片硬件合成中: $p%")
                }

                updateNotification("视频导出成功！已存入系统相册 Movies/SmartVideo", 100)
                _statusFlow.value = ProcessStatus.ExportCompleted(savedUri)

            } catch (ce: CancellationException) {
                // 用户主动取消导出，不弹出错误
            } catch (t: Throwable) {
                t.printStackTrace()
                val errMsg = "导出失败: ${t.localizedMessage ?: t.message}"
                updateNotification(errMsg, 0)
                _statusFlow.value = ProcessStatus.Error(errMsg)
            } finally {
                releaseWakeLock()
                activeMedia3Engine = null
                exportJob = null
                try {
                    stopForeground(STOP_FOREGROUND_DETACH)
                } catch (_: Exception) {}
            }
        }
    }

    fun cancelAnalysis(fallbackSegments: List<Segment> = emptyList()) {
        analysisJob?.cancel()
        analysisJob = null
        releaseWakeLock()
        try {
            stopForeground(STOP_FOREGROUND_DETACH)
        } catch (_: Exception) {}
        _statusFlow.value = if (fallbackSegments.isNotEmpty()) {
            ProcessStatus.AnalysisCompleted(fallbackSegments)
        } else {
            ProcessStatus.Idle
        }
        updateNotification("已取消分析", 0)
    }

    fun cancelExport(fallbackSegments: List<Segment> = emptyList()) {
        activeMedia3Engine?.cancelExport()
        activeMedia3Engine = null
        exportJob?.cancel()
        exportJob = null
        releaseWakeLock()
        try {
            stopForeground(STOP_FOREGROUND_DETACH)
        } catch (_: Exception) {}
        _statusFlow.value = if (fallbackSegments.isNotEmpty()) {
            ProcessStatus.AnalysisCompleted(fallbackSegments)
        } else {
            ProcessStatus.Idle
        }
        updateNotification("已取消导出", 0)
    }

    fun resetStatus(fallbackSegments: List<Segment> = emptyList()) {
        _statusFlow.value = if (fallbackSegments.isNotEmpty()) {
            ProcessStatus.AnalysisCompleted(fallbackSegments)
        } else {
            ProcessStatus.Idle
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
