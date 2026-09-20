import sys
import time
import json
import httpx
import asyncio
from pathlib import Path

async def run_full_test():
    print("=" * 65)
    print("      SmartVideo - 51 分钟真实大视频全链路深度测试")
    print("      目标文件: c:\\temp\\VID_20260919_163133.mp4")
    print("=" * 65)

    base_url = "http://127.0.0.1:8088"
    video_id = "a3a312ef"

    async with httpx.AsyncClient(base_url=base_url, timeout=300.0) as client:
        # 1. 验证视频元数据
        print("\n[Step 1] 校验视频在服务端的元数据状态...")
        res_stat = await client.get("/api/system/status")
        assert res_stat.status_code == 200, f"System status failed: {res_stat.text}"
        sys_data = res_stat.json()
        print(f"  FFmpeg: {sys_data['ffmpeg']['ffmpeg_version']}")
        print(f"  硬件加速推荐: {sys_data['ffmpeg']['recommended_encoder']}")

        # 2. 触发 51 分钟视频分段流水线分析
        print(f"\n[Step 2] 触发 51 分钟长视频分段流式 AI 分析 (video_id={video_id})...")
        payload = {
            "video_id": video_id,
            "model": "gemini-3.8-flash-high",
            "style_preset": "general",
            "custom_prompt": "识别精彩对话与核心事件保留，过渡走动或无聊等待快进，完全死屏或无意义黑屏剔除",
            "default_fast_forward_speed": 4.0
        }
        res_a = await client.post("/api/video/analyze", json=payload)
        if res_a.status_code != 200:
            print(f"触发分析失败: {res_a.status_code} {res_a.text}")
            return
        
        job_data = res_a.json()
        job_id = job_data["job_id"]
        print(f"  AI 分析任务已提交成功! Job ID: {job_id}")
        print("  正在实时监听 18 个分段 (每段 180 秒) 的极速切片与 Gemini 视觉分析...\n")

        # 3. 轮询监控流式进度
        start_time = time.time()
        last_seg_count = -1
        final_segments = []

        while True:
            await asyncio.sleep(2.0)
            elapsed = time.time() - start_time
            m = int(elapsed // 60)
            s = int(elapsed % 60)
            timer_str = f"{m:02d}:{s:02d}"

            try:
                st_res = await client.get(f"/api/video/analyze/status/{job_id}")
                if st_res.status_code != 200:
                    print(f"  [Warning] 获取状态 HTTP {st_res.status_code}")
                    continue
                st = st_res.json()
            except Exception as poll_err:
                print(f"  [Warning] 轮询异常: {poll_err}")
                continue

            status = st.get("status")
            progress = st.get("progress", 0.0)
            msg = st.get("progress_message", "")
            segs = st.get("segments") or []

            # 打印流式更新
            if len(segs) != last_seg_count or progress >= 100.0:
                print(f"  [T+{timer_str}] 进度: {progress:5.1f}% | 实时已点亮片段: {len(segs):2d} 段 | 当前阶段: {msg}")
                last_seg_count = len(segs)

            if status == "success":
                final_segments = segs
                print(f"\n[SUCCESS] 51 分钟视频全部分段流式 AI 分析顺利完成！总耗时: {timer_str}")
                break
            elif status == "failed":
                err = st.get("error", "未知错误")
                print(f"\n[ERROR] AI 分析任务失败: {err}")
                return

        # 4. 分析方案结果统计
        total_orig_dur = 3086.4
        keep_dur = sum(s["duration"] for s in final_segments if s["action"] == "keep")
        ff_dur = sum(s["duration"] for s in final_segments if s["action"] == "fast_forward")
        del_dur = sum(s["duration"] for s in final_segments if s["action"] == "delete")
        est_output_dur = sum(
            s["duration"] if s["action"] == "keep" 
            else s["duration"] / (s.get("speed") or 4.0) if s["action"] == "fast_forward" 
            else 0.0
            for s in final_segments
        )
        compress_rate = round((est_output_dur / total_orig_dur) * 100, 1)

        print("\n" + "=" * 65)
        print("                  AI 剪辑方案统计汇总")
        print("=" * 65)
        print(f"  原视频总时长:   {int(total_orig_dur//60)} 分 {int(total_orig_dur%60)} 秒 ({total_orig_dur:.1f}s)")
        print(f"  预计成片时长:   {int(est_output_dur//60)} 分 {int(est_output_dur%60)} 秒 ({est_output_dur:.1f}s)")
        print(f"  时间压缩率:     {compress_rate}% (精简了 {100-compress_rate:.1f}% 的冗余时间)")
        print(f"  分段总数量:     {len(final_segments)} 个")
        print(f"    - 保留段 (1x):  {sum(1 for s in final_segments if s['action']=='keep')} 段, 累计 {keep_dur:.1f}s")
        print(f"    - 快进段 (4x):  {sum(1 for s in final_segments if s['action']=='fast_forward')} 段, 累计 {ff_dur:.1f}s")
        print(f"    - 删除段 (0x):  {sum(1 for s in final_segments if s['action']=='delete')} 段, 累计 {del_dur:.1f}s")
        print("=" * 65)

        print("\n前 5 个分段预览:")
        for s in final_segments[:5]:
            print(f"  [{s['start_time']:6.1f}s ~ {s['end_time']:6.1f}s] {s['action']:12s} {s['speed']:3.1f}x | {s['reason']}")

        if len(final_segments) > 5:
            print(f"  ... (中间省略 {len(final_segments)-10 if len(final_segments)>10 else len(final_segments)-5} 个分段) ...")
            for s in final_segments[-5:]:
                print(f"  [{s['start_time']:6.1f}s ~ {s['end_time']:6.1f}s] {s['action']:12s} {s['speed']:3.1f}x | {s['reason']}")

        # 5. 触发成片合成导出验证 (测试硬件加速与音画合成)
        print("\n[Step 3] 触发成片合成导出测试 (Intel QSV 高速转码)...")
        # 挑选代表性分段进行成片合成验证（选取前 6 个包含保留与快进的典型连续分段）
        sample_export_segs = final_segments[:min(6, len(final_segments))]
        export_payload = {
            "video_id": video_id,
            "segments": sample_export_segs,
            "resolution": "original",
            "format": "mp4",
            "bitrate_mode": "auto",
            "encoder": "qsv",
            "audio_mode": "mute_high_speed"
        }
        res_exp = await client.post("/api/video/export", json=export_payload)
        assert res_exp.status_code == 200, f"Export failed: {res_exp.text}"
        task_id = res_exp.json()["task_id"]
        print(f"  导出任务已启动! Task ID: {task_id}")

        export_start = time.time()
        while True:
            await asyncio.sleep(1.0)
            st_exp = (await client.get(f"/api/video/export/status/{task_id}")).json()
            pct = st_exp.get("progress", 0.0)
            msg = st_exp.get("message", "")
            exp_elapsed = time.time() - export_start
            print(f"  [导出 T+{int(exp_elapsed)}s] {pct:5.1f}% | {msg}")
            if st_exp.get("status") == "success":
                out_url = st_exp.get("output_url")
                out_path = st_exp.get("output_path")
                print(f"\n[SUCCESS] 成片导出成功！")
                print(f"  本地成片路径: {out_path}")
                print(f"  Web 访问地址:  {base_url}{out_url}")
                break
            elif st_exp.get("status") == "failed":
                print(f"\n[ERROR] 导出失败: {st_exp.get('error')}")
                break

    print("\n" + "=" * 65)
    print("      51 分钟真实大视频全流程深度实测圆满完成！")
    print("=" * 65)

if __name__ == "__main__":
    asyncio.run(run_full_test())
