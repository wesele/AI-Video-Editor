import os
import sys
import time
import json
import httpx
import asyncio
import subprocess
import threading
from pathlib import Path

# Add project root to sys.path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from backend.main import app
from backend.config import TEMP_DIR

def start_server():
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8998, log_level="warning")

async def test_full_workflow():
    print("=== 开始完整 Web API 工作流联调测试 ===")

    # 1. Start server in thread
    t = threading.Thread(target=start_server, daemon=True)
    t.start()
    time.sleep(2)

    base_url = "http://127.0.0.1:8998"
    
    # 2. Generate a 4-second video
    sample_video = TEMP_DIR / "api_test_clip.mp4"
    cmd = [
        "ffmpeg", "-y",
        "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25:duration=4",
        "-f", "lavfi", "-i", "sine=frequency=500:duration=4",
        "-c:v", "libx264", "-c:a", "aac",
        str(sample_video)
    ]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print(f"[1] 生成测试样本视频: {sample_video}")

    async with httpx.AsyncClient(base_url=base_url, timeout=120.0) as client:
        # 3. Test Upload
        print("[2] 测试 /api/video/upload ...")
        with open(sample_video, "rb") as f:
            files = {"file": ("api_test_clip.mp4", f, "video/mp4")}
            res = await client.post("/api/video/upload", files=files)
            assert res.status_code == 200, f"Upload failed: {res.text}"
            upload_data = res.json()
            video_id = upload_data["video_id"]
            print(f"上传成功! video_id={video_id}, 时长={upload_data['duration']}s")

        # 4. Test Gemini Analysis
        print("[3] 测试 /api/video/analyze (调用远程 Gemini 3.8 Flash 模型)...")
        res_analyze = await client.post("/api/video/analyze", json={
            "video_id": video_id,
            "model": "gemini-3.8-flash-high",
            "style_preset": "general",
            "custom_prompt": "前2秒作为保留，后2秒作为快进",
            "default_fast_forward_speed": 4.0
        })
        assert res_analyze.status_code == 200, f"Analyze trigger failed: {res_analyze.text}"
        job_id = res_analyze.json()["job_id"]
        print(f"分析任务已触发: job_id={job_id}，正在轮询等待 Gemini 思考...")

        segments = None
        for attempt in range(40):
            await asyncio.sleep(2)
            check_res = await client.get(f"/api/video/analyze/status/{job_id}")
            st = check_res.json()
            if st["status"] == "success":
                segments = st["segments"]
                print(f"Gemini 分析成功! 得到 {len(segments)} 个分段:")
                for s in segments:
                    print(f"  - [{s['start_time']}s - {s['end_time']}s] 动作:{s['action']} 倍速:{s.get('speed')} 理由:{s.get('reason')}")
                break
            elif st["status"] == "failed":
                raise RuntimeError(f"Gemini 分析失败: {st.get('error')}")
            else:
                print(f"  轮询中 ({attempt+1}/40)... {st.get('progress_message')}")

        assert segments is not None, "未在超时时间内获取到分析结果"

        # 5. Test Export
        print("[4] 测试 /api/video/export (合成渲染)...")
        export_res = await client.post("/api/video/export", json={
            "video_id": video_id,
            "segments": segments,
            "resolution": "original",
            "format": "mp4",
            "bitrate_mode": "auto",
            "encoder": "qsv",
            "audio_mode": "auto_mute"
        })
        assert export_res.status_code == 200, f"Export trigger failed: {export_res.text}"
        task_id = export_res.json()["task_id"]

        for _ in range(30):
            await asyncio.sleep(1)
            check_export = await client.get(f"/api/video/export/status/{task_id}")
            ex_st = check_export.json()
            if ex_st["status"] == "success":
                print(f"合成导出成功! 输出URL: {ex_st['output_url']}")
                break
            elif ex_st["status"] == "failed":
                raise RuntimeError(f"导出失败: {ex_st.get('error')}")

    # Cleanup
    if sample_video.exists():
        sample_video.unlink()

    print("\n[SUCCESS] 完整端到端 Web API 流程测试圆满通过！")

if __name__ == "__main__":
    asyncio.run(test_full_workflow())
