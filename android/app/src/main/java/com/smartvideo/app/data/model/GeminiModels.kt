package com.smartvideo.app.data.model

import com.google.gson.annotations.SerializedName

data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GenerationConfig? = null
)

data class GeminiContent(
    val parts: List<GeminiPart>
)

data class GeminiPart(
    val text: String? = null,
    val inline_data: InlineData? = null
)

data class InlineData(
    val mime_type: String,
    val data: String // Base64 encoded video
)

data class GenerationConfig(
    val response_mime_type: String? = "application/json"
)

data class GeminiResponse(
    val candidates: List<Candidate>? = null
)

data class Candidate(
    val content: GeminiContent? = null
)

/**
 * 模型返回的原始分段结构
 */
data class RawSegmentItem(
    @SerializedName("start_time")
    val startTime: Double? = null,

    @SerializedName("end_time")
    val endTime: Double? = null,

    @SerializedName("action")
    val action: String? = null,

    @SerializedName("score")
    val score: Float? = null,

    @SerializedName("summary")
    val summary: String? = null,

    @SerializedName("reason")
    val reason: String? = null,

    @SerializedName("scene_desc")
    val sceneDesc: String? = null,

    @SerializedName("dialogue")
    val dialogue: String? = null
)
