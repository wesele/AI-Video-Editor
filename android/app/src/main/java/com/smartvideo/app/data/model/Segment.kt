package com.smartvideo.app.data.model

import com.google.gson.annotations.SerializedName
import java.util.UUID

/**
 * 剪辑分段核心实体
 */
data class Segment(
    @SerializedName("id")
    val id: String = UUID.randomUUID().toString().substring(0, 8),

    @SerializedName("start_time")
    val startTime: Double = 0.0,

    @SerializedName("end_time")
    val endTime: Double = 0.0,

    @SerializedName("duration")
    val duration: Double = 0.0,

    @SerializedName("action")
    val action: String = "keep", // "keep" | "fast_forward" | "delete"

    @SerializedName("speed")
    val speed: Float = 1.0f, // 1.0, 2.0, 4.0, 8.0, 16.0 or 0.0 for delete

    @SerializedName("score")
    val score: Float = 5.0f, // 1.0 ~ 10.0

    @SerializedName("reason")
    val reason: String = "",

    @SerializedName("summary")
    val summary: String = "",

    @SerializedName("scene_desc")
    val sceneDesc: String = "",

    @SerializedName("dialogue")
    val dialogue: String = ""
) {
    val isKeep: Boolean get() = action == "keep"
    val isFastForward: Boolean get() = action == "fast_forward"
    val isDelete: Boolean get() = action == "delete"

    val effectiveDuration: Double
        get() = when (action) {
            "keep" -> duration
            "fast_forward" -> if (speed > 0f) duration / speed else duration / 4.0
            else -> 0.0
        }
}
