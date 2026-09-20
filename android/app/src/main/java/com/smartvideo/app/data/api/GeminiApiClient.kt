package com.smartvideo.app.data.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.smartvideo.app.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class GeminiApiClient(
    private val baseUrl: String,
    private val apiKey: String
) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * 第一步：分段事实抽取 (Stage 1)
     */
    suspend fun extractChunkFacts(
        modelName: String,
        videoBase64: String,
        chunkIndex: Int,
        totalChunks: Int,
        startTime: Double,
        endTime: Double,
        styleInstruction: String = "通用剪辑",
        customPrompt: String = ""
    ): List<Segment> = withContext(Dispatchers.IO) {
        val chunkDur = endTime - startTime
        val prompt = """
            你是一名顶级专业视频视听分析师。你正在分析视频的第 ${chunkIndex + 1}/$totalChunks 分段。
            该分段在整片视频中的绝对时间范围是：【从 ${String.format("%.1f", startTime)} 秒 到 ${String.format("%.1f", endTime)} 秒】（本分段总时长 ${String.format("%.1f", chunkDur)} 秒）。
            你的任务是对这一时间区间内的视听事实信息进行全面细致的提取（用于后续全片全局宏观剪辑决策）：
            1. 观察画面场景环境、主体行为、关键动作（scene_desc）；
            2. 提取关键人物对话、解说台词或音频特征（dialogue）；
            3. 给出该局部的初步价值度打分（score: 1.0 - 10.0，浮点数或整数）；
            4. 给出局部看点一句话概述（summary）。

            【剪辑风格偏好】：
            $styleInstruction
            $customPrompt

            【输出严格要求】：
            1. 必须输出严格的 JSON 数组，连续无缝覆盖本段从 ${String.format("%.1f", startTime)} 秒 到 ${String.format("%.1f", endTime)} 秒的区间。
            2. 每个对象包含：
              - "start_time": 开始秒数（在 ${String.format("%.1f", startTime)} 到 ${String.format("%.1f", endTime)} 之间）
              - "end_time": 结束秒数（在 ${String.format("%.1f", startTime)} 到 ${String.format("%.1f", endTime)} 之间）
              - "scene_desc": 画面视觉活动与场景描述
              - "dialogue": 关键人物对话或旁白解说
              - "score": 1.0 到 10.0 之间的初步价值度打分
              - "summary": 本段事实内容精炼概述
              - "action": 初步动作建议 ("keep" | "fast_forward" | "delete")
        """.trimIndent()

        val requestPayload = GeminiRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(
                        GeminiPart(text = prompt),
                        GeminiPart(
                            inline_data = InlineData(
                                mime_type = "video/mp4",
                                data = videoBase64
                            )
                        )
                    )
                )
            ),
            generationConfig = GenerationConfig(response_mime_type = "application/json")
        )

        val rawItems = executeGeminiRequest(modelName, requestPayload)
        normalizeChunkSegments(rawItems, startTime, endTime)
    }

    /**
     * 第二步：全片二次宏观大模型研读与 1-10 分价值曲线计算 (Stage 2)
     */
    suspend fun globalMacroScore(
        modelName: String,
        totalDuration: Double,
        stage1Segments: List<Segment>,
        styleInstruction: String = "通用剪辑",
        customPrompt: String = "",
        defaultFfSpeed: Float = 4.0f
    ): List<Segment> = withContext(Dispatchers.IO) {
        val compactEvents = stage1Segments.map { s ->
            mapOf(
                "start" to Math.round(s.startTime * 10.0) / 10.0,
                "end" to Math.round(s.endTime * 10.0) / 10.0,
                "summary" to s.summary.ifEmpty { s.reason },
                "scene" to s.sceneDesc,
                "dialogue" to s.dialogue,
                "initial_score" to s.score
            )
        }

        val prompt = """
            你是一名顶级电影总剪辑师与总导演。
            整部视频总时长为 ${String.format("%.1f", totalDuration)} 秒。第一阶段已提取出覆盖全片各个时段的事实事件清单如下：
            ${gson.toJson(compactEvents)}

            【剪辑风格与偏好】：
            $styleInstruction
            $customPrompt

            【全局宏观评估与价值度打分任务】：
            请站在整部视频全局宏观叙事与观赏节奏的战略高度，统揽全局，对整部视频重新进行二次宏观审视，并给出全片从 0.0 秒至 ${String.format("%.1f", totalDuration)} 秒权威且精准的【价值度评分曲线 (score: 1.0 - 10.0)】：
            1. 识别全片真正具有高吸引力的高光时刻、关键对话、重要操作，与过场跑图、无聊等待、冗余废片；
            2. 为每个连续时间点输出权威的【视频价值度评分 (score: 1.0 - 10.0)】：
               - 1.0 - 2.0 分：完全无意义死屏、长时间静止、废镜头（极低价值，建议删除）；
               - 3.0 - 5.0 分：常规跑图赶路、翻找材料、下载等待、普通铺垫（中低价值，建议快进）；
               - 6.0 - 8.0 分：有效推动剧情、生动人物互动、关键讲解、精彩战局（高价值，建议保留 1.0x）；
               - 9.0 - 10.0 分：全片最高潮、关键反转、爆笑瞬间、最强操作（极高价值，必须保留 1.0x）；
            3. 输出严格的 JSON 数组，连续无缝覆盖全片 0.0 秒到 ${String.format("%.1f", totalDuration)} 秒。

            【输出 JSON 字段要求】：
            每个对象包含：
              - "start_time": 开始秒数 (float)
              - "end_time": 结束秒数 (float)
              - "score": 1.0 到 10.0 的评分 (float，如 3.5, 7.0, 9.5)
              - "action": 建议动作 ("keep" | "fast_forward" | "delete")
              - "summary": 场景精炼概述
              - "reason": 从全片全局视角的价值度判定理由
        """.trimIndent()

        val requestPayload = GeminiRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(GeminiPart(text = prompt))
                )
            ),
            generationConfig = GenerationConfig(response_mime_type = "application/json")
        )

        try {
            val rawItems = executeGeminiRequest(modelName, requestPayload)
            if (rawItems.isNotEmpty()) {
                return@withContext normalizeMasterTimeline(rawItems, totalDuration, defaultFfSpeed)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 回退至第一阶段平滑方案
        normalizeMasterTimeline(
            stage1Segments.map {
                RawSegmentItem(
                    it.startTime, it.endTime, it.action, it.score, it.summary, it.reason, it.sceneDesc, it.dialogue
                )
            },
            totalDuration,
            defaultFfSpeed
        )
    }

    private fun executeGeminiRequest(modelName: String, payload: GeminiRequest): List<RawSegmentItem> {
        val pureModel = modelName.removePrefix("models/")
        val url = if (baseUrl.endsWith("/v1beta/models")) {
            "$baseUrl/$pureModel:generateContent?key=$apiKey"
        } else {
            "${baseUrl.trimEnd('/')}/v1beta/models/$pureModel:generateContent?key=$apiKey"
        }

        val jsonBody = gson.toJson(payload)
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                throw RuntimeException("Gemini 请求失败 HTTP ${response.code}: $errBody")
            }

            val bodyString = response.body?.string() ?: throw RuntimeException("响应为空")
            val geminiResp = gson.fromJson(bodyString, GeminiResponse::class.java)
            val candidateText = geminiResp.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: throw RuntimeException("未从 Gemini 响应中解析出文本内容")

            return parseJsonSegments(candidateText)
        }
    }

    private fun parseJsonSegments(rawText: String): List<RawSegmentItem> {
        var clean = rawText.trim()
        if (clean.startsWith("```json")) {
            clean = clean.removePrefix("```json").trim()
        } else if (clean.startsWith("```")) {
            clean = clean.removePrefix("```").trim()
        }
        if (clean.endsWith("```")) {
            clean = clean.removeSuffix("```").trim()
        }

        // Try direct array parse
        try {
            val listType = object : TypeToken<List<RawSegmentItem>>() {}.type
            val result: List<RawSegmentItem>? = gson.fromJson(clean, listType)
            if (!result.isNullOrEmpty()) return result
        } catch (_: Exception) {}

        // Try regex match for JSON array
        val pattern = Pattern.compile("\\[\\s*\\{.*\\}\\s*\\]", Pattern.DOTALL)
        val matcher = pattern.matcher(clean)
        if (matcher.find()) {
            val jsonArrayStr = matcher.group(0)
            val listType = object : TypeToken<List<RawSegmentItem>>() {}.type
            return gson.fromJson(jsonArrayStr, listType)
        }

        return emptyList()
    }

    private fun normalizeChunkSegments(
        rawItems: List<RawSegmentItem>,
        startTime: Double,
        endTime: Double
    ): List<Segment> {
        val chunkDur = endTime - startTime
        val valid = mutableListOf<Segment>()

        for (item in rawItems) {
            var s = item.startTime ?: 0.0
            var e = item.endTime ?: chunkDur

            // Relative offset correction
            if (s < startTime && s < chunkDur) {
                s += startTime
                e += startTime
            }

            s = Math.max(startTime, Math.min(endTime, s))
            e = Math.max(s, Math.min(endTime, e))

            if (e - s >= 0.2) {
                valid.add(
                    Segment(
                        id = UUID.randomUUID().toString().substring(0, 8),
                        startTime = Math.round(s * 100.0) / 100.0,
                        endTime = Math.round(e * 100.0) / 100.0,
                        duration = Math.round((e - s) * 100.0) / 100.0,
                        action = item.action ?: "keep",
                        speed = if (item.action == "keep") 1.0f else 4.0f,
                        score = item.score ?: 5.0f,
                        reason = item.reason ?: "AI 事实片段",
                        summary = item.summary ?: "",
                        sceneDesc = item.sceneDesc ?: "",
                        dialogue = item.dialogue ?: ""
                    )
                )
            }
        }

        if (valid.isEmpty()) {
            return listOf(
                Segment(
                    id = UUID.randomUUID().toString().substring(0, 8),
                    startTime = startTime,
                    endTime = endTime,
                    duration = chunkDur,
                    action = "keep",
                    speed = 1.0f,
                    score = 5.0f,
                    reason = "保底分段",
                    summary = "整段视频"
                )
            )
        }

        valid.sortBy { it.startTime }
        return valid
    }

    private fun normalizeMasterTimeline(
        rawItems: List<RawSegmentItem>,
        totalDuration: Double,
        defaultFfSpeed: Float
    ): List<Segment> {
        if (rawItems.isEmpty()) {
            return listOf(
                Segment(
                    startTime = 0.0,
                    endTime = totalDuration,
                    duration = totalDuration,
                    action = "keep",
                    speed = 1.0f,
                    score = 6.0f,
                    reason = "全片默认保留",
                    summary = "完整视频"
                )
            )
        }

        val sorted = rawItems.filter { it.startTime != null && it.endTime != null }
            .sortedBy { it.startTime!! }

        val result = mutableListOf<Segment>()
        var currentTime = 0.0

        for (item in sorted) {
            val st = Math.max(0.0, item.startTime!!)
            val et = Math.min(totalDuration, item.endTime!!)

            // Fill gap
            if (st - currentTime > 0.4) {
                val gapDur = Math.round((st - currentTime) * 100.0) / 100.0
                result.add(
                    Segment(
                        startTime = Math.round(currentTime * 100.0) / 100.0,
                        endTime = Math.round(st * 100.0) / 100.0,
                        duration = gapDur,
                        action = "fast_forward",
                        speed = defaultFfSpeed,
                        score = 3.0f,
                        reason = "时间轴过渡衔接",
                        summary = "过渡场景"
                    )
                )
                currentTime = st
            }

            if (et <= st) continue

            val dur = Math.round((et - st) * 100.0) / 100.0
            val act = when (item.action) {
                "keep", "fast_forward", "delete" -> item.action
                else -> "keep"
            }
            val spd = when (act) {
                "keep" -> 1.0f
                "delete" -> 0.0f
                else -> defaultFfSpeed
            }
            val sc = Math.max(1.0f, Math.min(10.0f, item.score ?: 5.0f))

            result.add(
                Segment(
                    startTime = Math.round(st * 100.0) / 100.0,
                    endTime = Math.round(et * 100.0) / 100.0,
                    duration = dur,
                    action = act,
                    speed = spd,
                    score = Math.round(sc * 10.0f) / 10.0f,
                    reason = item.reason ?: "AI 判定段落",
                    summary = item.summary ?: "",
                    sceneDesc = item.sceneDesc ?: "",
                    dialogue = item.dialogue ?: ""
                )
            )
            currentTime = et
        }

        // Fill tail
        if (totalDuration - currentTime > 0.4) {
            val tailDur = Math.round((totalDuration - currentTime) * 100.0) / 100.0
            result.add(
                Segment(
                    startTime = Math.round(currentTime * 100.0) / 100.0,
                    endTime = Math.round(totalDuration * 100.0) / 100.0,
                    duration = tailDur,
                    action = "keep",
                    speed = 1.0f,
                    score = 5.0f,
                    reason = "结尾保留段落",
                    summary = "视频结尾"
                )
            )
        }

        return result
    }
}
