import os
import asyncio
import pytest
from unittest.mock import patch, MagicMock, AsyncMock
from pydantic import ValidationError
from backend.models import AnalyzeRequest, SettingsPayload
from backend.config import get_settings, update_settings
from backend.services.gemini_service import GeminiService

def test_models_concurrency_validation():
    # Valid ranges 1 to 8
    for c in range(1, 9):
        req = AnalyzeRequest(video_id="vid_123", concurrency=c)
        assert req.concurrency == c

    # Invalid range < 1
    with pytest.raises(ValidationError):
        AnalyzeRequest(video_id="vid_123", concurrency=0)

    # Invalid range > 8
    with pytest.raises(ValidationError):
        AnalyzeRequest(video_id="vid_123", concurrency=9)

def test_settings_payload_concurrency():
    payload = SettingsPayload(
        gemini_base_url="http://192.168.31.233:8317",
        gemini_api_key="sk-test",
        default_model="gemini-3.8-flash-high",
        default_concurrency=6
    )
    assert payload.default_concurrency == 6

    # Test settings update & get
    settings_before = get_settings()
    orig_concurrency = settings_before.get("default_concurrency", 4)

    try:
        updated = update_settings({"default_concurrency": 6})
        assert updated.get("default_concurrency") == 6
        assert get_settings().get("default_concurrency") == 6
    finally:
        update_settings({"default_concurrency": orig_concurrency})
        assert get_settings().get("default_concurrency") == orig_concurrency

@pytest.mark.asyncio
async def test_concurrent_stage1_execution():
    service = GeminiService()
    active_calls = 0
    max_active_calls = 0

    async def mock_post(url, json=None):
        nonlocal active_calls, max_active_calls
        active_calls += 1
        max_active_calls = max(max_active_calls, active_calls)
        await asyncio.sleep(0.08)
        active_calls -= 1

        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = {
            "candidates": [
                {
                    "content": {
                        "parts": [
                            {
                                "text": '[{"start_time": 0.0, "end_time": 180.0, "score": 8.0, "action": "keep", "summary": "test", "scene_desc": "desc", "dialogue": "dia"}]'
                            }
                        ]
                    }
                }
            ]
        }
        return mock_resp

    created_files = []

    async def mock_slice(video_path, st, et, idx):
        file_path = f"dummy_chunk_{idx}.mp4"
        with open(file_path, "wb") as f:
            f.write(b"dummy")
        created_files.append(file_path)
        return file_path

    with patch("backend.services.gemini_service.ffmpeg_service.probe_video", return_value={"duration": 900.0}), \
         patch("backend.services.gemini_service.ffmpeg_service.slice_chunk_proxy_async", side_effect=mock_slice), \
         patch("httpx.AsyncClient.post", side_effect=mock_post):

        try:
            # 900s video = 5 chunks (180s each)
            # concurrency = 3
            segments = await service.analyze_video(
                video_path="dummy.mp4",
                video_id="test_conc_vid",
                concurrency=3,
                resume=False
            )
            # Stage 1 peak concurrent calls should not exceed concurrency limit (3)
            assert max_active_calls <= 3
            # Concurrency actually took place
            assert max_active_calls >= 2
            assert len(segments) > 0
        finally:
            for f in created_files:
                if os.path.exists(f):
                    try:
                        os.remove(f)
                    except Exception:
                        pass
            service.clear_checkpoint()
