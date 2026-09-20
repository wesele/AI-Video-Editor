package com.smartvideo.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartvideo.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportBottomSheet(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    estimatedOutputSec: Double,
    onConfirmExport: (targetWidth: Int, targetHeight: Int, muteFfAudio: Boolean) -> Unit
) {
    if (!isOpen) return

    var selectedResolution by remember { mutableStateOf("1080p") }
    var muteFfAudio by remember { mutableStateOf(false) }

    val modalBottomSheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = modalBottomSheetState,
        containerColor = Slate900,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Slate700) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.ElectricBolt,
                    contentDescription = null,
                    tint = Sky400,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "配置并导出视频 (Android 原生 Media3)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 导出说明卡片
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Slate950)
                    .border(1.dp, Slate800, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        text = "硬件加速：调用手机芯片 MediaCodec 原生硬编",
                        fontSize = 11.sp,
                        color = Emerald400,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "保存位置：系统相册 Movies/SmartVideo，无损帧级拼装",
                        fontSize = 11.sp,
                        color = Slate400
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 分辨率选择
            Text(text = "输出分辨率:", fontSize = 12.sp, color = Slate300, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("原画" to (0 to 0), "1080p" to (1920 to 1080), "720p" to (1280 to 720)).forEach { (label, _) ->
                    val isSelected = selectedResolution == label
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Sky500 else Slate800)
                            .clickable { selectedResolution = label }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) Color.White else Slate300
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 快进音频静音开关
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Slate950)
                    .clickable { muteFfAudio = !muteFfAudio }
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "快进段音频静音", fontSize = 12.sp, color = Color.White)
                    Text(text = "高倍速快进时消除变频杂音", fontSize = 10.sp, color = Slate400)
                }
                Switch(
                    checked = muteFfAudio,
                    onCheckedChange = { muteFfAudio = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Sky500,
                        uncheckedTrackColor = Slate800
                    )
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 确认导出按钮
            Button(
                onClick = {
                    val (w, h) = when (selectedResolution) {
                        "720p" -> 1280 to 720
                        else -> 1920 to 1080
                    }
                    onConfirmExport(w, h, muteFfAudio)
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Sky500)
            ) {
                Icon(Icons.Default.ElectricBolt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "开始硬件极速导出合成",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
