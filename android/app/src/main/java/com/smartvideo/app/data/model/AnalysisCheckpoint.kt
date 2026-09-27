package com.smartvideo.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * 粗剪分析断点持久化模型
 * 用于在手机锁屏、异常中断或网络闪断后无缝断点续剪
 */
data class AnalysisCheckpoint(
    @SerializedName("video_uri")
    val videoUri: String,

    @SerializedName("total_duration")
    val totalDuration: Double,

    @SerializedName("chunk_window")
    val chunkWindow: Double = 90.0,

    @SerializedName("total_chunks")
    val totalChunks: Int,

    @SerializedName("completed_chunks")
    val completedChunks: Int,

    @SerializedName("completed_chunk_indices")
    val completedChunkIndices: List<Int> = emptyList(),

    @SerializedName("stage1_segments")
    val stage1Segments: List<Segment>,

    @SerializedName("style_instruction")
    val styleInstruction: String = "通用剪辑",

    @SerializedName("custom_prompt")
    val customPrompt: String = "",

    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis()
)
