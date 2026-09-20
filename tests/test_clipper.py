import os
import sys
import pytest
import asyncio
from pathlib import Path

# Add project root to sys.path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from backend.config import get_settings, update_settings, AVAILABLE_MODELS
from backend.services.ffmpeg_service import ffmpeg_service
from backend.services.gemini_service import gemini_service

def test_config():
    settings = get_settings()
    assert "gemini_base_url" in settings
    assert "gemini_api_key" in settings
    assert len(AVAILABLE_MODELS) >= 8

def test_ffmpeg_system():
    sys_info = ffmpeg_service.check_system()
    assert sys_info["ffmpeg_installed"] is True
    assert sys_info["ffprobe_installed"] is True
    assert "encoders" in sys_info
    print(f"FFmpeg Version: {sys_info['ffmpeg_version']}")
    print(f"Detected Encoders: {sys_info['encoders']}")
    print(f"Recommended Encoder: {sys_info['recommended_encoder']}")

def test_atempo_chain():
    # Test atempo chaining logic for high speeds
    c2 = ffmpeg_service._build_atempo_filter(2.0)
    assert c2 == "atempo=2.0"
    
    c4 = ffmpeg_service._build_atempo_filter(4.0)
    assert "atempo=2.0,atempo=2.0" in c4

    c8 = ffmpeg_service._build_atempo_filter(8.0)
    assert c8.count("atempo=2.0") == 3

def test_timeline_normalization():
    # Test gap filling and tail completion
    raw_segments = [
        {"start_time": 2.0, "end_time": 5.0, "action": "keep", "speed": 1.0, "reason": "Test 1"},
        {"start_time": 8.0, "end_time": 10.0, "action": "fast_forward", "speed": 4.0, "reason": "Test 2"},
    ]
    normalized = gemini_service._normalize_timeline(raw_segments, total_duration=12.0, default_ff_speed=4.0)
    
    # Should fill 0.0 - 2.0 gap
    assert normalized[0]["start_time"] == 0.0
    assert normalized[0]["end_time"] == 2.0
    assert normalized[0]["action"] == "fast_forward"
    
    # Segment 1: 2.0 - 5.0 keep
    assert normalized[1]["start_time"] == 2.0
    assert normalized[1]["end_time"] == 5.0
    assert normalized[1]["action"] == "keep"

    # Gap 5.0 - 8.0 fast_forward
    assert normalized[2]["start_time"] == 5.0
    assert normalized[2]["end_time"] == 8.0
    assert normalized[2]["action"] == "fast_forward"

    # Segment 3: 8.0 - 10.0 fast_forward
    assert normalized[3]["start_time"] == 8.0
    assert normalized[3]["end_time"] == 10.0

    # Tail: 10.0 - 12.0
    assert normalized[4]["start_time"] == 10.0
    assert normalized[4]["end_time"] == 12.0

if __name__ == "__main__":
    test_config()
    test_ffmpeg_system()
    test_atempo_chain()
    test_timeline_normalization()
    print("All unit tests passed successfully!")
