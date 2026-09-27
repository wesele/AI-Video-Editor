package com.smartvideo.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * 最终粗剪研读与价值打分持久化模型
 * 保证大模型分析完成后的全部分段永久安全落地，永不因进程重启、导出失败或意外中断而丢失
 */
data class AnalysisResult(
    @SerializedName("video_uri")
    val videoUri: String,

    @SerializedName("total_duration")
    val totalDuration: Double,

    @SerializedName("segments")
    val segments: List<Segment>,

    @SerializedName("style_instruction")
    val styleInstruction: String = "通用剪辑",

    @SerializedName("custom_prompt")
    val customPrompt: String = "",

    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis()
)
