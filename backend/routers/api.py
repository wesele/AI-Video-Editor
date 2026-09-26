import os
import uuid
import asyncio
import subprocess
from pathlib import Path
from typing import Dict, Any, Optional
from fastapi import APIRouter, UploadFile, File, HTTPException, BackgroundTasks
from backend.config import (
    UPLOADS_DIR, EXPORTS_DIR, AVAILABLE_MODELS, get_settings, update_settings
)
from backend.models import (
    VideoMeta, AnalyzeRequest, ExportRequest, SettingsPayload
)
from backend.services.ffmpeg_service import ffmpeg_service
from backend.services.gemini_service import gemini_service

router = APIRouter(prefix="/api")

# In-memory job state trackers
analyze_jobs: Dict[str, Dict[str, Any]] = {}
export_jobs: Dict[str, Dict[str, Any]] = {}
uploaded_videos: Dict[str, Dict[str, Any]] = {}

@router.get("/system/status")
async def get_system_status():
    sys_info = ffmpeg_service.check_system()
    settings = get_settings()
    return {
        "ffmpeg": sys_info,
        "models": AVAILABLE_MODELS,
        "settings": {
            "gemini_base_url": settings.get("gemini_base_url"),
            "has_api_key": bool(settings.get("gemini_api_key")),
            "api_key_masked": settings.get("gemini_api_key", "")[:4] + "****" if settings.get("gemini_api_key") else "",
            "default_model": settings.get("default_model"),
            "custom_ffmpeg_path": settings.get("custom_ffmpeg_path"),
        }
    }

@router.get("/settings")
async def get_settings_api():
    return get_settings()

@router.post("/settings")
async def save_settings_api(payload: SettingsPayload):
    updated = update_settings(payload.model_dump())
    return {"status": "ok", "settings": updated}

@router.post("/video/upload")
async def upload_video(file: UploadFile = File(...)):
    video_id = str(uuid.uuid4())[:8]
    ext = Path(file.filename).suffix or ".mp4"
    safe_name = f"{video_id}_{file.filename}"
    target_path = UPLOADS_DIR / safe_name

    with open(target_path, "wb") as buffer:
        while chunk := await file.read(1024 * 1024):
            buffer.write(chunk)

    file_size_bytes = target_path.stat().st_size
    file_size_formatted = f"{round(file_size_bytes / (1024 * 1024), 2)} MB"

    try:
        meta = ffmpeg_service.probe_video(str(target_path))
    except Exception as e:
        if target_path.exists():
            target_path.unlink()
        raise HTTPException(status_code=400, detail=f"解析视频文件失败: {str(e)}")

    video_info = {
        "video_id": video_id,
        "filename": file.filename,
        "safe_filename": safe_name,
        "filepath": str(target_path),
        "filesize_bytes": file_size_bytes,
        "filesize_formatted": file_size_formatted,
        "duration": meta["duration"],
        "width": meta["width"],
        "height": meta["height"],
        "fps": meta["fps"],
        "bitrate": meta["bitrate"],
        "has_audio": meta["has_audio"],
        "stream_url": f"/media/uploads/{safe_name}",
    }
    uploaded_videos[video_id] = video_info
    return video_info

async def _run_analysis(job_id: str, video_path: str, req: AnalyzeRequest):
    init_progress = 0.0
    init_msg = "正在初始化视频分析管线..."
    init_segments = None

    if req.resume:
        ckpt = gemini_service.get_checkpoint(req.video_id)
        if ckpt and ckpt.get("stage1_completed_chunks"):
            init_segments = []
            for c in ckpt["stage1_completed_chunks"]:
                init_segments.extend(c.get("segments", []))
            total_c = max(1, ckpt.get("total_chunks", 1))
            done_c = len(ckpt["stage1_completed_chunks"])
            init_progress = round((done_c / total_c) * 75.0, 1)
            init_msg = f"正在从断点恢复...（已复用前 {done_c}/{total_c} 段）"

    analyze_jobs[job_id] = {
        "status": "processing",
        "progress": init_progress,
        "progress_message": init_msg,
        "segments": init_segments,
        "error": None,
    }

    def on_progress(pct: float, msg: str, segs: Optional[Any] = None):
        if job_id in analyze_jobs:
            analyze_jobs[job_id]["progress"] = pct
            analyze_jobs[job_id]["progress_message"] = msg
            if segs is not None and len(segs) > 0:
                analyze_jobs[job_id]["segments"] = list(segs)

    try:
        segments = await gemini_service.analyze_video(
            video_path=video_path,
            video_id=req.video_id,
            model_name=req.model,
            style_preset=req.style_preset,
            custom_prompt=req.custom_prompt,
            default_ff_speed=req.default_fast_forward_speed,
            resume=req.resume,
            progress_callback=on_progress,
        )
        analyze_jobs[job_id] = {
            "status": "success",
            "progress": 100.0,
            "progress_message": f"AI 分析完成！共提取出 {len(segments)} 个剪辑分段",
            "segments": segments,
            "error": None,
        }
    except Exception as e:
        prev_segs = analyze_jobs.get(job_id, {}).get("segments")
        if not prev_segs:
            ckpt = gemini_service.get_checkpoint(req.video_id)
            if ckpt and ckpt.get("stage1_completed_chunks"):
                prev_segs = []
                for c in ckpt["stage1_completed_chunks"]:
                    prev_segs.extend(c.get("segments", []))
        analyze_jobs[job_id] = {
            "status": "failed",
            "progress": analyze_jobs.get(job_id, {}).get("progress", 0.0),
            "progress_message": f"分析失败: {str(e)}",
            "segments": prev_segs,
            "error": str(e),
        }

def get_or_restore_video_info(video_id: str):
    if video_id in uploaded_videos and os.path.exists(uploaded_videos[video_id].get("filepath", "")):
        return uploaded_videos[video_id]

    # Search on disk
    candidates = list(UPLOADS_DIR.glob(f"{video_id}_*"))
    if candidates and candidates[0].exists():
        target_path = candidates[0]
        try:
            meta = ffmpeg_service.probe_video(str(target_path))
            orig_name = target_path.name[len(video_id) + 1:]
            info = {
                "video_id": video_id,
                "filename": orig_name,
                "safe_filename": target_path.name,
                "filepath": str(target_path),
                "filesize_bytes": target_path.stat().st_size,
                "filesize_formatted": f"{round(target_path.stat().st_size / (1024 * 1024), 2)} MB",
                "duration": meta["duration"],
                "width": meta["width"],
                "height": meta["height"],
                "fps": meta["fps"],
                "bitrate": meta["bitrate"],
                "has_audio": meta["has_audio"],
                "stream_url": f"/media/uploads/{target_path.name}",
            }
            uploaded_videos[video_id] = info
            return info
        except Exception:
            return None
    return None

@router.post("/video/analyze")
async def analyze_video(req: AnalyzeRequest, background_tasks: BackgroundTasks):
    video_info = get_or_restore_video_info(req.video_id)
    if not video_info:
        raise HTTPException(status_code=404, detail="视频文件未找到，请重新上传")

    job_id = str(uuid.uuid4())[:8]
    background_tasks.add_task(_run_analysis, job_id, video_info["filepath"], req)
    return {"job_id": job_id, "status": "processing"}

@router.get("/video/analyze/status/{job_id}")
async def get_analyze_status(job_id: str):
    if job_id not in analyze_jobs:
        raise HTTPException(status_code=404, detail="分析任务不存在")
    return analyze_jobs[job_id]

@router.get("/video/analyze/checkpoint")
async def get_checkpoint_api(video_id: Optional[str] = None):
    ckpt = gemini_service.get_checkpoint(video_id)
    if not ckpt:
        return {"has_checkpoint": False, "checkpoint": None, "partial_segments": []}

    partial_segments = []
    if ckpt.get("stage1_completed_chunks"):
        for c in ckpt["stage1_completed_chunks"]:
            partial_segments.extend(c.get("segments", []))
    elif ckpt.get("final_segments"):
        partial_segments = ckpt["final_segments"]

    return {
        "has_checkpoint": True,
        "checkpoint": ckpt,
        "partial_segments": partial_segments,
    }

@router.delete("/video/analyze/checkpoint")
async def clear_checkpoint_api():
    gemini_service.clear_checkpoint()
    return {"status": "ok", "message": "断点已清理"}

async def _run_export(
    task_id: str,
    input_path: str,
    output_path: str,
    req: ExportRequest,
):
    export_jobs[task_id] = {
        "status": "processing",
        "progress": 0.0,
        "message": "正在准备转码管线...",
        "output_url": None,
        "output_path": output_path,
        "error": None,
    }

    def on_progress(pct: float, msg: str):
        export_jobs[task_id]["progress"] = pct
        export_jobs[task_id]["message"] = msg

    try:
        raw_segments = [s.model_dump() for s in req.segments]
        await ffmpeg_service.export_video_async(
            input_path=input_path,
            output_path=output_path,
            segments=raw_segments,
            resolution=req.resolution,
            format_type=req.format,
            bitrate_mode=req.bitrate_mode,
            custom_bitrate_mbps=req.custom_bitrate_mbps,
            encoder=req.encoder,
            audio_mode=req.audio_mode,
            progress_callback=on_progress,
        )
        export_jobs[task_id]["status"] = "success"
        export_jobs[task_id]["progress"] = 100.0
        export_jobs[task_id]["message"] = "视频合成导出成功！"
        export_jobs[task_id]["output_url"] = f"/media/exports/{Path(output_path).name}"
    except Exception as e:
        export_jobs[task_id]["status"] = "failed"
        export_jobs[task_id]["message"] = f"导出失败: {str(e)}"
        export_jobs[task_id]["error"] = str(e)

@router.post("/video/export")
async def export_video_endpoint(req: ExportRequest, background_tasks: BackgroundTasks):
    video_info = get_or_restore_video_info(req.video_id)
    if not video_info or not os.path.exists(video_info["filepath"]):
        raise HTTPException(status_code=404, detail="原始视频文件未找到")

    task_id = str(uuid.uuid4())[:8]
    ext = f".{req.format}"
    base_stem = Path(video_info["filename"]).stem
    output_filename = f"cut_{base_stem}_{task_id}{ext}"
    output_path = str(EXPORTS_DIR / output_filename)

    background_tasks.add_task(_run_export, task_id, video_info["filepath"], output_path, req)
    return {"task_id": task_id, "status": "processing"}

@router.get("/video/export/status/{task_id}")
async def get_export_status(task_id: str):
    if task_id not in export_jobs:
        raise HTTPException(status_code=404, detail="导出任务不存在")
    return export_jobs[task_id]

@router.post("/video/open-folder")
async def open_folder(payload: Dict[str, str]):
    target_path = payload.get("path")
    if not target_path or not os.path.exists(target_path):
        target_path = str(EXPORTS_DIR)
        
    try:
        # Use explorer /select,"path" on Windows
        if os.path.isfile(target_path):
            subprocess.Popen(f'explorer.exe /select,"{os.path.normpath(target_path)}"')
        else:
            subprocess.Popen(f'explorer.exe "{os.path.normpath(target_path)}"')
        return {"status": "ok"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"打开文件夹失败: {str(e)}")
