package com.smartvideo.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartvideo.app.data.model.AnalysisResult
import com.smartvideo.app.data.model.AppSettings
import com.smartvideo.app.data.model.Segment
import com.smartvideo.app.data.model.VideoMetadata
import com.smartvideo.app.media.VideoMetadataHelper
import com.smartvideo.app.service.ProcessStatus
import com.smartvideo.app.service.VideoProcessingService
import com.smartvideo.app.ui.components.*
import com.smartvideo.app.ui.theme.*

@Composable
fun MainScreen(
    service: VideoProcessingService?,
    settings: AppSettings,
    onUpdateSettings: (AppSettings) -> Unit
) {
    val context = LocalContext.current

    var videoMeta by remember { mutableStateOf<VideoMetadata?>(null) }
    var segments by remember { mutableStateOf<List<Segment>>(emptyList()) }
    var scoreThreshold by remember { mutableStateOf(settings.defaultThreshold) }
    var belowThresholdSpeed by remember { mutableStateOf(settings.defaultFfSpeed) }
    var deleteLowScore by remember { mutableStateOf(settings.deleteLowScore) }

    var currentPlayheadSec by remember { mutableStateOf(0.0) }
    var selectedStylePreset by remember { mutableStateOf("通用剪辑") }
    var customPrompt by remember { mutableStateOf("") }

    var isSettingsOpen by remember { mutableStateOf(false) }
    var isExportSheetOpen by remember { mutableStateOf(false) }
    val playerSeekController = remember { PlayerSeekController() }

    // 监听后台服务执行状态
    val processStatus by service?.statusFlow?.collectAsState() ?: remember { mutableStateOf(ProcessStatus.Idle) }

    LaunchedEffect(processStatus) {
        when (val st = processStatus) {
            is ProcessStatus.Analyzing -> {
                if (st.partialSegments.isNotEmpty()) {
                    segments = applyThresholdToSegments(st.partialSegments, scoreThreshold, belowThresholdSpeed, deleteLowScore)
                }
            }
            is ProcessStatus.AnalysisCompleted -> {
                segments = applyThresholdToSegments(st.segments, scoreThreshold, belowThresholdSpeed, deleteLowScore)
                Toast.makeText(context, "AI 两阶段研读完成！已生成 1-10 分价值曲线", Toast.LENGTH_SHORT).show()
            }
            is ProcessStatus.ExportCompleted -> {
                Toast.makeText(context, "成片导出成功！已保存至手机相册", Toast.LENGTH_LONG).show()
            }
            is ProcessStatus.Error -> {
                Toast.makeText(context, st.message, Toast.LENGTH_LONG).show()
            }
            else -> {}
        }
    }

    // Android 系统原生照片/视频选择器
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val meta = VideoMetadataHelper.extractMetadata(context, uri)
            videoMeta = meta

            // 智能感知：优先加载已永久落地的粗处理成果
            val uriStr = uri.toString()
            val savedResult = service?.getAnalysisResult(uriStr)
            if (savedResult != null && savedResult.segments.isNotEmpty()) {
                segments = applyThresholdToSegments(savedResult.segments, scoreThreshold, belowThresholdSpeed, deleteLowScore)
                if (savedResult.styleInstruction.isNotEmpty()) selectedStylePreset = savedResult.styleInstruction
                if (savedResult.customPrompt.isNotEmpty()) customPrompt = savedResult.customPrompt
                Toast.makeText(context, "已载入已完成的历史粗剪成果 (共 ${savedResult.segments.size} 段)", Toast.LENGTH_LONG).show()
            } else {
                val savedCp = service?.getCheckpoint(uriStr)
                if (savedCp != null && savedCp.stage1Segments.isNotEmpty()) {
                    segments = applyThresholdToSegments(savedCp.stage1Segments, scoreThreshold, belowThresholdSpeed, deleteLowScore)
                    if (savedCp.styleInstruction.isNotEmpty()) selectedStylePreset = savedCp.styleInstruction
                    if (savedCp.customPrompt.isNotEmpty()) customPrompt = savedCp.customPrompt
                } else {
                    val initialSeg = Segment(
                        startTime = 0.0,
                        endTime = meta.durationSec,
                        duration = meta.durationSec,
                        action = "keep",
                        speed = 1.0f,
                        score = 6.0f,
                        reason = "原始未剪辑完整视频",
                        summary = "完整素材"
                    )
                    segments = listOf(initialSeg)
                }
            }
        }
    }

    // 若本地存在已完成的历史成果或未完成的断点检查点，自动恢复分段与参数
    LaunchedEffect(service, videoMeta) {
        if (service != null && videoMeta != null && segments.size <= 1) {
            val uriStr = videoMeta!!.uri.toString()
            val savedResult = service.getAnalysisResult(uriStr)
            if (savedResult != null && savedResult.segments.isNotEmpty()) {
                segments = applyThresholdToSegments(savedResult.segments, scoreThreshold, belowThresholdSpeed, deleteLowScore)
                if (savedResult.styleInstruction.isNotEmpty()) selectedStylePreset = savedResult.styleInstruction
                if (savedResult.customPrompt.isNotEmpty()) customPrompt = savedResult.customPrompt
                Toast.makeText(context, "已自动载入历史粗剪成果 (共 ${savedResult.segments.size} 段)", Toast.LENGTH_SHORT).show()
            } else {
                val cp = service.getCheckpoint(uriStr)
                if (cp != null && cp.stage1Segments.isNotEmpty()) {
                    segments = applyThresholdToSegments(cp.stage1Segments, scoreThreshold, belowThresholdSpeed, deleteLowScore)
                    if (cp.styleInstruction.isNotEmpty()) selectedStylePreset = cp.styleInstruction
                    if (cp.customPrompt.isNotEmpty()) customPrompt = cp.customPrompt
                }
            }
        }
    }

    val chunkWindow = 90.0
    val totalChunks = if (videoMeta != null && videoMeta!!.durationSec > 0) {
        Math.max(1, Math.ceil(videoMeta!!.durationSec / chunkWindow).toInt())
    } else 1

    val checkpoint = remember(service, videoMeta, processStatus) {
        if (service != null && videoMeta != null) {
            service.getCheckpoint(videoMeta!!.uri.toString())
        } else null
    }

    val savedAnalysisResult = remember(service, videoMeta, processStatus) {
        if (service != null && videoMeta != null) {
            service.getAnalysisResult(videoMeta!!.uri.toString())
        } else null
    }

    val hasCompletedResult = remember(savedAnalysisResult, segments) {
        (savedAnalysisResult != null && savedAnalysisResult.segments.isNotEmpty()) ||
                (segments.size > 1 && !(processStatus is ProcessStatus.Analyzing))
    }

    val maxAnalyzedTime = remember(segments) {
        if (segments.size > 1) segments.maxOfOrNull { it.endTime } ?: 0.0 else 0.0
    }

    val hasUnfinishedCheckpoint = remember(checkpoint, segments, videoMeta, maxAnalyzedTime, hasCompletedResult) {
        if (videoMeta == null || hasCompletedResult) false
        else if (checkpoint != null && checkpoint.completedChunks > 0 && checkpoint.completedChunks < checkpoint.totalChunks) true
        else (segments.size > 1 && maxAnalyzedTime < videoMeta!!.durationSec - 10.0)
    }

    val completedChunkCount = remember(checkpoint, segments, maxAnalyzedTime) {
        when {
            checkpoint != null -> checkpoint.completedChunks
            segments.size > 1 -> (maxAnalyzedTime / chunkWindow).toInt().coerceAtMost(totalChunks)
            else -> 0
        }
    }

    fun persistSegmentsIfCompleted(newSegments: List<Segment>) {
        segments = newSegments
        if (service != null && videoMeta != null && newSegments.size > 1) {
            service.saveAnalysisResult(
                AnalysisResult(
                    videoUri = videoMeta!!.uri.toString(),
                    totalDuration = videoMeta!!.durationSec,
                    segments = newSegments,
                    styleInstruction = selectedStylePreset,
                    customPrompt = customPrompt
                )
            )
        }
    }

    // 动态阈值变更时，毫秒级纯本地重划分段动作与测算，并自动同步落盘保护
    fun onThresholdChanged(newThresh: Float) {
        scoreThreshold = newThresh
        val updated = applyThresholdToSegments(segments, newThresh, belowThresholdSpeed, deleteLowScore)
        persistSegmentsIfCompleted(updated)
    }

    fun onSpeedChanged(newSpd: Float) {
        belowThresholdSpeed = newSpd
        val updated = applyThresholdToSegments(segments, scoreThreshold, newSpd, deleteLowScore)
        persistSegmentsIfCompleted(updated)
    }

    fun onDeleteLowChanged(deleteLow: Boolean) {
        deleteLowScore = deleteLow
        val updated = applyThresholdToSegments(segments, scoreThreshold, belowThresholdSpeed, deleteLow)
        persistSegmentsIfCompleted(updated)
    }

    Scaffold(
        containerColor = Slate950,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .background(Slate900)
                    .border(1.dp, Slate800)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Sky500),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.VideoCameraBack,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "SmartVideo",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Android 原生",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Sky300,
                                modifier = Modifier
                                    .background(Sky500.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                        Text(
                            text = "Media3 Transformer + Gemini 剪辑",
                            fontSize = 10.sp,
                            color = Slate400
                        )
                    }
                }

                IconButton(onClick = { isSettingsOpen = true }) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Slate300,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            // 1. 视频选择与基本属性卡片
            item {
                if (videoMeta == null) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Slate900),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate800),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                videoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                )
                            }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.CloudUpload,
                                contentDescription = null,
                                tint = Sky400,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "点击选择手机相册视频",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "支持任意长度 MP4 / MOV 拍摄原片，无 FFmpeg 依赖",
                                fontSize = 11.sp,
                                color = Slate400
                            )
                        }
                    }
                } else {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Slate900),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate800),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Slate800),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Movie, contentDescription = null, tint = Sky400, modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = videoMeta!!.filename,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "${videoMeta!!.formattedDuration} · ${videoMeta!!.filesizeFormatted} · ${videoMeta!!.width}x${videoMeta!!.height}",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Slate400
                                    )
                                }
                            }

                            TextButton(
                                onClick = {
                                    videoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                    )
                                }
                            ) {
                                Text("换视频", fontSize = 11.sp, color = Sky400)
                            }
                        }
                    }
                }
            }

            // 2. AI 剪辑配置卡片
            if (videoMeta != null) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Slate900),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate800),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Sky400, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Gemini 三步走智能剪辑",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // 风格预设选择
                            Text(text = "剪辑风格偏好:", fontSize = 11.sp, color = Slate400)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("通用剪辑", "Vlog日常", "游戏高光", "会议网课").forEach { style ->
                                    val isPicked = selectedStylePreset == style
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isPicked) Sky500 else Slate800)
                                            .clickable { selectedStylePreset = style }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = style,
                                            fontSize = 10.sp,
                                            fontWeight = if (isPicked) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isPicked) Color.White else Slate300
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // 自定义补充指示
                            OutlinedTextField(
                                value = customPrompt,
                                onValueChange = { customPrompt = it },
                                placeholder = { Text("补充指示（可选，如：重点保留有人说话的段落）", fontSize = 10.sp, color = Slate600) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Sky400,
                                    unfocusedBorderColor = Slate800,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // 开始/断点继续/取消分析控制区
                            val isAnalyzing = processStatus is ProcessStatus.Analyzing
                            if (isAnalyzing) {
                                Button(
                                    onClick = { service?.cancelAnalysis(segments) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Rose600)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("取消 Gemini 研读分析", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            } else if (hasCompletedResult) {
                                // 粗剪研读与打分已完成并落地保护
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Emerald950.copy(alpha = 0.5f)),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Emerald800),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Emerald400, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "AI 粗剪成果已永久落地保护",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Emerald300
                                            )
                                            Text(
                                                text = "共 ${segments.size} 个剪辑分段 · 1-10分价值曲线就绪，可直接调整或导出",
                                                fontSize = 10.sp,
                                                color = Slate300
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                var showReanalyzeConfirmDialog by remember { mutableStateOf(false) }

                                if (showReanalyzeConfirmDialog) {
                                    AlertDialog(
                                        onDismissRequest = { showReanalyzeConfirmDialog = false },
                                        title = { Text("确认重新完整研读？", color = Color.White) },
                                        text = { Text("当前已永久保存该视频的 ${segments.size} 个粗剪分段成果。重新研读将覆盖已有分析，需耗费较长时间调用大模型。", color = Slate300, fontSize = 12.sp) },
                                        confirmButton = {
                                            TextButton(
                                                onClick = {
                                                    showReanalyzeConfirmDialog = false
                                                    service?.clearAnalysisResult(videoMeta!!.uri.toString())
                                                    service?.clearCheckpoint(videoMeta!!.uri.toString())
                                                    try {
                                                        val serviceIntent = Intent(context, VideoProcessingService::class.java)
                                                        ContextCompat.startForegroundService(context, serviceIntent)
                                                        service?.startAnalysis(
                                                            videoUri = videoMeta!!.uri,
                                                            settings = settings,
                                                            styleInstruction = selectedStylePreset,
                                                            customPrompt = customPrompt,
                                                            resume = false
                                                        )
                                                    } catch (e: Throwable) {
                                                        e.printStackTrace()
                                                        Toast.makeText(context, "启动分析失败: ${e.localizedMessage ?: e.message}", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            ) {
                                                Text("确认重新研读", color = Rose400, fontWeight = FontWeight.Bold)
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { showReanalyzeConfirmDialog = false }) {
                                                Text("取消", color = Sky400)
                                            }
                                        },
                                        containerColor = Slate900
                                    )
                                }

                                OutlinedButton(
                                    onClick = { showReanalyzeConfirmDialog = true },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(34.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Slate700),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Slate400)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("重新完整研读 (将覆盖已有成果)", fontSize = 11.sp)
                                }
                            } else if (hasUnfinishedCheckpoint) {
                                // 存在未完成的断点
                                val m = (maxAnalyzedTime / 60).toInt()
                                val s = (maxAnalyzedTime % 60).toInt()
                                val timeStr = String.format("%02d:%02d", m, s)

                                Button(
                                    onClick = {
                                        try {
                                            val serviceIntent = Intent(context, VideoProcessingService::class.java)
                                            ContextCompat.startForegroundService(context, serviceIntent)
                                            service?.startAnalysis(
                                                videoUri = videoMeta!!.uri,
                                                settings = settings,
                                                styleInstruction = selectedStylePreset,
                                                customPrompt = customPrompt,
                                                resume = true,
                                                existingSegments = segments
                                            )
                                        } catch (e: Throwable) {
                                            e.printStackTrace()
                                            Toast.makeText(context, "启动断点续剪失败: ${e.localizedMessage ?: e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Sky500)
                                ) {
                                    Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "从断点继续研读 (第 ${completedChunkCount + 1}/$totalChunks 段 · 已研读至 $timeStr)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedButton(
                                    onClick = {
                                        service?.clearCheckpoint(videoMeta!!.uri.toString())
                                        val initialSeg = Segment(
                                            startTime = 0.0,
                                            endTime = videoMeta!!.durationSec,
                                            duration = videoMeta!!.durationSec,
                                            action = "keep",
                                            speed = 1.0f,
                                            score = 6.0f,
                                            reason = "原始未剪辑完整视频",
                                            summary = "完整素材"
                                        )
                                        segments = listOf(initialSeg)
                                        Toast.makeText(context, "已清除断点，恢复初始状态", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(34.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Slate700),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Slate300)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp), tint = Slate400)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("清除断点，重新从头研读", fontSize = 11.sp)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        try {
                                            val serviceIntent = Intent(context, VideoProcessingService::class.java)
                                            ContextCompat.startForegroundService(context, serviceIntent)
                                            service?.startAnalysis(
                                                videoUri = videoMeta!!.uri,
                                                settings = settings,
                                                styleInstruction = selectedStylePreset,
                                                customPrompt = customPrompt,
                                                resume = false
                                            )
                                        } catch (e: Throwable) {
                                            e.printStackTrace()
                                            Toast.makeText(context, "启动分析失败: ${e.localizedMessage ?: e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Sky500)
                                ) {
                                    Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("开始 Gemini 智能粗剪 (两阶段研读)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            // 实时分析进度提示与快速取消入口
                            if (processStatus is ProcessStatus.Analyzing) {
                                val st = processStatus as ProcessStatus.Analyzing
                                Spacer(modifier = Modifier.height(10.dp))
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${st.message} (${st.progress.toInt()}%)",
                                            fontSize = 10.sp,
                                            color = Sky300,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = { service?.cancelAnalysis(segments) },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                            modifier = Modifier.height(24.dp)
                                        ) {
                                            Text("终止", fontSize = 10.sp, color = Rose400)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { st.progress / 100f },
                                        color = Sky400,
                                        trackColor = Slate800,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                    )
                                }
                            }

                            // 分析错误卡片反馈与断点一键重试
                            if (processStatus is ProcessStatus.Error && !(processStatus as ProcessStatus.Error).message.contains("导出")) {
                                val err = processStatus as ProcessStatus.Error
                                Spacer(modifier = Modifier.height(10.dp))
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Rose950.copy(alpha = 0.5f)),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Rose800),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Rose400, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(err.message, fontSize = 11.sp, color = Rose200, modifier = Modifier.weight(1f))
                                            TextButton(onClick = { isSettingsOpen = true }) {
                                                Text("查验设置", fontSize = 10.sp, color = Sky300)
                                            }
                                        }
                                        if (hasUnfinishedCheckpoint) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Button(
                                                onClick = {
                                                    try {
                                                        val serviceIntent = Intent(context, VideoProcessingService::class.java)
                                                        ContextCompat.startForegroundService(context, serviceIntent)
                                                        service?.startAnalysis(
                                                            videoUri = videoMeta!!.uri,
                                                            settings = settings,
                                                            styleInstruction = selectedStylePreset,
                                                            customPrompt = customPrompt,
                                                            resume = true,
                                                            existingSegments = segments
                                                        )
                                                    } catch (e: Throwable) {
                                                        e.printStackTrace()
                                                        Toast.makeText(context, "重试失败: ${e.localizedMessage ?: e.message}", Toast.LENGTH_LONG).show()
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Rose600),
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(32.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                            ) {
                                                Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("从断点重试继续", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. 视频播放预览器 (ExoPlayer)
                item {
                    ExoPlayerPreview(
                        videoUri = videoMeta!!.uri,
                        totalDurationSec = videoMeta!!.durationSec,
                        segments = segments,
                        onCurrentTimeUpdate = { currentPlayheadSec = it },
                        playerSeekController = playerSeekController,
                        onSplitCurrentTime = { cutTime ->
                            val targetIdx = segments.findIndexForSplit(cutTime)
                            if (targetIdx != -1) {
                                val cur = segments[targetIdx]
                                val sA = cur.copy(endTime = cutTime, duration = cutTime - cur.startTime)
                                val sB = cur.copy(id = java.util.UUID.randomUUID().toString().substring(0, 8), startTime = cutTime, duration = cur.endTime - cutTime)
                                val m = segments.toMutableList()
                                m.removeAt(targetIdx)
                                m.add(targetIdx, sA)
                                m.add(targetIdx + 1, sB)
                                persistSegmentsIfCompleted(m)
                                Toast.makeText(context, "已在当前帧裁切分段", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                // 4. 动态保留阈值控制器卡片 (第三步 交互测算)
                if (segments.isNotEmpty()) {
                    item {
                        ThresholdCard(
                            scoreThreshold = scoreThreshold,
                            onThresholdChange = { onThresholdChanged(it) },
                            belowThresholdSpeed = belowThresholdSpeed,
                            onBelowSpeedChange = { onSpeedChanged(it) },
                            deleteLowScore = deleteLowScore,
                            onDeleteLowToggle = { onDeleteLowChanged(it) },
                            segments = segments,
                            originalDurationSec = videoMeta!!.durationSec
                        )
                    }

                    // 5. 1-10分价值曲线与总览时间轴 (Canvas 面积折线图)
                    item {
                        ScoreCurveCanvas(
                            totalDurationSec = videoMeta!!.durationSec,
                            segments = segments,
                            scoreThreshold = scoreThreshold,
                            currentPlayheadSec = currentPlayheadSec,
                            onSeekToTime = { playerSeekController.seekTo(it) }
                        )
                    }

                    // 6. 分段详细列表
                    item {
                        SegmentListView(
                            segments = segments,
                            onUpdateSegmentAction = { index, act, spd ->
                                val m = segments.toMutableList()
                                m[index] = m[index].copy(action = act, speed = spd)
                                persistSegmentsIfCompleted(m)
                            },
                            onMergeWithNext = { index ->
                                if (index < segments.size - 1) {
                                    val cur = segments[index]
                                    val next = segments[index + 1]
                                    val merged = cur.copy(
                                        endTime = next.endTime,
                                        duration = next.endTime - cur.startTime,
                                        score = Math.max(cur.score, next.score),
                                        reason = "[合并段落] ${cur.reason} + ${next.reason}".trim()
                                    )
                                    val m = segments.toMutableList()
                                    m.removeAt(index)
                                    m.removeAt(index)
                                    m.add(index, merged)
                                    persistSegmentsIfCompleted(m)
                                }
                            },
                            onSeekToTime = { playerSeekController.seekTo(it) },
                            onAdjustBoundary = { index, nStart, nEnd ->
                                if (index in segments.indices) {
                                    val cur = segments[index]
                                    val dur = Math.round((nEnd - nStart) * 100.0) / 100.0
                                    val updated = cur.copy(
                                        startTime = nStart,
                                        endTime = nEnd,
                                        duration = Math.max(0.1, dur)
                                    )
                                    val m = segments.toMutableList()
                                    m[index] = updated
                                    persistSegmentsIfCompleted(m)
                                }
                            }
                        )
                    }

                    // 7. 导出按钮
                    item {
                        val isExporting = processStatus is ProcessStatus.Exporting
                        Button(
                            onClick = { isExportSheetOpen = true },
                            enabled = !isExporting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isExporting) Slate800 else Emerald500
                            )
                        ) {
                            if (isExporting) {
                                CircularProgressIndicator(
                                    color = Sky400,
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "成片硬件合成中... (${(processStatus as ProcessStatus.Exporting).progress.toInt()}%)",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Sky300
                                )
                            } else {
                                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "配置并导出成片 (Media3 硬件加速)",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 导出 BottomSheet
    ExportBottomSheet(
        isOpen = isExportSheetOpen,
        onDismiss = { isExportSheetOpen = false },
        estimatedOutputSec = segments.sumOf { it.effectiveDuration },
        isPortrait = (videoMeta?.width ?: 0) < (videoMeta?.height ?: 0),
        onConfirmExport = { targetW, targetH, muteFf ->
            videoMeta?.let { meta ->
                service?.startExport(
                    videoUri = meta.uri,
                    segments = segments,
                    targetWidth = targetW,
                    targetHeight = targetH,
                    muteFastForwardAudio = muteFf
                )
            }
        }
    )

    // 方案 A：模态导出进度与完成看板浮层
    ExportProgressDialog(
        status = processStatus,
        onCancel = {
            service?.cancelExport(segments)
        },
        onDismiss = {
            service?.resetStatus(segments)
        },
        onPlayVideo = { uri ->
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "无法调用系统播放器: ${e.localizedMessage ?: e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    )

    // 配置弹窗
    if (isSettingsOpen) {
        SettingsDialog(
            currentSettings = settings,
            onDismiss = { isSettingsOpen = false },
            onSaveSettings = onUpdateSettings
        )
    }
}

// 辅助函数：根据阈值与偏好重新映射分段动作
private fun applyThresholdToSegments(
    rawSegments: List<Segment>,
    threshold: Float,
    belowSpeed: Float,
    deleteLow: Boolean
): List<Segment> {
    return rawSegments.map { s ->
        val sc = s.score
        when {
            sc >= threshold -> s.copy(action = "keep", speed = 1.0f)
            deleteLow && sc <= 2.0f -> s.copy(action = "delete", speed = 0.0f)
            else -> s.copy(action = "fast_forward", speed = belowSpeed)
        }
    }
}

private fun List<Segment>.findIndexForSplit(time: Double): Int {
    return indexOfFirst { time > it.startTime + 0.3 && time < it.endTime - 0.3 }
}
