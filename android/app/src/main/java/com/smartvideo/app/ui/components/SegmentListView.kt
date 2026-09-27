package com.smartvideo.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartvideo.app.data.model.Segment
import com.smartvideo.app.ui.theme.*

@Composable
fun SegmentListView(
    segments: List<Segment>,
    onUpdateSegmentAction: (index: Int, action: String, speed: Float) -> Unit,
    onMergeWithNext: (index: Int) -> Unit,
    onSeekToTime: ((Double) -> Unit)? = null,
    onAdjustBoundary: ((index: Int, newStart: Double, newEnd: Double) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var expandedTuneIndex by remember { mutableStateOf<Int?>(null) }
    fun formatSec(sec: Double): String {
        val m = (sec / 60).toInt()
        val s = sec % 60
        return String.format("%02d:%04.1f", m, s)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Slate900)
            .border(1.dp, Slate800, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "剪辑分段明细 (${segments.size} 段)",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "可手动干预动作与合并相邻段",
                fontSize = 10.sp,
                color = Slate400
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 各分段卡片展示
        segments.forEachIndexed { index, seg ->
            val actionColor = when (seg.action) {
                "keep" -> Emerald500
                "fast_forward" -> Amber500
                else -> Rose500
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Slate950),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Slate800),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    // 顶部行：序号、时间范围、价值度徽章与点击跳转预览
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onSeekToTime?.invoke(seg.startTime) }
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = Sky400,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "#${index + 1}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate400
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${formatSec(seg.startTime)} → ${formatSec(seg.endTime)}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val effSec = if (seg.action == "keep") seg.duration else if (seg.action == "fast_forward") (seg.duration / (if (seg.speed > 0f) seg.speed else 4.0f)) else 0.0
                            Text(
                                text = "(${String.format("%.1f", seg.duration)}s→${String.format("%.1f", effSec)}s)",
                                fontSize = 10.sp,
                                color = Slate400
                            )
                        }

                        // 价值度徽章
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(actionColor.copy(alpha = 0.2f))
                                .border(1.dp, actionColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${String.format("%.1f", seg.score)}分",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = actionColor
                            )
                        }
                    }

                    // 事实概述或判定原因
                    val displayText = seg.summary.ifEmpty { seg.reason }
                    if (displayText.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = displayText,
                            fontSize = 11.sp,
                            color = Slate300,
                            maxLines = 2
                        )
                    }

                    // 微调展开面板 (±0.5s 起止微调)
                    if (expandedTuneIndex == index) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Slate900)
                                .border(1.dp, Slate800, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("起点:", fontSize = 10.sp, color = Slate400)
                                Spacer(modifier = Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Slate800)
                                        .clickable {
                                            val newStart = Math.max(0.0, Math.round((seg.startTime - 0.5) * 10.0) / 10.0)
                                            onAdjustBoundary?.invoke(index, newStart, seg.endTime)
                                        }
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Text("-0.5s", fontSize = 9.sp, color = Sky300, fontFamily = FontFamily.Monospace)
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Slate800)
                                        .clickable {
                                            val newStart = Math.min(seg.endTime - 0.2, Math.round((seg.startTime + 0.5) * 10.0) / 10.0)
                                            onAdjustBoundary?.invoke(index, newStart, seg.endTime)
                                        }
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Text("+0.5s", fontSize = 9.sp, color = Sky300, fontFamily = FontFamily.Monospace)
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("终点:", fontSize = 10.sp, color = Slate400)
                                Spacer(modifier = Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Slate800)
                                        .clickable {
                                            val newEnd = Math.max(seg.startTime + 0.2, Math.round((seg.endTime - 0.5) * 10.0) / 10.0)
                                            onAdjustBoundary?.invoke(index, seg.startTime, newEnd)
                                        }
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Text("-0.5s", fontSize = 9.sp, color = Amber300, fontFamily = FontFamily.Monospace)
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Slate800)
                                        .clickable {
                                            val newEnd = Math.round((seg.endTime + 0.5) * 10.0) / 10.0
                                            onAdjustBoundary?.invoke(index, seg.startTime, newEnd)
                                        }
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Text("+0.5s", fontSize = 9.sp, color = Amber300, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // 底部操作栏：动作切换 Chips 与 合并微调按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            // 保留
                            val isKeep = seg.action == "keep"
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isKeep) Emerald500 else Slate800)
                                    .clickable { onUpdateSegmentAction(index, "keep", 1.0f) }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "保留 1x",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isKeep) Color.White else Slate400
                                )
                            }

                            // 快进
                            val isFf = seg.action == "fast_forward"
                            if (!isFf) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Slate800)
                                        .clickable { onUpdateSegmentAction(index, "fast_forward", 4.0f) }
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "快进",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Slate400
                                    )
                                }
                            } else {
                                listOf(2.0f, 4.0f, 8.0f).forEach { spd ->
                                    val isCur = (seg.speed == spd)
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isCur) Amber500 else Slate800)
                                            .clickable { onUpdateSegmentAction(index, "fast_forward", spd) }
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "${spd.toInt()}x",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isCur) Color.White else Amber300
                                        )
                                    }
                                }
                            }

                            // 删除
                            val isDel = seg.action == "delete"
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isDel) Rose500 else Slate800)
                                    .clickable { onUpdateSegmentAction(index, "delete", 0.0f) }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "丢弃",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDel) Color.White else Slate400
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 微调起止按钮
                            IconButton(
                                onClick = {
                                    expandedTuneIndex = if (expandedTuneIndex == index) null else index
                                },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    Icons.Default.Tune,
                                    contentDescription = "Tune",
                                    tint = if (expandedTuneIndex == index) Sky400 else Slate400,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            // 合并到下一段
                            if (index < segments.size - 1) {
                                Spacer(modifier = Modifier.width(2.dp))
                                IconButton(
                                    onClick = { onMergeWithNext(index) },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(
                                        Icons.Default.CallMerge,
                                        contentDescription = "Merge",
                                        tint = Slate400,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
