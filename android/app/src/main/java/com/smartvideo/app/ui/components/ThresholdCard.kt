package com.smartvideo.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
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
fun ThresholdCard(
    scoreThreshold: Float,
    onThresholdChange: (Float) -> Unit,
    belowThresholdSpeed: Float,
    onBelowSpeedChange: (Float) -> Unit,
    deleteLowScore: Boolean,
    onDeleteLowToggle: (Boolean) -> Unit,
    segments: List<Segment>,
    originalDurationSec: Double,
    modifier: Modifier = Modifier
) {
    // 毫秒级纯本地动态测算成片总时长与统计指标
    var totalEstimatedSec = 0.0
    var keepCount = 0
    var ffCount = 0
    var delCount = 0
    var keepDur = 0.0
    var ffDur = 0.0

    segments.forEach { s ->
        when (s.action) {
            "keep" -> {
                keepCount++
                keepDur += s.duration
                totalEstimatedSec += s.duration
            }
            "fast_forward" -> {
                ffCount++
                ffDur += s.duration
                val spd = if (s.speed > 0f) s.speed else 4.0f
                totalEstimatedSec += (s.duration / spd)
            }
            else -> {
                delCount++
            }
        }
    }

    val compressionRatio = if (originalDurationSec > 0) {
        ((totalEstimatedSec / originalDurationSec) * 100).toInt()
    } else 100

    fun formatTime(sec: Double): String {
        val m = (sec / 60).toInt()
        val s = (sec % 60).toInt()
        return String.format("%02d:%02d", m, s)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Slate900)
            .border(1.dp, Slate800, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        // 顶部标题与动态测算成片时长看板
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = Sky400,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "动态保留阈值决策",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "拖动实时计算成片长度",
                        fontSize = 10.sp,
                        color = Slate400
                    )
                }
            }

            // 实时成片指标展示
            Box(
                modifier = Modifier
                    .background(Slate950, RoundedCornerShape(10.dp))
                    .border(1.dp, Slate800, RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "成片: ",
                            fontSize = 10.sp,
                            color = Slate400
                        )
                        Text(
                            text = formatTime(totalEstimatedSec),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Emerald400
                        )
                        Text(
                            text = " ($compressionRatio%)",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Slate400
                        )
                    }
                    Text(
                        text = "保留${keepCount}段 · 快进${ffCount}段" + if (delCount > 0) " · 删${delCount}段" else "",
                        fontSize = 9.sp,
                        color = Slate400
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 保留阈值滑块
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "保留阈值:",
                fontSize = 11.sp,
                color = Slate300,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.width(8.dp))

            Slider(
                value = scoreThreshold,
                onValueChange = { onThresholdChange(Math.round(it * 2f) / 2f) },
                valueRange = 1.0f..9.5f,
                steps = 16,
                colors = SliderDefaults.colors(
                    thumbColor = Sky400,
                    activeTrackColor = Sky500,
                    inactiveTrackColor = Slate800
                ),
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = "${String.format("%.1f", scoreThreshold)}分",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = Sky300,
                modifier = Modifier
                    .background(Sky500.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                    .border(1.dp, Sky500.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 预设快捷选择 Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val presets = listOf(
                7.5f to "精简高潮 (7.5)",
                6.0f to "标准推荐 (6.0)",
                4.5f to "饱满铺垫 (4.5)"
            )

            presets.forEach { (sc, label) ->
                val isSelected = Math.abs(scoreThreshold - sc) < 0.2f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Sky500 else Slate800)
                        .border(1.dp, if (isSelected) Sky400 else Slate700, RoundedCornerShape(8.dp))
                        .clickable { onThresholdChange(sc) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) Color.White else Slate300
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 快进倍速与低分剔除行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "快进倍速:",
                    fontSize = 11.sp,
                    color = Slate400
                )
                Spacer(modifier = Modifier.width(6.dp))
                listOf(2.0f, 4.0f, 8.0f, 16.0f).forEach { spd ->
                    val isSpdSelected = belowThresholdSpeed == spd
                    Box(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSpdSelected) Amber500 else Slate800)
                            .border(1.dp, if (isSpdSelected) Amber400 else Slate700, RoundedCornerShape(6.dp))
                            .clickable { onBelowSpeedChange(spd) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${spd.toInt()}x",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSpdSelected) Color.White else Slate300
                        )
                    }
                }
            }

            // ≤2.0分丢弃开关
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onDeleteLowToggle(!deleteLowScore) }
            ) {
                Checkbox(
                    checked = deleteLowScore,
                    onCheckedChange = onDeleteLowToggle,
                    colors = CheckboxDefaults.colors(
                        checkedColor = Rose500,
                        uncheckedColor = Slate600
                    ),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "≤2分废片丢弃",
                    fontSize = 11.sp,
                    color = Slate300
                )
            }
        }
    }
}
