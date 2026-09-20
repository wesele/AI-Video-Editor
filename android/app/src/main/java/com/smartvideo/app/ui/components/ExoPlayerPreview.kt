package com.smartvideo.app.ui.components

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.smartvideo.app.data.model.Segment
import com.smartvideo.app.ui.theme.*
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun ExoPlayerPreview(
    videoUri: Uri,
    totalDurationSec: Double,
    segments: List<Segment>,
    onCurrentTimeUpdate: (Double) -> Unit,
    onSplitCurrentTime: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var currentTimeSec by remember { mutableStateOf(0.0) }
    var simulatePreview by remember { mutableStateOf(false) }
    var activeSpeed by remember { mutableStateOf(1.0f) }

    val exoPlayer = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
            })
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    // 播放监听与模拟成片联动 (快进变频与跳删)
    LaunchedEffect(isPlaying, simulatePreview, segments) {
        while (true) {
            if (exoPlayer.isPlaying || isPlaying) {
                val currentSec = exoPlayer.currentPosition / 1000.0
                currentTimeSec = currentSec
                onCurrentTimeUpdate(currentSec)

                if (simulatePreview) {
                    val currentSeg = segments.find { currentSec >= it.startTime && currentSec < it.endTime }
                    if (currentSeg != null) {
                        when (currentSeg.action) {
                            "delete" -> {
                                val targetSec = Math.min(currentSeg.endTime + 0.05, totalDurationSec)
                                exoPlayer.seekTo((targetSec * 1000).toLong())
                            }
                            "fast_forward" -> {
                                val spd = Math.max(0.5f, Math.min(16.0f, currentSeg.speed))
                                if (exoPlayer.playbackParameters.speed != spd) {
                                    exoPlayer.setPlaybackSpeed(spd)
                                    activeSpeed = spd
                                }
                            }
                            else -> {
                                if (exoPlayer.playbackParameters.speed != 1.0f) {
                                    exoPlayer.setPlaybackSpeed(1.0f)
                                    activeSpeed = 1.0f
                                }
                            }
                        }
                    }
                } else {
                    if (exoPlayer.playbackParameters.speed != 1.0f) {
                        exoPlayer.setPlaybackSpeed(1.0f)
                        activeSpeed = 1.0f
                    }
                }
            }
            delay(100)
        }
    }

    fun formatTime(sec: Double): String {
        val m = (sec / 60).toInt()
        val s = (sec % 60)
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
        // 顶部控制条
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Sky500)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${formatTime(currentTimeSec)} / ${formatTime(totalDurationSec)}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                if (simulatePreview && activeSpeed != 1.0f) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${activeSpeed}x 快进",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Amber400,
                        modifier = Modifier
                            .background(Amber500.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            .border(1.dp, Amber500.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // 当前帧切分 (Split)
                OutlinedButton(
                    onClick = { onSplitCurrentTime(currentTimeSec) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Sky400),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("切分", fontSize = 11.sp)
                }

                Spacer(modifier = Modifier.width(6.dp))

                // 模拟成片预览切换
                Button(
                    onClick = { simulatePreview = !simulatePreview },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (simulatePreview) Emerald500 else Slate800,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (simulatePreview) "模拟中" else "原片", fontSize = 11.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 播放器容器 (16:9 纵横比)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false // 使用我们定制的移动端控制条
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
