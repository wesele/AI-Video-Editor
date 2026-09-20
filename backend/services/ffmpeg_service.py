import os
import sys
import json
import shutil
import asyncio
import subprocess
from pathlib import Path
from typing import Dict, Any, List, Optional, Callable
from backend.config import get_settings, TEMP_DIR

class FFmpegService:
    def __init__(self):
        self._cached_ffmpeg = None
        self._cached_ffprobe = None
        self._cached_encoders = None

    def get_ffmpeg_path(self) -> str:
        settings = get_settings()
        custom = settings.get("custom_ffmpeg_path", "").strip()
        if custom and os.path.exists(custom):
            return custom
        
        # Check in PATH
        which = shutil.which("ffmpeg")
        if which:
            return which
            
        # Check local bin
        local_bin = Path(__file__).resolve().parent.parent.parent / "bin" / "ffmpeg.exe"
        if local_bin.exists():
            return str(local_bin)
            
        return "ffmpeg"

    def get_ffprobe_path(self) -> str:
        settings = get_settings()
        custom = settings.get("custom_ffmpeg_path", "").strip()
        if custom and os.path.exists(custom):
            # If custom points to ffmpeg.exe, try finding ffprobe in same dir
            p = Path(custom).parent / "ffprobe.exe"
            if p.exists():
                return str(p)
                
        which = shutil.which("ffprobe")
        if which:
            return which
            
        local_bin = Path(__file__).resolve().parent.parent.parent / "bin" / "ffprobe.exe"
        if local_bin.exists():
            return str(local_bin)
            
        return "ffprobe"

    def check_system(self) -> Dict[str, Any]:
        ffmpeg_bin = self.get_ffmpeg_path()
        ffprobe_bin = self.get_ffprobe_path()
        
        ffmpeg_ok = False
        ffmpeg_version = "未知"
        try:
            res = subprocess.run([ffmpeg_bin, "-version"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=5)
            if res.returncode == 0:
                ffmpeg_ok = True
                first_line = res.stdout.splitlines()[0] if res.stdout else ""
                ffmpeg_version = first_line.replace("ffmpeg version ", "").split(" ")[0]
        except Exception:
            ffmpeg_ok = False

        encoders = self.detect_hardware_encoders()
        
        recommended_encoder = "cpu"
        if encoders.get("qsv"):
            recommended_encoder = "qsv"
        elif encoders.get("nvenc"):
            recommended_encoder = "nvenc"
        elif encoders.get("amf"):
            recommended_encoder = "amf"

        return {
            "ffmpeg_installed": ffmpeg_ok,
            "ffmpeg_path": ffmpeg_bin,
            "ffmpeg_version": ffmpeg_version,
            "ffprobe_installed": shutil.which(ffprobe_bin) is not None or os.path.exists(ffprobe_bin),
            "encoders": encoders,
            "recommended_encoder": recommended_encoder,
        }

    def detect_hardware_encoders(self) -> Dict[str, bool]:
        if self._cached_encoders is not None:
            return self._cached_encoders
            
        ffmpeg_bin = self.get_ffmpeg_path()
        detected = {
            "qsv": False,
            "nvenc": False,
            "amf": False,
            "cpu": True,
        }
        
        # Test QSV
        try:
            qsv_test = subprocess.run(
                [ffmpeg_bin, "-f", "lavfi", "-i", "color=c=black:s=64x64:d=0.1", "-c:v", "h264_qsv", "-f", "null", "-"],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=4
            )
            if qsv_test.returncode == 0:
                detected["qsv"] = True
        except Exception:
            pass

        # Test NVENC
        try:
            nv_test = subprocess.run(
                [ffmpeg_bin, "-f", "lavfi", "-i", "color=c=black:s=64x64:d=0.1", "-c:v", "h264_nvenc", "-f", "null", "-"],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=4
            )
            if nv_test.returncode == 0:
                detected["nvenc"] = True
        except Exception:
            pass

        # Test AMF
        try:
            amf_test = subprocess.run(
                [ffmpeg_bin, "-f", "lavfi", "-i", "color=c=black:s=64x64:d=0.1", "-c:v", "h264_amf", "-f", "null", "-"],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=4
            )
            if amf_test.returncode == 0:
                detected["amf"] = True
        except Exception:
            pass

        self._cached_encoders = detected
        return detected

    def probe_video(self, file_path: str) -> Dict[str, Any]:
        ffprobe_bin = self.get_ffprobe_path()
        cmd = [
            ffprobe_bin,
            "-v", "quiet",
            "-print_format", "json",
            "-show_format",
            "-show_streams",
            file_path
        ]
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=15)
        if res.returncode != 0:
            raise RuntimeError(f"FFprobe 探测视频失败: {res.stderr}")
            
        data = json.loads(res.stdout)
        fmt = data.get("format", {})
        streams = data.get("streams", [])
        
        video_stream = None
        has_audio = False
        for s in streams:
            if s.get("codec_type") == "video" and not video_stream:
                video_stream = s
            elif s.get("codec_type") == "audio":
                has_audio = True

        if not video_stream:
            raise ValueError("文件中未检测到有效视频轨")

        duration = float(fmt.get("duration", video_stream.get("duration", 0)))
        width = int(video_stream.get("width", 0))
        height = int(video_stream.get("height", 0))
        bitrate = int(fmt.get("bit_rate", video_stream.get("bit_rate", 0)))
        
        # Calculate fps
        r_frame_rate = video_stream.get("r_frame_rate", "30/1")
        try:
            num, den = r_frame_rate.split("/")
            fps = round(float(num) / float(den), 2)
        except Exception:
            fps = 30.0

        return {
            "duration": duration,
            "width": width,
            "height": height,
            "fps": fps,
            "bitrate": bitrate,
            "has_audio": has_audio,
            "codec_name": video_stream.get("codec_name", ""),
        }

    async def create_ai_proxy_async(
        self,
        input_file: str,
        total_duration: float,
        max_size_mb: float = 15.0,
        progress_callback: Optional[Callable[[float, str], None]] = None,
    ) -> str:
        """
        Dynamically downsample video into a fast, ultra-compact proxy (<= 15MB) for Gemini AI analysis.
        Runs asynchronously and reports real-time progress.
        """
        file_size_mb = os.path.getsize(input_file) / (1024 * 1024)
        if file_size_mb <= max_size_mb:
            if progress_callback:
                progress_callback(100.0, "原片体积较小，无需二次压制，直接提交分析")
            return input_file

        ffmpeg_bin = self.get_ffmpeg_path()
        proxy_path = str(TEMP_DIR / f"proxy_{Path(input_file).stem}.mp4")

        # Dynamic compression parameters based on duration to guarantee <= 15MB
        if total_duration <= 180:  # <= 3 mins
            fps = 8
            scale = "scale=w=640:h=360:force_original_aspect_ratio=decrease"
            crf = "30"
            audio_args = ["-c:a", "aac", "-b:a", "32k", "-ac", "2", "-ar", "22050"]
        elif total_duration <= 900:  # 3 to 15 mins
            fps = 2
            scale = "scale=w=480:h=270:force_original_aspect_ratio=decrease"
            crf = "33"
            audio_args = ["-c:a", "aac", "-b:a", "24k", "-ac", "1", "-ar", "16000"]
        elif total_duration <= 3600:  # 15 to 60 mins (e.g. 51m video)
            fps = 0.5  # 1 frame every 2 seconds
            scale = "scale=w=320:h=180:force_original_aspect_ratio=decrease"
            crf = "36"
            audio_args = ["-c:a", "aac", "-b:a", "16k", "-ac", "1", "-ar", "16000"]
        else:  # > 1 hour
            fps = 0.25  # 1 frame every 4 seconds
            scale = "scale=w=320:h=180:force_original_aspect_ratio=decrease"
            crf = "38"
            audio_args = ["-c:a", "aac", "-b:a", "12k", "-ac", "1", "-ar", "16000"]

        cmd = [
            ffmpeg_bin,
            "-y",
            "-i", input_file,
            "-vf", f"{scale},fps={fps}",
            "-c:v", "libx264",
            "-preset", "ultrafast",
            "-crf", crf,
            *audio_args,
            "-progress", "pipe:1",
            proxy_path
        ]

        if progress_callback:
            progress_callback(0.0, f"开始极速压制轻量代理视频 (总长 {round(total_duration)}s)...")

        process = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )

        while True:
            line = await process.stdout.readline()
            if not line:
                break
            line_str = line.decode("utf-8", errors="ignore").strip()
            if line_str.startswith("out_time_us="):
                try:
                    time_us = int(line_str.split("=")[1])
                    cur_sec = time_us / 1000000.0
                    if total_duration > 0:
                        pct = min(99.0, max(0.0, (cur_sec / total_duration) * 100.0))
                        if progress_callback:
                            progress_callback(
                                round(pct, 1),
                                f"极速压制分析代理 ({round(pct, 1)}% - {round(cur_sec)}s/{round(total_duration)}s)"
                            )
                except Exception:
                    pass

        await process.wait()
        if process.returncode != 0 or not os.path.exists(proxy_path):
            stderr_bytes = await process.stderr.read()
            stderr_str = stderr_bytes.decode("utf-8", errors="ignore")
            raise RuntimeError(f"生成 AI 分析代理视频失败: {stderr_str}")

        if progress_callback:
            proxy_size_mb = os.path.getsize(proxy_path) / (1024 * 1024)
            progress_callback(100.0, f"轻量代理压制完成 ({round(proxy_size_mb, 1)} MB)")

        return proxy_path

    async def slice_chunk_proxy_async(
        self,
        input_file: str,
        start_time: float,
        end_time: float,
        chunk_index: int,
    ) -> str:
        """
        Fast slice & downsample a specific time chunk [start_time, end_time] into a lightweight proxy MP4 (~2-4 MB).
        """
        ffmpeg_bin = self.get_ffmpeg_path()
        chunk_path = str(TEMP_DIR / f"chunk_{chunk_index}_{Path(input_file).stem}.mp4")

        # Fast seeking with -ss before -i, and -to for exact slice
        cmd = [
            ffmpeg_bin,
            "-y",
            "-ss", str(round(start_time, 2)),
            "-to", str(round(end_time, 2)),
            "-i", input_file,
            "-vf", "scale=w=480:h=270:force_original_aspect_ratio=decrease,fps=2",
            "-c:v", "libx264",
            "-preset", "ultrafast",
            "-crf", "32",
            "-c:a", "aac",
            "-b:a", "24k",
            "-ac", "1",
            "-ar", "16000",
            chunk_path
        ]

        process = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        await process.wait()

        if process.returncode != 0 or not os.path.exists(chunk_path):
            stderr_bytes = await process.stderr.read()
            stderr_str = stderr_bytes.decode("utf-8", errors="ignore")
            raise RuntimeError(f"切片第 {chunk_index+1} 分段失败: {stderr_str}")

        return chunk_path

    async def export_video_async(
        self,
        input_path: str,
        output_path: str,
        segments: List[Dict[str, Any]],
        resolution: str = "original",
        format_type: str = "mp4",
        bitrate_mode: str = "auto",
        custom_bitrate_mbps: Optional[float] = 8.0,
        encoder: str = "auto",
        audio_mode: str = "auto_mute",
        progress_callback: Optional[Callable[[float, str], None]] = None,
    ):
        """
        Execute FFmpeg complex filter graph export with real-time progress.
        """
        ffmpeg_bin = self.get_ffmpeg_path()
        meta = self.probe_video(input_path)
        has_audio = meta.get("has_audio", False)
        
        # Filter out deleted segments
        active_segments = [s for s in segments if s.get("action") != "delete"]
        if not active_segments:
            raise ValueError("剪辑方案中没有保留任何视频片段，无法导出")

        # Calculate estimated total output duration
        total_out_duration = 0.0
        for s in active_segments:
            dur = s["end_time"] - s["start_time"]
            speed = float(s.get("speed", 1.0))
            if speed <= 0:
                speed = 1.0
            total_out_duration += (dur / speed)

        # Build Filter Graph
        filter_complex_parts = []
        video_outputs = []
        audio_outputs = []
        
        # Determine scale filter if resolution is specified
        scale_filter = ""
        if resolution == "1080p":
            scale_filter = ",scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2"
        elif resolution == "720p":
            scale_filter = ",scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2"
        elif resolution == "4k":
            scale_filter = ",scale=3840:2160:force_original_aspect_ratio=decrease,pad=3840:2160:(ow-iw)/2:(oh-ih)/2"

        for i, s in enumerate(active_segments):
            start = s["start_time"]
            end = s["end_time"]
            speed = float(s.get("speed", 1.0))
            if speed <= 0:
                speed = 1.0
                
            # Video trim & speed
            # setpts = (1/speed) * (PTS - STARTPTS)
            v_label = f"v{i}"
            v_filter = f"[0:v]trim=start={start}:end={end},setpts=PTS-STARTPTS"
            if speed != 1.0:
                v_filter += f",setpts=(1/{speed})*PTS"
            if scale_filter:
                v_filter += scale_filter
            v_filter += f"[{v_label}]"
            filter_complex_parts.append(v_filter)
            video_outputs.append(f"[{v_label}]")
            
            # Audio trim & speed
            if has_audio:
                a_label = f"a{i}"
                a_filter = f"[0:a]atrim=start={start}:end={end},asetpts=PTS-STARTPTS"
                
                # Check mute rules
                should_mute = False
                if audio_mode == "mute_all_ff" and speed > 1.0:
                    should_mute = True
                elif audio_mode == "auto_mute" and speed >= 4.0:
                    should_mute = True
                    
                if speed != 1.0:
                    # atempo filter only supports 0.5 to 2.0. Chain atempo filters for > 2.0
                    atempo_chain = self._build_atempo_filter(speed)
                    a_filter += f",{atempo_chain}"
                if should_mute:
                    a_filter += f",volume=0.0"
                    
                a_filter += f"[{a_label}]"
                filter_complex_parts.append(a_filter)
                audio_outputs.append(f"[{a_label}]")

        # Concat filter
        concat_count = len(active_segments)
        if has_audio:
            concat_inputs = "".join(f"{v}{a}" for v, a in zip(video_outputs, audio_outputs))
            filter_complex_parts.append(f"{concat_inputs}concat=n={concat_count}:v=1:a=1[outv][outa]")
        else:
            concat_inputs = "".join(video_outputs)
            filter_complex_parts.append(f"{concat_inputs}concat=n={concat_count}:v=1:a=0[outv]")

        full_filter_complex = ";".join(filter_complex_parts)

        # Select encoder
        system_info = self.check_system()
        available_enc = system_info.get("encoders", {})
        
        video_codec = "libx264"
        codec_args = ["-preset", "medium", "-crf", "20"]
        
        target_encoder = encoder
        if target_encoder == "auto":
            target_encoder = system_info.get("recommended_encoder", "cpu")
            
        if target_encoder == "qsv" and available_enc.get("qsv"):
            video_codec = "h264_qsv"
            codec_args = ["-global_quality", "23", "-look_ahead", "0"]
        elif target_encoder == "nvenc" and available_enc.get("nvenc"):
            video_codec = "h264_nvenc"
            codec_args = ["-preset", "p4", "-cq", "22"]
        elif target_encoder == "amf" and available_enc.get("amf"):
            video_codec = "h264_amf"
            codec_args = ["-quality", "speed"]
        else:
            video_codec = "libx264"
            codec_args = ["-preset", "medium", "-crf", "21"]

        # Bitrate setting
        if bitrate_mode == "custom" and custom_bitrate_mbps and custom_bitrate_mbps > 0:
            b_val = f"{int(custom_bitrate_mbps * 1000)}k"
            codec_args.extend(["-b:v", b_val, "-maxrate", f"{int(custom_bitrate_mbps * 1500)}k", "-bufsize", f"{int(custom_bitrate_mbps * 2000)}k"])

        cmd = [
            ffmpeg_bin,
            "-y",
            "-i", input_path,
            "-filter_complex", full_filter_complex,
            "-map", "[outv]",
        ]
        if has_audio:
            cmd.extend(["-map", "[outa]", "-c:a", "aac", "-b:a", "192k"])

        cmd.extend(["-c:v", video_codec])
        cmd.extend(codec_args)
        cmd.extend(["-progress", "pipe:1", output_path])

        # Execute async subprocess and parse real-time progress
        process = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )

        last_percent = 0.0
        while True:
            line = await process.stdout.readline()
            if not line:
                break
            line_str = line.decode("utf-8", errors="ignore").strip()
            if line_str.startswith("out_time_us="):
                try:
                    time_us = int(line_str.split("=")[1])
                    current_time_sec = time_us / 1000000.0
                    if total_out_duration > 0:
                        pct = min(99.0, max(0.0, (current_time_sec / total_out_duration) * 100.0))
                        last_percent = pct
                        if progress_callback:
                            progress_callback(round(pct, 1), f"渲染中: {round(pct, 1)}% ({round(current_time_sec, 1)}s / {round(total_out_duration, 1)}s)")
                except Exception:
                    pass
            elif line_str.startswith("progress=end"):
                if progress_callback:
                    progress_callback(100.0, "合成渲染完成！")

        await process.wait()
        if process.returncode != 0:
            stderr_bytes = await process.stderr.read()
            stderr_str = stderr_bytes.decode("utf-8", errors="ignore")
            raise RuntimeError(f"FFmpeg 导出失败 (code {process.returncode}): {stderr_str}")

        if progress_callback:
            progress_callback(100.0, "导出成功！")

    def _build_atempo_filter(self, speed: float) -> str:
        """
        FFmpeg atempo filter accepts 0.5 <= speed <= 2.0.
        For speeds > 2.0 or < 0.5, multiple atempo filters must be chained.
        """
        filters = []
        rem = speed
        while rem > 2.0:
            filters.append("atempo=2.0")
            rem /= 2.0
        while rem < 0.5:
            filters.append("atempo=0.5")
            rem /= 0.5
        filters.append(f"atempo={round(rem, 3)}")
        return ",".join(filters)

ffmpeg_service = FFmpegService()
