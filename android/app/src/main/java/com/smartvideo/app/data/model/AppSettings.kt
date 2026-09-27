package com.smartvideo.app.data.model

import android.content.Context

/**
 * 用户应用配置
 */
data class AppSettings(
    val geminiBaseUrl: String = "http://192.168.31.233:8317",
    val geminiApiKey: String = "sk-X3FATzGIIlF5Q7HQx",
    val defaultModel: String = "gemini-3.1-flash-lite",
    val defaultFfSpeed: Float = 4.0f,
    val defaultThreshold: Float = 6.0f,
    val deleteLowScore: Boolean = true,
    val concurrency: Int = 4
) {
    fun save(context: Context) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putString(KEY_BASE_URL, geminiBaseUrl)
            .putString(KEY_API_KEY, geminiApiKey)
            .putString(KEY_DEFAULT_MODEL, defaultModel)
            .putFloat(KEY_FF_SPEED, defaultFfSpeed)
            .putFloat(KEY_THRESHOLD, defaultThreshold)
            .putBoolean(KEY_DELETE_LOW_SCORE, deleteLowScore)
            .putInt(KEY_CONCURRENCY, concurrency.coerceIn(1, 8))
            .apply()
    }

    companion object {
        private const val PREF_NAME = "smartvideo_app_settings"
        private const val KEY_BASE_URL = "gemini_base_url"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_DEFAULT_MODEL = "default_model"
        private const val KEY_FF_SPEED = "default_ff_speed"
        private const val KEY_THRESHOLD = "default_threshold"
        private const val KEY_DELETE_LOW_SCORE = "delete_low_score"
        private const val KEY_CONCURRENCY = "concurrency"

        val AVAILABLE_MODELS = listOf(
            "gemini-3.1-flash-lite" to "Gemini 3.1 Flash Lite (极速推荐)",
            "gemini-3.8-flash-high" to "Gemini 3.8 Flash (深度精析)",
            "gemini-3.7-flash-high" to "Gemini 3.7 Flash",
            "gemini-3.6-flash-high" to "Gemini 3.6 Flash",
            "gemini-3.5-flash-lite" to "Gemini 3.5 Flash Lite",
            "gemini-3-flash" to "Gemini 3 Flash",
            "gemini-3.1-pro-low" to "Gemini 3.1 Pro"
        )

        fun load(context: Context): AppSettings {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val defaultInstance = AppSettings()
            return AppSettings(
                geminiBaseUrl = sp.getString(KEY_BASE_URL, defaultInstance.geminiBaseUrl) ?: defaultInstance.geminiBaseUrl,
                geminiApiKey = sp.getString(KEY_API_KEY, defaultInstance.geminiApiKey) ?: defaultInstance.geminiApiKey,
                defaultModel = sp.getString(KEY_DEFAULT_MODEL, defaultInstance.defaultModel) ?: defaultInstance.defaultModel,
                defaultFfSpeed = sp.getFloat(KEY_FF_SPEED, defaultInstance.defaultFfSpeed),
                defaultThreshold = sp.getFloat(KEY_THRESHOLD, defaultInstance.defaultThreshold),
                deleteLowScore = sp.getBoolean(KEY_DELETE_LOW_SCORE, defaultInstance.deleteLowScore),
                concurrency = sp.getInt(KEY_CONCURRENCY, defaultInstance.concurrency).coerceIn(1, 8)
            )
        }
    }
}
