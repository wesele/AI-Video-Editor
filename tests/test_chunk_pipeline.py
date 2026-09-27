import os
import sys
import time
import json
import httpx
import asyncio
import subprocess
from pathlib import Path

# Add project root to sys.path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import threading
from backend.main import app
from backend.config import TEMP_DIR

def start_server():
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8999, log_level="warning")

async def test_chunk_pipeline():
    print("=== 测试分段流水线切片并发分析 ===")

    # Start server in thread
    t = threading.Thread(target=start_server, daemon=True)
    t.start()
    await asyncio.sleep(1.5)

    # Generate a 12-second test video with duration simulated
    sample_video = TEMP_DIR / "test_pipe_clip.mp4"
    cmd = [
        "ffmpeg", "-y",
        "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25:duration=10",
        "-f", "lavfi", "-i", "sine=frequency=600:duration=10",
        "-c:v", "libx264", "-c:a", "aac",
        str(sample_video)
    ]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print(f"[1] 生成测试视频: {sample_video}")

    base_url = "http://127.0.0.1:8999"
    async with httpx.AsyncClient(base_url=base_url, timeout=120.0) as client:
        # Upload
        print("[2] 上传视频...")
        with open(sample_video, "rb") as f:
            files = {"file": ("test_pipe_clip.mp4", f, "video/mp4")}
            res = await client.post("/api/video/upload", files=files)
            assert res.status_code == 200
            data = res.json()
            video_id = data["video_id"]
            print(f"上传成功, video_id={video_id}")

        # Trigger analyze
        print("[3] 触发分段流水线 AI 分析...")
        res_a = await client.post("/api/video/analyze", json={
            "video_id": video_id,
            "model": "gemini-3.8-flash-high",
            "style_preset": "general",
            "custom_prompt": "前5秒保留，后5秒快进",
            "default_fast_forward_speed": 4.0
        })
        assert res_a.status_code == 200
        job_id = res_a.json()["job_id"]
        print(f"分析任务启动: job_id={job_id}")

        # Poll
        for _ in range(30):
            await asyncio.sleep(1)
            st = (await client.get(f"/api/video/analyze/status/{job_id}")).json()
            pct = st.get("progress")
            msg = st.get("progress_message")
            segs = st.get("segments") or []
            print(f"  [进度] {pct}% | 片段数: {len(segs)} | 状态: {msg}")
            if st["status"] == "success":
                print(f"[SUCCESS] 分析成功完成！最终提取到 {len(segs)} 个连续分段:")
                for s in segs:
                    print(f"    {s['start_time']}s ~ {s['end_time']}s: [{s['action']} {s['speed']}x] {s['reason']}")
                break
            elif st["status"] == "failed":
                raise RuntimeError(f"分析失败: {st.get('error')}")

    if sample_video.exists():
        sample_video.unlink()

if __name__ == "__main__":
    asyncio.run(test_chunk_pipeline())
