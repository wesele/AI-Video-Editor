import os
import sys
import time
import shutil
import webbrowser
import subprocess
import threading
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent

def check_python_deps():
    print("[1/4] 正在检查 Python 依赖...")
    required = ["fastapi", "uvicorn", "httpx", "pydantic", "dotenv"]
    missing = []
    for pkg in required:
        try:
            __import__(pkg)
        except ImportError:
            missing.append(pkg)
            
    if missing:
        print(f"正在安装缺失的依赖项: {missing} ...")
        req_file = BASE_DIR / "requirements.txt"
        subprocess.check_call([sys.executable, "-m", "pip", "install", "-r", str(req_file)])
    print("Python 依赖已就绪。")

def check_ffmpeg():
    print("[2/4] 正在检查 FFmpeg 视频引擎...")
    which = shutil.which("ffmpeg")
    if which:
        print(f"检测到系统 FFmpeg: {which}")
        return True
    
    local_bin = BASE_DIR / "bin" / "ffmpeg.exe"
    if local_bin.exists():
        print(f"检测到本地 FFmpeg: {local_bin}")
        return True

    print("未在 PATH 或 local bin 中检测到 ffmpeg，尝试通过 winget 自动安装...")
    try:
        subprocess.run(["winget", "install", "Gyan.FFmpeg", "--accept-source-agreements", "--accept-package-agreements"], check=True)
        print("FFmpeg 安装成功！")
        return True
    except Exception as e:
        print(f"自动安装 FFmpeg 提示: {e}。如需使用请手动安装并加入系统 PATH。")
        return False

def check_frontend_build():
    print("[3/4] 正在检查前端构建产物...")
    dist_index = BASE_DIR / "frontend" / "dist" / "index.html"
    if not dist_index.exists():
        print("首次运行，正在编译现代化前端静态资源 (Vite build)...")
        npm_bin = shutil.which("npm.cmd") or shutil.which("npm")
        if npm_bin:
            frontend_dir = BASE_DIR / "frontend"
            subprocess.run([npm_bin, "install"], cwd=frontend_dir, check=True)
            subprocess.run([npm_bin, "run", "build"], cwd=frontend_dir, check=True)
            print("前端编译完成！")
        else:
            print("未检测到 npm，将使用独立前端 dev 或基础模式。")
    else:
        print("前端已编译完成。")

def open_browser_delayed(url: str, delay: float = 1.5):
    def _open():
        time.sleep(delay)
        print(f"\n[INFO] 正在打开浏览器: {url}")
        webbrowser.open(url)
    threading.Thread(target=_open, daemon=True).start()

def find_free_port(start_port: int = 8088) -> int:
    import socket
    for p in range(start_port, start_port + 50):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            if s.connect_ex(('127.0.0.1', p)) != 0:
                return p
    return start_port

def main():
    print("=" * 60)
    print("        SmartVideo - Gemini 视频智能剪辑应用启动器       ")
    print("=" * 60)

    check_python_deps()
    check_ffmpeg()
    check_frontend_build()

    port = find_free_port(8088)
    print(f"[4/4] 启动 FastAPI 本地服务 (端口: {port})...")
    app_url = f"http://127.0.0.1:{port}"
    open_browser_delayed(app_url)

    import uvicorn
    uvicorn.run("backend.main:app", host="127.0.0.1", port=port, reload=False)

if __name__ == "__main__":
    main()
