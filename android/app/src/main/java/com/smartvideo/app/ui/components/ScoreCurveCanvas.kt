package com.smartvideo.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartvideo.app.data.model.Segment
import com.smartvideo.app.ui.theme.*

@Composable
fun ScoreCurveCanvas(
    totalDurationSec: Double,
    segments: List<Segment>,
    scoreThreshold: Float,
    currentPlayheadSec: Double,
    onSeekToTime: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    var touchX by remember { mutableStateOf<Float?>(null) }
    var touchTime by remember { mutableStateOf<Double?>(null) }
    var touchSegment by remember { mutableStateOf<Segment?>(null) }

    val duration = if (totalDurationSec > 0) totalDurationSec else 1.0

    // Y 坐标映射函数 (分数 1.0 ~ 10.0 -> 高度百分比)
    fun getYForScore(score: Float, height: Float): Float {
        val clamped = Math.max(1.0f, Math.min(10.0f, score))
        val topPadding = height * 0.12f
        val bottomPadding = height * 0.88f
        val usableHeight = bottomPadding - topPadding
        return bottomPadding - ((clamped - 1.0f) / 9.0f) * usableHeight
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Slate950)
            .border(1.dp, Slate800, RoundedCornerShape(14.dp))
            .padding(10.dp)
    ) {
        // 标题与图例
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "全局价值度曲线 (1-10分)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Slate300
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                val maxAnalyzedTime = if (segments.size > 1) segments.maxOfOrNull { it.endTime } ?: 0.0 else 0.0
                val isPartial = segments.size > 1 && (duration - maxAnalyzedTime) > 5.0

                if (isPartial) {
                    Box(modifier = Modifier.size(7.dp).background(Sky400, RoundedCornerShape(2.dp)))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("待续剪", fontSize = 9.sp, color = Sky300)
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Box(modifier = Modifier.size(8.dp).background(Emerald500, RoundedCornerShape(2.dp)))
                Spacer(modifier = Modifier.width(3.dp))
                Text("≥${String.format("%.1f", scoreThreshold)}分 保留", fontSize = 9.sp, color = Slate400)

                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.size(8.dp).background(Amber500, RoundedCornerShape(2.dp)))
                Spacer(modifier = Modifier.width(3.dp))
                Text("快进", fontSize = 9.sp, color = Slate400)

                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.size(8.dp).background(Rose500, RoundedCornerShape(2.dp)))
                Spacer(modifier = Modifier.width(3.dp))
                Text("≤2分 丢弃", fontSize = 9.sp, color = Slate400)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 核心 Canvas 曲线绘制区域
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Slate900)
                .pointerInput(totalDurationSec, segments) {
                    detectTapGestures(
                        onPress = { offset ->
                            val pct = (offset.x / size.width).coerceIn(0f, 1f)
                            val t = pct * duration
                            touchX = offset.x
                            touchTime = t
                            touchSegment = segments.find { t >= it.startTime && t < it.endTime }
                            onSeekToTime(t)
                        }
                    )
                }
                .pointerInput(totalDurationSec, segments) {
                    detectDragGestures(
                        onDrag = { change, _ ->
                            val x = change.position.x
                            val pct = (x / size.width).coerceIn(0f, 1f)
                            val t = pct * duration
                            touchX = x
                            touchTime = t
                            touchSegment = segments.find { t >= it.startTime && t < it.endTime }
                            onSeekToTime(t)
                        },
                        onDragEnd = {
                            touchX = null
                            touchTime = null
                            touchSegment = null
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // 1. 绘制水平参考虚线
                val y80 = getYForScore(8.0f, h)
                val y50 = getYForScore(5.0f, h)
                val y20 = getYForScore(2.0f, h)

                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)

                // 8.0 高能
                drawLine(
                    color = Slate700,
                    start = Offset(0f, y80),
                    end = Offset(w, y80),
                    pathEffect = dashEffect,
                    strokeWidth = 1f
                )

                // 5.0 过渡
                drawLine(
                    color = Slate800,
                    start = Offset(0f, y50),
                    end = Offset(w, y50),
                    pathEffect = dashEffect,
                    strokeWidth = 1f
                )

                // 2.0 冗余
                drawLine(
                    color = Slate800,
                    start = Offset(0f, y20),
                    end = Offset(w, y20),
                    pathEffect = dashEffect,
                    strokeWidth = 1f
                )

                // 2. 绘制各分段价值度填充面积与曲线
                segments.forEachIndexed { idx, seg ->
                    val x1 = ((seg.startTime / duration) * w).toFloat()
                    val x2 = ((seg.endTime / duration) * w).toFloat()
                    val y = getYForScore(seg.score, h)

                    val isKeep = seg.score >= scoreThreshold
                    val isDel = seg.score <= 2.0f

                    val topColor = when {
                        isKeep -> Emerald500
                        isDel -> Rose500
                        else -> Amber500
                    }

                    // 绘制下方面积渐变
                    val areaPath = Path().apply {
                        moveTo(x1, h)
                        lineTo(x1, y)
                        lineTo(x2, y)
                        lineTo(x2, h)
                        close()
                    }

                    drawPath(
                        path = areaPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(topColor.copy(alpha = 0.35f), Color.Transparent),
                            startY = y,
                            endY = h
                        ),
                        style = Fill
                    )

                    // 绘制顶部分段线
                    drawLine(
                        color = topColor,
                        start = Offset(x1, y),
                        end = Offset(x2, y),
                        strokeWidth = 3f
                    )

                    // 连接下一段的虚线过渡
                    if (idx < segments.size - 1) {
                        val nextSeg = segments[idx + 1]
                        val nextY = getYForScore(nextSeg.score, h)
                        drawLine(
                            color = topColor.copy(alpha = 0.5f),
                            start = Offset(x2, y),
                            end = Offset(x2, nextY),
                            strokeWidth = 1.5f,
                            pathEffect = dashEffect
                        )
                    }
                }

                // 2.5 绘制未研读区间半透明遮罩与断点虚线
                val maxAnalyzedTime = if (segments.size > 1) segments.maxOfOrNull { it.endTime } ?: 0.0 else 0.0
                if (segments.size > 1 && (duration - maxAnalyzedTime) > 5.0) {
                    val xCut = ((maxAnalyzedTime / duration) * w).toFloat().coerceIn(0f, w)
                    // 未分析区域半透明深色遮罩
                    drawRect(
                        color = Color(0x66020617),
                        topLeft = Offset(xCut, 0f),
                        size = androidx.compose.ui.geometry.Size(w - xCut, h)
                    )
                    // 断点分界竖线
                    drawLine(
                        color = Sky400.copy(alpha = 0.8f),
                        start = Offset(xCut, 0f),
                        end = Offset(xCut, h),
                        strokeWidth = 2f,
                        pathEffect = dashEffect
                    )
                }

                // 3. 绘制动态天蓝色阈值虚线
                val threshY = getYForScore(scoreThreshold, h)
                drawLine(
                    color = Sky400,
                    start = Offset(0f, threshY),
                    end = Offset(w, threshY),
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                )

                // 4. 绘制播放头竖线
                val playheadX = ((currentPlayheadSec / duration) * w).toFloat()
                drawLine(
                    color = Color.White,
                    start = Offset(playheadX, 0f),
                    end = Offset(playheadX, h),
                    strokeWidth = 2f
                )

                // 5. 绘制触控探针指示线
                touchX?.let { tx ->
                    drawLine(
                        color = Sky300,
                        start = Offset(tx, 0f),
                        end = Offset(tx, h),
                        strokeWidth = 2f
                    )
                }
            }

            // 触控浮动 Tooltip
            if (touchTime != null && touchSegment != null && touchX != null) {
                Box(
                    modifier = Modifier
                        .offset(x = 10.dp, y = 6.dp)
                        .background(Slate950.copy(alpha = 0.95f), RoundedCornerShape(8.dp))
                        .border(1.dp, Sky400.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    val m = (touchTime!! / 60).toInt()
                    val s = (touchTime!! % 60)
                    val timeStr = String.format("%02d:%04.1f", m, s)
                    val sc = touchSegment!!.score
                    val sum = touchSegment!!.summary.ifEmpty { touchSegment!!.reason }

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = timeStr,
                                color = Color.White,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${String.format("%.1f", sc)}分",
                                color = if (sc >= scoreThreshold) Emerald400 else Amber400,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (sum.isNotEmpty()) {
                            Text(
                                text = sum,
                                color = Slate300,
                                fontSize = 9.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
