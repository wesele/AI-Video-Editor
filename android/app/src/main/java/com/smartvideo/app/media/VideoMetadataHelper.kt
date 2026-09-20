package com.smartvideo.app.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.smartvideo.app.data.model.VideoMetadata

object VideoMetadataHelper {

    fun extractMetadata(context: Context, uri: Uri): VideoMetadata {
        val retriever = MediaMetadataRetriever()
        var durationMs = 0L
        var width = 1280
        var height = 720
        var rotation = 0
        var filename = "video.mp4"
        var filesizeBytes = 0L

        try {
            retriever.setDataSource(context, uri)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
            rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

            // If rotated 90 or 270, swap width and height for display
            if (rotation == 90 || rotation == 270) {
                val temp = width
                width = height
                height = temp
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }

        // Query filename and size from ContentResolver
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex != -1) filename = cursor.getString(nameIndex) ?: "video.mp4"
                    if (sizeIndex != -1) filesizeBytes = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return VideoMetadata(
            uri = uri,
            filename = filename,
            filesizeBytes = filesizeBytes,
            durationMs = durationMs,
            width = width,
            height = height,
            rotation = rotation
        )
    }
}
