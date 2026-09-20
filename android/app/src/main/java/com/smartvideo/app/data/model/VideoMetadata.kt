package com.smartvideo.app.data.model

import android.net.Uri

/**
 * 选中的原始视频元数据
 */
data class VideoMetadata(
    val uri: Uri,
    val filename: String,
    val filesizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int = 0,
    val fps: Float = 30f
) {
    val durationSec: Double get() = durationMs / 1000.0
    val filesizeFormatted: String
        get() = String.format("%.2f MB", filesizeBytes / (1024.0 * 1024.0))

    val formattedDuration: String
        get() {
            val totalSec = durationMs / 1000
            val m = totalSec / 60
            val s = totalSec % 60
            return String.format("%02d:%02d", m, s)
        }
}
