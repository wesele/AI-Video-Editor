package com.smartvideo.app.data.model

/**
 * 用户应用配置
 */
data class AppSettings(
    val geminiBaseUrl: String = "http://192.168.31.233:8317",
    val geminiApiKey: String = "sk-X3FATzGIIlF5Q7HQx",
    val defaultModel: String = "gemini-3.8-flash-high",
    val defaultFfSpeed: Float = 4.0f,
    val defaultThreshold: Float = 6.0f,
    val deleteLowScore: Boolean = true
) {
    companion object {
        val AVAILABLE_MODELS = listOf(
            "gemini-3.8-flash-high" to "Gemini 3.8 Flash (推荐)",
            "gemini-3.7-flash-high" to "Gemini 3.7 Flash",
            "gemini-3.6-flash-high" to "Gemini 3.6 Flash",
            "gemini-3-flash" to "Gemini 3 Flash",
            "gemini-3.5-flash-lite" to "Gemini 3.5 Flash Lite",
            "gemini-3.1-pro-low" to "Gemini 3.1 Pro"
        )
    }
}
