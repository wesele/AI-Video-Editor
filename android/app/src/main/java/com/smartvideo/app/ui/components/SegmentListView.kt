package com.smartvideo.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
    modifier: Modifier = Modifier
) {
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
                    // 顶部行：序号、时间范围、价值度徽章
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                            val effSec = if (seg.action == "keep") seg.duration else if (seg.action == "fast_forward") (seg.duration / seg.speed) else 0.0
                            Text(
                                text = "(${seg.duration.toInt()}s→${effSec.toInt()}s)",
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

                    Spacer(modifier = Modifier.height(6.dp))

                    // 底部操作栏：动作切换 Chips 与 合并按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isFf) Amber500 else Slate800)
                                    .clickable { onUpdateSegmentAction(index, "fast_forward", 4.0f) }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "快进 4x",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isFf) Color.White else Slate400
                                )
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

                        // 合并到下一段
                        if (index < segments.size - 1) {
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
