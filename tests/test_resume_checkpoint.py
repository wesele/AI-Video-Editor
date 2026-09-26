import os
import sys
import json
import asyncio
from pathlib import Path

# Add project root to sys.path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from backend.config import CHECKPOINT_FILE, CHECKPOINTS_DIR
from backend.services.gemini_service import gemini_service

async def test_checkpoint_logic():
    print("=== 开始断点持久化与续跑逻辑测试 ===")

    # 1. Clear existing checkpoint
    gemini_service.clear_checkpoint()
    assert not CHECKPOINT_FILE.exists(), "清理后断点文件不应存在"
    print("[1] 清理历史断点成功")

    # 2. Check get_checkpoint returns None when no checkpoint
    ckpt = gemini_service.get_checkpoint("test_vid_1")
    assert ckpt is None, "空状态下 get_checkpoint 应返回 None"
    print("[2] 空状态检测通过")

    # 3. Simulate saving a checkpoint for video 1 with 1 completed chunk
    fake_chunk_0 = {
        "chunk_index": 0,
        "st": 0.0,
        "et": 180.0,
        "segments": [
            {
                "id": "seg-1",
                "start_time": 0.0,
                "end_time": 180.0,
                "duration": 180.0,
                "action": "keep",
                "speed": 1.0,
                "score": 7.5,
                "reason": "测试片段0",
                "summary": "第0段测试事实"
            }
        ]
    }

    test_data = {
        "video_id": "test_vid_1",
        "video_path": "C:/fake/path.mp4",
        "total_duration": 360.0,
        "chunk_len": 180.0,
        "total_chunks": 2,
        "status": "failed",
        "failed_stage": "stage1",
        "failed_chunk_index": 1,
        "error_message": "模拟网络连接超时",
        "config": {
            "model": "gemini-3.8-flash-high",
            "style_preset": "general",
            "custom_prompt": "测试提示词",
            "default_fast_forward_speed": 4.0,
        },
        "stage1_completed_chunks": [fake_chunk_0],
        "final_segments": None,
    }

    gemini_service.save_checkpoint(test_data)
    assert CHECKPOINT_FILE.exists(), "保存后断点文件必须存在于磁盘"
    print("[3] 磁盘断点持久化成功")

    # 4. Read back and verify
    loaded = gemini_service.get_checkpoint("test_vid_1")
    assert loaded is not None, "读取断点不应为空"
    assert loaded["video_id"] == "test_vid_1"
    assert loaded["status"] == "failed"
    assert loaded["failed_chunk_index"] == 1
    assert len(loaded["stage1_completed_chunks"]) == 1
    assert loaded["stage1_completed_chunks"][0]["chunk_index"] == 0
    print("[4] 断点读取与数据完整性校验通过")

    # 5. Check video_id mismatch isolation
    mismatch = gemini_service.get_checkpoint("different_video_99")
    assert mismatch is None, "查询不同 video_id 时应返回 None"
    print("[5] 多视频隔离校验通过")

    # 6. Verify single active checkpoint overwriting behavior
    test_data_2 = {
        "video_id": "test_vid_2",
        "video_path": "C:/fake/path2.mp4",
        "total_duration": 60.0,
        "chunk_len": 180.0,
        "total_chunks": 1,
        "status": "processing",
        "failed_stage": None,
        "failed_chunk_index": None,
        "error_message": None,
        "config": {
            "model": "gemini-3.8-flash-high",
            "style_preset": "vlog",
            "custom_prompt": "",
            "default_fast_forward_speed": 4.0,
        },
        "stage1_completed_chunks": [],
        "final_segments": None,
    }
    gemini_service.save_checkpoint(test_data_2)
    loaded_new = gemini_service.get_checkpoint("test_vid_2")
    assert loaded_new["video_id"] == "test_vid_2"
    # Old video should no longer be the active checkpoint
    assert gemini_service.get_checkpoint("test_vid_1") is None
    print("[6] 新视频覆盖旧断点校验（单活跃断点规范）通过")

    # 7. Clean up
    gemini_service.clear_checkpoint()
    assert not CHECKPOINT_FILE.exists()
    print("[7] 清理断点成功")

    print("\n>>> 所有断点续跑核心单元测试均 100% 通过！ <<<")

if __name__ == "__main__":
    asyncio.run(test_checkpoint_logic())
