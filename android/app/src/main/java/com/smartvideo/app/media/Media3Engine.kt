package com.smartvideo.app.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.*
import com.smartvideo.app.data.model.Segment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

@OptIn(UnstableApi::class)
class Media3Engine(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var activeExportTransformer: Transformer? = null

    fun cancelExport() {
        mainHandler.post {
            try {
                activeExportTransformer?.cancel()
                activeExportTransformer = null
            } catch (_: Throwable) {}
        }
    }

    /**
     * 生成供 Gemini 视觉理解分析用的超轻量分段代理视频（360p 低码率 MP4）
     * 完全通过系统硬件 MediaCodec 编解码，无 FFmpeg 依赖
     */
    suspend fun sliceChunkProxy(
        videoUri: Uri,
        startTimeSec: Double,
        endTimeSec: Double,
        chunkIndex: Int,
        isPortrait: Boolean = false
    ): File = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "proxy_chunk_${chunkIndex}_${System.currentTimeMillis()}.mp4")
        if (tempFile.exists()) tempFile.delete()

        val startMs = (startTimeSec * 1000).toLong()
        val endMs = (endTimeSec * 1000).toLong()

        val deferred = CompletableDeferred<File>()

        mainHandler.post {
            try {
                val clippingConfig = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()

                val mediaItem = MediaItem.Builder()
                    .setUri(videoUri)
                    .setClippingConfiguration(clippingConfig)
                    .build()

                // 根据横竖屏动态设置分辨率（竖屏 360x640，横屏 640x360），硬件降采样极速处理
                val targetW = if (isPortrait) 360 else 640
                val targetH = if (isPortrait) 640 else 360
                val presentationEffect = Presentation.createForWidthAndHeight(
                    targetW,
                    targetH,
                    Presentation.LAYOUT_SCALE_TO_FIT
                )
                // 针对 Gemini 视觉识别进行 2fps 硬件抽帧（Gemini 官方标准为 1fps），极速减少 93% 冗余帧，转码与网络上传提升数十倍
                val frameDropEffect = FrameDropEffect.createDefaultFrameDropEffect(2.0f)
                val effects = Effects(listOf(), listOf<Effect>(presentationEffect, frameDropEffect))

                val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                    .setEffects(effects)
                    .setRemoveAudio(false)
                    .build()

                // 开启硬件编码器回退容错，确保各类真机芯片组（高通/联发科/三星）平稳运行
                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setEnableFallback(true)
                    .build()

                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            deferred.complete(tempFile)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            deferred.completeExceptionally(exportException)
                        }
                    })
                    .build()

                transformer.start(editedMediaItem, tempFile.absolutePath)
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            }
        }

        deferred.await()
    }

    /**
     * 最终成片硬件加速合成导出
     * 拼接保留片段与快进片段，跳过删除片段，完成后写入系统相册 (Movies/SmartVideo)
     */
    suspend fun exportComposition(
        videoUri: Uri,
        segments: List<Segment>,
        targetWidth: Int = 1280,
        targetHeight: Int = 720,
        muteFastForwardAudio: Boolean = false,
        onProgress: (Float) -> Unit
    ): Uri = withContext(Dispatchers.IO) {
        val tempOutput = File(context.cacheDir, "final_export_${System.currentTimeMillis()}.mp4")
        if (tempOutput.exists()) tempOutput.delete()

        val validSegments = segments.filter { !it.isDelete && it.duration > 0.1 }
        if (validSegments.isEmpty()) {
            throw IllegalArgumentException("没有可导出的有效保留或快进片段")
        }

        val deferred = CompletableDeferred<File>()

        mainHandler.post {
            try {
                val editedMediaItemList = mutableListOf<EditedMediaItem>()

                for (seg in validSegments) {
                    val startMs = (seg.startTime * 1000).toLong()
                    val endMs = (seg.endTime * 1000).toLong()

                    val clippingConfig = MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build()

                    val mediaItem = MediaItem.Builder()
                        .setUri(videoUri)
                        .setClippingConfiguration(clippingConfig)
                        .build()

                    val speed = if (seg.isFastForward && seg.speed > 0f) seg.speed else 1.0f
                    val shouldMute = seg.isFastForward && (muteFastForwardAudio || speed >= 4.0f)

                    val videoEffects = mutableListOf<Effect>()
                    if (targetWidth > 0 && targetHeight > 0) {
                        videoEffects.add(
                            Presentation.createForWidthAndHeight(
                                targetWidth,
                                targetHeight,
                                Presentation.LAYOUT_SCALE_TO_FIT
                            )
                        )
                    }
                    if (seg.isFastForward && speed != 1.0f) {
                        videoEffects.add(SpeedChangeEffect(speed))
                    }

                    val audioProcessors = mutableListOf<AudioProcessor>()
                    if (seg.isFastForward && !shouldMute && speed != 1.0f) {
                        val sonic = SonicAudioProcessor().apply {
                            setSpeed(speed)
                        }
                        audioProcessors.add(sonic)
                    }

                    val effects = Effects(audioProcessors, videoEffects)

                    val item = EditedMediaItem.Builder(mediaItem)
                        .setEffects(effects)
                        .setRemoveAudio(shouldMute)
                        .build()

                    editedMediaItemList.add(item)
                }

                val sequence = EditedMediaItemSequence(editedMediaItemList)
                val composition = Composition.Builder(listOf(sequence))
                    .experimentalSetForceAudioTrack(true)
                    .build()

                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        activeExportTransformer = null
                        deferred.complete(tempOutput)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        activeExportTransformer = null
                        deferred.completeExceptionally(exportException)
                    }
                }

                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setEnableFallback(true)
                    .build()

                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    .addListener(listener)
                    .build()

                activeExportTransformer = transformer

                transformer.start(composition, tempOutput.absolutePath)
            } catch (t: Throwable) {
                activeExportTransformer = null
                deferred.completeExceptionally(t)
            }
        }

        // 轮询导出硬件编码进度并回调
        val progressHolder = ProgressHolder()
        while (!deferred.isCompleted) {
            delay(300)
            if (deferred.isCompleted) break
            withContext(Dispatchers.Main) {
                val t = activeExportTransformer
                if (t != null && !deferred.isCompleted) {
                    val state = t.getProgress(progressHolder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        val p = progressHolder.progress.toFloat()
                        withContext(Dispatchers.IO) {
                            onProgress(p)
                        }
                    }
                }
            }
        }

        val completedFile = deferred.await()

        // 将最终成片保存至手机原生系统相册 MediaStore (Movies/SmartVideo)
        saveToSystemGallery(completedFile)
    }

    private fun saveToSystemGallery(file: File): Uri {
        val fileName = "SmartVideo_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SmartVideo")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = context.contentResolver.insert(collection, values)
            ?: throw RuntimeException("无法在系统相册创建视频记录")

        context.contentResolver.openOutputStream(itemUri)?.use { out ->
            FileInputStream(file).use { input ->
                input.copyTo(out)
            }
        }

        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        context.contentResolver.update(itemUri, values, null, null)

        // 清理缓存临时文件
        if (file.exists()) file.delete()

        return itemUri
    }
}
