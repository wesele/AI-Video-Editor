import os
import sys
import asyncio
import subprocess
from pathlib import Path

# Add project root to sys.path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from backend.services.ffmpeg_service import ffmpeg_service
from backend.config import TEMP_DIR, EXPORTS_DIR

async def run_e2e_test():
    print("=== 开始端到端 FFmpeg 剪辑与 QSV 硬件加速合成测试 ===")
    
    test_input = TEMP_DIR / "test_input_6s.mp4"
    test_output = EXPORTS_DIR / "test_output_e2e.mp4"

    # Step 1: Generate a 6s test video with audio
    print("[1] 生成 6秒带音频合成测试源视频...")
    cmd_gen = [
        "ffmpeg", "-y",
        "-f", "lavfi", "-i", "testsrc=size=640x360:rate=25:duration=6",
        "-f", "lavfi", "-i", "sine=frequency=440:duration=6",
        "-c:v", "libx264", "-c:a", "aac",
        str(test_input)
    ]
    subprocess.run(cmd_gen, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    assert test_input.exists(), "测试视频生成失败"

    # Step 2: Probe metadata
    print("[2] 测试视频元数据探测...")
    meta = ffmpeg_service.probe_video(str(test_input))
    print(f"源片信息: 时长={meta['duration']}s, 分辨率={meta['width']}x{meta['height']}, fps={meta['fps']}, 有音频={meta['has_audio']}")
    assert abs(meta["duration"] - 6.0) < 0.5
    assert meta["has_audio"] is True

    # Step 3: Define 3-segment cut plan (0-2s keep, 2-4s 4x speed, 4-6s keep)
    segments = [
        {"start_time": 0.0, "end_time": 2.0, "duration": 2.0, "action": "keep", "speed": 1.0},
        {"start_time": 2.0, "end_time": 4.0, "duration": 2.0, "action": "fast_forward", "speed": 4.0},
        {"start_time": 4.0, "end_time": 6.0, "duration": 2.0, "action": "keep", "speed": 1.0},
    ]

    # Step 4: Export with Intel QSV hardware acceleration
    print("[3] 执行异步导出与 QSV 硬件加速转码...")
    progress_records = []
    def on_progress(pct, msg):
        progress_records.append(pct)
        print(f"  [渲染进度] {pct}% - {msg}")

    await ffmpeg_service.export_video_async(
        input_path=str(test_input),
        output_path=str(test_output),
        segments=segments,
        resolution="original",
        format_type="mp4",
        bitrate_mode="auto",
        encoder="qsv",
        audio_mode="auto_mute",
        progress_callback=on_progress,
    )

    # Step 5: Verify output file
    print("[4] 校验输出文件...")
    assert test_output.exists(), "输出文件未生成"
    out_meta = ffmpeg_service.probe_video(str(test_output))
    print(f"成片信息: 时长={out_meta['duration']}s, 分辨率={out_meta['width']}x{out_meta['height']}, 编码器={out_meta['codec_name']}")
    
    # Expected duration: 2s + (2s/4) + 2s = 4.5s
    assert abs(out_meta["duration"] - 4.5) < 0.5, f"成片时长不符合预期: {out_meta['duration']}"
    assert len(progress_records) > 0, "进度回调未被触发"

    # Cleanup
    if test_input.exists():
        test_input.unlink()
    if test_output.exists():
        test_output.unlink()

    print("\n[SUCCESS] 端到端 FFmpeg 剪辑与 QSV 硬件加速转码全部校验通过！")

if __name__ == "__main__":
    asyncio.run(run_e2e_test())
