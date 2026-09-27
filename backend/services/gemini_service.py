import os
import re
import json
import base64
import asyncio
import httpx
import uuid
from typing import Dict, Any, List, Optional
from backend.config import get_settings, CHECKPOINT_FILE, CHECKPOINTS_DIR
from backend.services.ffmpeg_service import ffmpeg_service

STYLE_PROMPTS = {
    "general": (
        "通用剪辑原则：请分析整段视频。\n"
        "- 【保留(keep)】：有趣、剧情高潮、核心对话/解说、精彩动作、关键信息传递部分，保持正常速度（1.0x）。\n"
        "- 【快进(fast_forward)】：剧情过渡、跑图/行走、操作等待、无关键对话但仍需展现过程的部分，采用快进（建议2x-8x）。\n"
        "- 【删除(delete)】：完全黑屏、无意义静止死屏、镜头严重失焦晃动、冗长无用等待直接删除。"
    ),
    "vlog": (
        "Vlog/生活日常原则：\n"
        "- 【保留(keep)】：人物对话、面部表情生动、美食风景特写、有趣互动事件保持正常速度。\n"
        "- 【快进(fast_forward)】：赶路、转场行走、整理物品、准备烹饪等过程性画面进行快进展示。\n"
        "- 【删除(delete)】：调机卡顿、无声静止、多余废镜头予以剔除。"
    ),
    "gaming": (
        "游戏高光原则：\n"
        "- 【保留(keep)】：击杀、团战高光、BOSS决战、搞笑翻车、主播高能解说及精彩操作保持正常速度。\n"
        "- 【快进(fast_forward)】：跑图赶路、刷小怪、等待复活、整理背包装备过程采用快进加速。\n"
        "- 【删除(delete)】：挂机读盘黑屏、长时间静止等待建议剔除。"
    ),
    "tutorial": (
        "网课教程原则：\n"
        "- 【保留(keep)】：讲师讲解关键原理、核心代码敲写、关键操作步骤保持原速。\n"
        "- 【快进(fast_forward)】：软件安装进度条、大文件下载等待、重复性操作予以快进。\n"
        "- 【删除(delete)】：设备断网、讲师喝水长停顿、无内容死屏予以删除。"
    ),
    "meeting": (
        "会议记录原则：\n"
        "- 【保留(keep)】：代表发言、重要提问与答复、决策结论讨论保持原速。\n"
        "- 【快进(fast_forward)】：翻找PPT、等待参会者上线、散会离场等过程快进。\n"
        "- 【删除(delete)】：完全静止无人说话的空白期予以剔除。"
    ),
}

class GeminiService:
    def get_checkpoint(self, video_id: Optional[str] = None) -> Optional[Dict[str, Any]]:
        if not CHECKPOINT_FILE.exists():
            return None
        try:
            with open(CHECKPOINT_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            if video_id and data.get("video_id") != video_id:
                return None
            return data
        except Exception:
            return None

    def save_checkpoint(self, checkpoint_data: Dict[str, Any]) -> None:
        try:
            CHECKPOINTS_DIR.mkdir(parents=True, exist_ok=True)
            temp_path = CHECKPOINT_FILE.with_suffix(".tmp")
            with open(temp_path, "w", encoding="utf-8") as f:
                json.dump(checkpoint_data, f, ensure_ascii=False, indent=2)
            os.replace(temp_path, CHECKPOINT_FILE)
        except Exception as e:
            print(f"写入检查点失败: {e}")

    def clear_checkpoint(self) -> None:
        try:
            if CHECKPOINT_FILE.exists():
                CHECKPOINT_FILE.unlink()
        except Exception as e:
            print(f"清除检查点失败: {e}")

    async def analyze_video(
        self,
        video_path: str,
        video_id: str = "",
        model_name: str = "gemini-3.8-flash-high",
        style_preset: str = "general",
        custom_prompt: Optional[str] = "",
        default_ff_speed: float = 4.0,
        resume: bool = False,
        concurrency: int = 4,
        progress_callback: Optional[Any] = None,
    ) -> List[Dict[str, Any]]:
        safe_concurrency = max(1, min(8, int(concurrency)))
        settings = get_settings()
        base_url = settings.get("gemini_base_url", "http://192.168.31.233:8317").rstrip("/")
        api_key = settings.get("gemini_api_key", "").strip()

        # Step 1: Probe video for duration
        meta = ffmpeg_service.probe_video(video_path)
        total_duration = meta["duration"]

        # Step 2: Define chunks (180 seconds / 3 minutes per chunk for optimal detail & speed)
        CHUNK_LEN = 180.0
        if total_duration <= CHUNK_LEN:
            chunks = [(0.0, total_duration)]
        else:
            chunks = []
            curr = 0.0
            while curr < total_duration:
                nxt = min(total_duration, curr + CHUNK_LEN)
                chunks.append((round(curr, 2), round(nxt, 2)))
                curr = nxt

        total_chunks = len(chunks)
        all_segments: List[Dict[str, Any]] = []

        # Checkpoint restoration or initialization
        existing_ckpt = self.get_checkpoint(video_id)
        if resume and existing_ckpt and existing_ckpt.get("video_id") == video_id:
            checkpoint = existing_ckpt
            stage1_completed = {c["chunk_index"]: c for c in checkpoint.get("stage1_completed_chunks", [])}
            checkpoint["status"] = "processing"
            checkpoint["failed_stage"] = None
            checkpoint["failed_chunk_index"] = None
            checkpoint["error_message"] = None
            checkpoint["config"] = {
                "model": model_name,
                "style_preset": style_preset,
                "custom_prompt": custom_prompt,
                "default_fast_forward_speed": default_ff_speed,
                "concurrency": safe_concurrency,
            }
            self.save_checkpoint(checkpoint)
        else:
            stage1_completed = {}
            checkpoint = {
                "video_id": video_id,
                "video_path": video_path,
                "total_duration": total_duration,
                "chunk_len": CHUNK_LEN,
                "total_chunks": total_chunks,
                "status": "processing",
                "failed_stage": None,
                "failed_chunk_index": None,
                "error_message": None,
                "config": {
                    "model": model_name,
                    "style_preset": style_preset,
                    "custom_prompt": custom_prompt,
                    "default_fast_forward_speed": default_ff_speed,
                    "concurrency": safe_concurrency,
                },
                "stage1_completed_chunks": [],
                "final_segments": None,
            }
            self.save_checkpoint(checkpoint)

        style_instruction = STYLE_PROMPTS.get(style_preset, STYLE_PROMPTS["general"])
        custom_part = f"\n【用户补充要求】：\n{custom_prompt.strip()}" if custom_prompt and custom_prompt.strip() else ""
        pure_model = model_name.replace("models/", "")
        endpoint = f"{base_url}/v1beta/models/{pure_model}:generateContent?key={api_key}"

        def fmt_time(s: float) -> str:
            m = int(s // 60)
            sec = int(s % 60)
            return f"{m:02d}:{sec:02d}"

        # Stage 1: Chunk factual extraction with concurrency (1-8, default 4)
        completed_chunks_map: Dict[int, List[Dict[str, Any]]] = {}
        for c in checkpoint.get("stage1_completed_chunks", []):
            completed_chunks_map[c["chunk_index"]] = c["segments"]

        completed_count = len(completed_chunks_map)
        checkpoint_lock = asyncio.Lock()
        sem = asyncio.Semaphore(safe_concurrency)
        first_error: Optional[Exception] = None

        def get_current_sorted_segments() -> List[Dict[str, Any]]:
            res = []
            for i in sorted(completed_chunks_map.keys()):
                res.extend(completed_chunks_map[i])
            return res

        if completed_count > 0 and progress_callback:
            init_pct = round((completed_count / total_chunks) * 75.0, 1)
            progress_callback(
                init_pct,
                f"[第一步 事实提取] 已复用已保存的前序 {completed_count}/{total_chunks} 段事实...",
                get_current_sorted_segments(),
            )

        async def process_single_chunk(idx: int, st: float, et: float):
            nonlocal first_error, completed_count
            if first_error is not None:
                return

            chunk_dur = round(et - st, 2)
            async with sem:
                if first_error is not None:
                    return

                if progress_callback:
                    chunk_pct_slice = round((completed_count / total_chunks) * 75.0 + 0.5, 1)
                    progress_callback(
                        chunk_pct_slice,
                        f"[第一步 事实提取] 正在切片与分析第 {idx+1}/{total_chunks} 段 ({fmt_time(st)} ~ {fmt_time(et)}) [并发: {safe_concurrency}]...",
                        get_current_sorted_segments(),
                    )

                # Auto-retry up to 3 times
                last_err = None
                norm_chunk = None

                for attempt in range(1, 4):
                    if first_error is not None:
                        return
                    chunk_path = None
                    try:
                        # Step A: Slice chunk to lightweight proxy (typically 2-4 MB)
                        chunk_path = await ffmpeg_service.slice_chunk_proxy_async(video_path, st, et, idx)

                        # Step B: Base64 encode chunk
                        with open(chunk_path, "rb") as f:
                            video_b64 = base64.b64encode(f.read()).decode("utf-8")

                        if os.path.exists(chunk_path):
                            try:
                                os.remove(chunk_path)
                                chunk_path = None
                            except Exception:
                                pass

                        # Step C: Prompt for chunk factual extraction (Stage 1)
                        chunk_prompt = f"""你是一名顶级专业视频视听分析师。你正在分析视频的第 {idx+1}/{total_chunks} 分段。
该分段在整片视频中的绝对时间范围是：【从 {st:.1f} 秒 到 {et:.1f} 秒】（本分段总时长 {chunk_dur:.1f} 秒）。
你的任务是对这一时间区间内的视听事实信息进行全面细致的提取（用于后续全片全局宏观剪辑决策）：
1. 观察画面场景环境、主体行为、关键动作（scene_desc）；
2. 提取关键人物对话、解说台词或音频特征（dialogue）；
3. 给出该局部的初步价值度打分（score: 1.0 - 10.0，浮点数或整数）；
4. 给出局部看点一句话概述（summary）。

【剪辑风格关注偏好】：
{style_instruction}
{custom_part}

【输出严格要求】：
1. 必须输出严格的 JSON 数组，连续无缝覆盖本段从 {st:.1f} 秒 到 {et:.1f} 秒的区间。
2. 每个对象包含：
  - "start_time": 开始秒数（在 {st:.1f} 到 {et:.1f} 之间）
  - "end_time": 结束秒数（在 {st:.1f} 到 {et:.1f} 之间）
  - "scene_desc": 画面视觉活动与场景描述
  - "dialogue": 关键人物对话或旁白解说
  - "score": 1.0 到 10.0 之间的初步价值度打分
  - "summary": 本段事实内容精炼概述
  - "action": 初步动作建议 ("keep" | "fast_forward" | "delete")
"""

                        payload = {
                            "contents": [
                                {
                                    "parts": [
                                        {"text": chunk_prompt},
                                        {
                                            "inline_data": {
                                                "mime_type": "video/mp4",
                                                "data": video_b64
                                            }
                                        }
                                    ]
                                }
                            ],
                            "generationConfig": {
                                "response_mime_type": "application/json"
                            }
                        }

                        if progress_callback:
                            chunk_pct_ai = round((completed_count / total_chunks) * 75.0 + 1.0, 1)
                            retry_suffix = f" (第{attempt}次重试)..." if attempt > 1 else "..."
                            progress_callback(
                                chunk_pct_ai,
                                f"[第一步 事实提取] {pure_model} 正在分析第 {idx+1}/{total_chunks} 段 ({fmt_time(st)} ~ {fmt_time(et)}){retry_suffix}",
                                get_current_sorted_segments(),
                            )

                        async with httpx.AsyncClient(timeout=120.0) as client:
                            resp = await client.post(endpoint, json=payload)
                            if resp.status_code != 200:
                                raise RuntimeError(f"Gemini API 响应异常 (HTTP {resp.status_code}): {resp.text[:200]}")
                            res_json = resp.json()
                            if "candidates" not in res_json or not res_json["candidates"]:
                                raise RuntimeError("Gemini 返回候选内容为空")
                            candidate = res_json["candidates"][0]
                            if "content" not in candidate or "parts" not in candidate["content"]:
                                raise RuntimeError("Gemini 返回数据结构异常")
                            raw_text = candidate["content"]["parts"][0]["text"]
                            raw_segments = self._parse_json_segments(raw_text)

                        norm_chunk = self._normalize_chunk_timeline(raw_segments, st, et, default_ff_speed)
                        last_err = None
                        break
                    except Exception as e:
                        last_err = e
                        print(f"分段 {idx+1}/{total_chunks} 第 {attempt} 次提取异常: {e}")
                        if chunk_path and os.path.exists(chunk_path):
                            try:
                                os.remove(chunk_path)
                            except Exception:
                                pass
                        if attempt < 3:
                            await asyncio.sleep(attempt * 1.5)

                if last_err is not None:
                    async with checkpoint_lock:
                        if first_error is None:
                            first_error = RuntimeError(f"第 {idx+1}/{total_chunks} 段 ({fmt_time(st)}~{fmt_time(et)}) 事实提取失败: {str(last_err)}")
                            checkpoint["status"] = "failed"
                            checkpoint["failed_stage"] = "stage1"
                            checkpoint["failed_chunk_index"] = idx
                            checkpoint["error_message"] = str(last_err)
                            self.save_checkpoint(checkpoint)
                    raise first_error

                async with checkpoint_lock:
                    completed_chunks_map[idx] = norm_chunk
                    completed_count += 1
                    checkpoint["stage1_completed_chunks"] = [
                        {
                            "chunk_index": i,
                            "st": chunks[i][0],
                            "et": chunks[i][1],
                            "segments": completed_chunks_map[i],
                        }
                        for i in sorted(completed_chunks_map.keys())
                    ]
                    checkpoint["status"] = "processing"
                    self.save_checkpoint(checkpoint)

                    if progress_callback:
                        chunk_pct_done = round((completed_count / total_chunks) * 75.0, 1)
                        sorted_segs = get_current_sorted_segments()
                        progress_callback(
                            chunk_pct_done,
                            f"[第一步 事实提取] 已完成 {completed_count}/{total_chunks} 段！已提取 {len(sorted_segs)} 个事实事件 (并发: {safe_concurrency})",
                            sorted_segs,
                        )

        # Launch concurrent tasks for remaining chunks
        pending_chunks = [
            (idx, st, et) for idx, (st, et) in enumerate(chunks) if idx not in completed_chunks_map
        ]

        if pending_chunks:
            tasks = [process_single_chunk(idx, st, et) for (idx, st, et) in pending_chunks]
            results = await asyncio.gather(*tasks, return_exceptions=True)
            for r in results:
                if isinstance(r, Exception):
                    raise r

        all_segments = get_current_sorted_segments()

        # =========================================================================
        # Stage 2: Global Macro Analysis & 1-10 Value Curve Evaluation
        # =========================================================================
        if progress_callback:
            progress_callback(
                80.0,
                f"[第二步 全局宏观研读] Gemini 正在对全片 {len(all_segments)} 个事实事件进行全局通盘审视与价值度评分...",
                all_segments,
            )

        final_segments = []
        last_err_s2 = None

        compact_events = []
        for s in all_segments:
            compact_events.append({
                "start": round(s["start_time"], 1),
                "end": round(s["end_time"], 1),
                "summary": s.get("summary", "") or s.get("reason", ""),
                "scene": s.get("scene_desc", ""),
                "dialogue": s.get("dialogue", ""),
                "initial_score": s.get("score", 5.0)
            })

        step2_prompt = f"""你是一名顶级电影总剪辑师与总导演。
整部视频总时长为 {total_duration:.1f} 秒。第一阶段已提取出覆盖全片各个时段的事实事件清单如下：
{json.dumps(compact_events, ensure_ascii=False, indent=1)}

【剪辑风格与偏好】：
{style_instruction}
{custom_part}

【全局宏观评估与价值度打分任务】：
请站在整部视频全局宏观叙事与观赏节奏的战略高度，统揽全局，对整部视频重新进行二次宏观审视，并给出全片从 0.0 秒至 {total_duration:.1f} 秒权威且精准的【价值度评分曲线 (score: 1.0 - 10.0)】：
1. 识别全片真正具有高吸引力的高光时刻、关键对话、重要操作，与过场跑图、无聊等待、冗余废片；
2. 为每个连续时间点输出权威的【视频价值度评分 (score: 1.0 - 10.0)】：
   - 1.0 - 2.0 分：完全无意义死屏、长时间静止、废镜头（极低价值，建议删除）；
   - 3.0 - 5.0 分：常规跑图赶路、翻找材料、下载等待、普通铺垫（中低价值，建议快进）；
   - 6.0 - 8.0 分：有效推动剧情、生动人物互动、关键讲解、精彩战局（高价值，建议保留 1.0x）；
   - 9.0 - 10.0 分：全片最高潮、关键反转、爆笑瞬间、最强操作（极高价值，必须保留 1.0x）；
3. 输出严格的 JSON 数组，连续无缝覆盖全片 0.0 秒到 {total_duration:.1f} 秒。

【输出 JSON 字段要求】：
每个对象包含：
  - "start_time": 开始秒数 (float)
  - "end_time": 结束秒数 (float)
  - "score": 1.0 到 10.0 的评分 (float，如 3.5, 7.0, 9.5)
  - "action": 建议动作 ("keep" | "fast_forward" | "delete")
  - "summary": 场景精炼概述
  - "reason": 从全片全局视角的价值度判定理由
"""

        step2_payload = {
            "contents": [
                {
                    "parts": [{"text": step2_prompt}]
                }
            ],
            "generationConfig": {
                "response_mime_type": "application/json"
            }
        }

        for attempt in range(1, 4):
            try:
                if progress_callback and attempt > 1:
                    progress_callback(
                        80.0,
                        f"[第二步 全局宏观研读] 正在重试第 {attempt} 次全局通盘审视...",
                        all_segments,
                    )
                async with httpx.AsyncClient(timeout=120.0) as client:
                    resp2 = await client.post(endpoint, json=step2_payload)
                    if resp2.status_code != 200:
                        raise RuntimeError(f"Gemini 全局打分接口响应异常 (HTTP {resp2.status_code}): {resp2.text[:200]}")
                    res2_json = resp2.json()
                    if "candidates" not in res2_json or not res2_json["candidates"]:
                        raise RuntimeError("Gemini 全局打分返回候选集为空")
                    candidate2 = res2_json["candidates"][0]
                    if "content" not in candidate2 or "parts" not in candidate2["content"]:
                        raise RuntimeError("Gemini 全局打分返回结构异常")
                    raw_text2 = candidate2["content"]["parts"][0]["text"]
                    scored_segments = self._parse_json_segments(raw_text2)
                    if scored_segments and len(scored_segments) > 0:
                        final_segments = self._normalize_timeline(scored_segments, total_duration, default_ff_speed)
                        last_err_s2 = None
                        break
                    else:
                        raise RuntimeError("未能解析出有效的全局评分分段数据")
            except Exception as e2:
                last_err_s2 = e2
                print(f"第二阶段全局价值打分第 {attempt} 次异常: {e2}")
                if attempt < 3:
                    await asyncio.sleep(attempt * 1.5)

        if last_err_s2 is not None:
            checkpoint["status"] = "failed"
            checkpoint["failed_stage"] = "stage2"
            checkpoint["failed_chunk_index"] = None
            checkpoint["error_message"] = str(last_err_s2)
            self.save_checkpoint(checkpoint)
            raise RuntimeError(f"第二阶段全局宏观审视打分失败: {str(last_err_s2)}")

        if not final_segments:
            final_segments = self._normalize_timeline(all_segments, total_duration, default_ff_speed)

        # Stage 2 complete!
        checkpoint["status"] = "success"
        checkpoint["failed_stage"] = None
        checkpoint["failed_chunk_index"] = None
        checkpoint["error_message"] = None
        checkpoint["final_segments"] = final_segments
        self.save_checkpoint(checkpoint)

        if progress_callback:
            progress_callback(
                100.0,
                f"全局大模型宏观研读完成！已生成全片 1-10 分价值曲线，共 {len(final_segments)} 个剪辑分段",
                final_segments,
            )

        return final_segments

    def _normalize_chunk_timeline(
        self,
        raw_segments: List[Dict[str, Any]],
        st: float,
        et: float,
        default_ff_speed: float,
    ) -> List[Dict[str, Any]]:
        chunk_dur = et - st
        if not raw_segments:
            return [{
                "id": str(uuid.uuid4())[:8],
                "start_time": round(st, 2),
                "end_time": round(et, 2),
                "duration": round(chunk_dur, 2),
                "action": "keep",
                "speed": 1.0,
                "score": 5,
                "reason": "保底分段",
                "summary": ""
            }]

        converted = []
        for s in raw_segments:
            raw_s = float(s.get("start_time", 0))
            raw_e = float(s.get("end_time", chunk_dur))

            # Offset if Gemini returned relative timestamps
            if raw_s < st and raw_s < chunk_dur:
                raw_s += st
                raw_e += st

            # Clamp
            raw_s = max(st, min(et, raw_s))
            raw_e = max(raw_s, min(et, raw_e))

            if raw_e - raw_s >= 0.2:
                s_copy = dict(s)
                s_copy["id"] = str(uuid.uuid4())[:8]
                s_copy["start_time"] = round(raw_s, 2)
                s_copy["end_time"] = round(raw_e, 2)
                s_copy["duration"] = round(raw_e - raw_s, 2)
                converted.append(s_copy)

        if not converted:
            return [{
                "id": str(uuid.uuid4())[:8],
                "start_time": round(st, 2),
                "end_time": round(et, 2),
                "duration": round(chunk_dur, 2),
                "action": "keep",
                "speed": 1.0,
                "score": 5,
                "reason": "分段保留",
                "summary": ""
            }]

        # Ensure internal continuity from st to et
        converted.sort(key=lambda x: x["start_time"])
        continuous = []
        curr = st

        for c in converted:
            c_s = c["start_time"]
            c_e = c["end_time"]
            if c_s - curr > 0.4:
                # Gap: fill with fast_forward
                continuous.append({
                    "id": str(uuid.uuid4())[:8],
                    "start_time": round(curr, 2),
                    "end_time": round(c_s, 2),
                    "duration": round(c_s - curr, 2),
                    "action": "fast_forward",
                    "speed": default_ff_speed,
                    "score": 3,
                    "reason": "过渡快进",
                    "summary": ""
                })
            c["start_time"] = round(max(curr, c_s), 2)
            c["duration"] = round(c["end_time"] - c["start_time"], 2)
            continuous.append(c)
            curr = c["end_time"]

        if et - curr > 0.4:
            continuous.append({
                "id": str(uuid.uuid4())[:8],
                "start_time": round(curr, 2),
                "end_time": round(et, 2),
                "duration": round(et - curr, 2),
                "action": "keep",
                "speed": 1.0,
                "score": 5,
                "reason": "分段结尾",
                "summary": ""
            })

        return continuous

    def _parse_json_segments(self, text: str) -> List[Dict[str, Any]]:
        # Remove markdown code block fences if present
        clean_text = text.strip()
        if clean_text.startswith("```json"):
            clean_text = clean_text[7:]
        elif clean_text.startswith("```"):
            clean_text = clean_text[3:]
        if clean_text.endswith("```"):
            clean_text = clean_text[:-3]
        clean_text = clean_text.strip()

        # Try direct parse
        try:
            data = json.loads(clean_text)
            if isinstance(data, list):
                return data
            if isinstance(data, dict):
                for k in ["segments", "cuts", "results", "data"]:
                    if k in data and isinstance(data[k], list):
                        return data[k]
        except Exception:
            pass

        # Try regex search for array
        match = re.search(r"\[\s*\{.*\}\s*\]", clean_text, re.DOTALL)
        if match:
            try:
                data = json.loads(match.group(0))
                if isinstance(data, list):
                    return data
            except Exception:
                pass

        raise ValueError(f"未能从模型返回中提取有效分段 JSON:\n{text[:500]}")

    def _normalize_timeline(self, raw_segments: List[Dict[str, Any]], total_duration: float, default_ff_speed: float) -> List[Dict[str, Any]]:
        if not raw_segments:
            # Fallback single keep segment
            return [{
                "id": str(uuid.uuid4())[:8],
                "start_time": 0.0,
                "end_time": round(total_duration, 2),
                "duration": round(total_duration, 2),
                "action": "keep",
                "speed": 1.0,
                "score": 6,
                "reason": "全片默认保留",
                "summary": "完整视频片段"
            }]

        # Sort segments by start_time
        sorted_segs = sorted(raw_segments, key=lambda s: float(s.get("start_time", 0.0)))
        
        normalized = []
        current_time = 0.0

        for s in sorted_segs:
            st = max(0.0, float(s.get("start_time", current_time)))
            et = min(total_duration, float(s.get("end_time", st + 5.0)))
            
            # If there is a gap between current_time and st > 0.5s, fill it with fast-forward
            if st - current_time > 0.5:
                gap_dur = round(st - current_time, 2)
                normalized.append({
                    "id": str(uuid.uuid4())[:8],
                    "start_time": round(current_time, 2),
                    "end_time": round(st, 2),
                    "duration": gap_dur,
                    "action": "fast_forward",
                    "speed": default_ff_speed,
                    "score": 3,
                    "reason": "时间轴衔接段落（默认快进）",
                    "summary": "过渡场景"
                })
                current_time = st
            elif st < current_time:
                st = current_time

            if et <= st:
                continue

            dur = round(et - st, 2)
            act = s.get("action", "keep")
            if act not in ["keep", "fast_forward", "delete"]:
                act = "keep"

            speed = float(s.get("speed", 1.0 if act == "keep" else default_ff_speed))
            if act == "keep":
                speed = 1.0
            elif act == "delete":
                speed = 0.0

            try:
                sc = round(float(s.get("score", 5.0)), 1)
            except Exception:
                sc = 5.0

            normalized.append({
                "id": str(uuid.uuid4())[:8],
                "start_time": round(st, 2),
                "end_time": round(et, 2),
                "duration": dur,
                "action": act,
                "speed": speed,
                "score": sc,
                "reason": s.get("reason", "AI 判定段落"),
                "summary": s.get("summary", ""),
                "scene_desc": s.get("scene_desc", ""),
                "dialogue": s.get("dialogue", "")
            })
            current_time = et

        # Check if tail is missing
        if total_duration - current_time > 0.5:
            tail_dur = round(total_duration - current_time, 2)
            normalized.append({
                "id": str(uuid.uuid4())[:8],
                "start_time": round(current_time, 2),
                "end_time": round(total_duration, 2),
                "duration": tail_dur,
                "action": "keep",
                "speed": 1.0,
                "score": 5,
                "reason": "结尾保留段落",
                "summary": "视频结尾"
            })

        return normalized

gemini_service = GeminiService()
