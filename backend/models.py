from typing import List, Optional, Literal
from pydantic import BaseModel, Field

class Segment(BaseModel):
    id: str
    start_time: float = Field(..., description="Start time in seconds")
    end_time: float = Field(..., description="End time in seconds")
    duration: float = Field(..., description="Duration in seconds")
    action: Literal["keep", "fast_forward", "delete"] = Field("keep", description="Action to take")
    speed: float = Field(1.0, description="Playback speed multiplier (e.g. 1.0 for keep, 4.0 for fast_forward, 0 for delete)")
    score: Optional[float] = Field(5.0, description="Value score 1.0-10.0")
    reason: str = Field("", description="AI rationale for this decision")
    summary: Optional[str] = Field("", description="Short summary of content in this segment")
    scene_desc: Optional[str] = Field("", description="Visual/scene facts")
    dialogue: Optional[str] = Field("", description="Spoken dialogue or audio facts")

class VideoMeta(BaseModel):
    video_id: str
    filename: str
    filepath: str
    filesize_bytes: int
    filesize_formatted: str
    duration: float
    width: int
    height: int
    fps: float
    bitrate: int

class AnalyzeRequest(BaseModel):
    video_id: str
    model: str = "gemini-3.8-flash-high"
    style_preset: str = "general"  # general, vlog, gaming, tutorial, meeting
    custom_prompt: Optional[str] = ""
    default_fast_forward_speed: float = 4.0
    resume: bool = False

class ExportRequest(BaseModel):
    video_id: str
    segments: List[Segment]
    resolution: str = "original"  # original, 1080p, 720p, 4k
    format: str = "mp4"           # mp4, mkv
    bitrate_mode: str = "auto"    # auto, custom
    custom_bitrate_mbps: Optional[float] = 8.0
    encoder: str = "auto"         # auto, qsv, nvenc, amf, cpu
    audio_mode: str = "auto_mute" # atempo, auto_mute (mute if speed >= 4x), mute_all_ff

class SettingsPayload(BaseModel):
    gemini_base_url: str
    gemini_api_key: str
    default_model: str
    custom_ffmpeg_path: Optional[str] = ""
