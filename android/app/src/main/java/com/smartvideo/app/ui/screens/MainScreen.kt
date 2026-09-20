package com.smartvideo.app.ui.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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

    // 动态阈值变更时，毫秒级纯本地重划分段动作与测算
    fun onThresholdChanged(newThresh: Float) {
        scoreThreshold = newThresh
        segments = applyThresholdToSegments(segments, newThresh, belowThresholdSpeed, deleteLowScore)
    }

    fun onSpeedChanged(newSpd: Float) {
        belowThresholdSpeed = newSpd
        segments = applyThresholdToSegments(segments, scoreThreshold, newSpd, deleteLowScore)
    }

    fun onDeleteLowChanged(deleteLow: Boolean) {
        deleteLowScore = deleteLow
        segments = applyThresholdToSegments(segments, scoreThreshold, belowThresholdSpeed, deleteLow)
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

                            // 开始分析按钮
                            val isAnalyzing = processStatus is ProcessStatus.Analyzing
                            Button(
                                onClick = {
                                    service?.startAnalysis(
                                        videoUri = videoMeta!!.uri,
                                        settings = settings,
                                        styleInstruction = selectedStylePreset,
                                        customPrompt = customPrompt
                                    )
                                },
                                enabled = !isAnalyzing,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Sky500)
                            ) {
                                if (isAnalyzing) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("大模型研读分析中...", fontSize = 12.sp)
                                } else {
                                    Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("开始 Gemini 智能粗剪 (两阶段研读)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            // 实时分析进度提示
                            if (processStatus is ProcessStatus.Analyzing) {
                                val st = processStatus as ProcessStatus.Analyzing
                                Spacer(modifier = Modifier.height(10.dp))
                                Column {
                                    LinearProgressIndicator(
                                        progress = { st.progress / 100f },
                                        color = Sky400,
                                        trackColor = Slate800,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "${st.message} (${st.progress.toInt()}%)",
                                        fontSize = 10.sp,
                                        color = Sky300
                                    )
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
                                segments = m
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
                            onSeekToTime = { /* seek player */ }
                        )
                    }

                    // 6. 分段详细列表
                    item {
                        SegmentListView(
                            segments = segments,
                            onUpdateSegmentAction = { index, act, spd ->
                                val m = segments.toMutableList()
                                m[index] = m[index].copy(action = act, speed = spd)
                                segments = m
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
                                    segments = m
                                }
                            }
                        )
                    }

                    // 7. 导出按钮
                    item {
                        Button(
                            onClick = { isExportSheetOpen = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Emerald500)
                        ) {
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

    // 导出 BottomSheet
    ExportBottomSheet(
        isOpen = isExportSheetOpen,
        onDismiss = { isExportSheetOpen = false },
        estimatedOutputSec = segments.sumOf { it.effectiveDuration },
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
