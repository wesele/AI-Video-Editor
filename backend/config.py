import os
from pathlib import Path
from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = BASE_DIR / "data"
UPLOADS_DIR = DATA_DIR / "uploads"
EXPORTS_DIR = DATA_DIR / "exports"
TEMP_DIR = DATA_DIR / "temp"
CHECKPOINTS_DIR = DATA_DIR / "checkpoints"
CHECKPOINT_FILE = CHECKPOINTS_DIR / "current_checkpoint.json"
ENV_FILE = BASE_DIR / ".env"

# Ensure directories exist
UPLOADS_DIR.mkdir(parents=True, exist_ok=True)
EXPORTS_DIR.mkdir(parents=True, exist_ok=True)
TEMP_DIR.mkdir(parents=True, exist_ok=True)
CHECKPOINTS_DIR.mkdir(parents=True, exist_ok=True)

# Load existing .env if present
if ENV_FILE.exists():
    load_dotenv(ENV_FILE)

# Default configuration from AGENTS.md
DEFAULT_GEMINI_BASE_URL = "http://192.168.31.233:8317"
DEFAULT_GEMINI_API_KEY = "sk-X3FATzGIIlF5Q7HQx"
DEFAULT_MODEL = "gemini-3.8-flash-high"

AVAILABLE_MODELS = [
    {"id": "gemini-3.8-flash-high", "name": "Gemini 3.8 Flash (推荐)", "desc": "高性能且速度极快，多模态综合能力最优"},
    {"id": "gemini-3.7-flash-high", "name": "Gemini 3.7 Flash", "desc": "高画质理解，推理细节丰富"},
    {"id": "gemini-3.6-flash-high", "name": "Gemini 3.6 Flash", "desc": "稳定高速分析"},
    {"id": "gemini-3-flash", "name": "Gemini 3 Flash", "desc": "快速通用模型"},
    {"id": "gemini-3.5-flash-lite", "name": "Gemini 3.5 Flash Lite", "desc": "超轻量高响应"},
    {"id": "gemini-3.1-flash-lite", "name": "Gemini 3.1 Flash Lite", "desc": "轻量视频分析"},
    {"id": "gemini-pro-agent", "name": "Gemini 3.1 Pro (High)", "desc": "深度推理，分析长镜头逻辑"},
    {"id": "gemini-3.1-pro-low", "name": "Gemini 3.1 Pro (Low)", "desc": "Pro 降速版"},
]

def get_settings():
    return {
        "gemini_base_url": os.getenv("GEMINI_BASE_URL", DEFAULT_GEMINI_BASE_URL),
        "gemini_api_key": os.getenv("GEMINI_API_KEY", DEFAULT_GEMINI_API_KEY),
        "default_model": os.getenv("DEFAULT_MODEL", DEFAULT_MODEL),
        "custom_ffmpeg_path": os.getenv("CUSTOM_FFMPEG_PATH", ""),
    }

def update_settings(new_settings: dict):
    lines = []
    if ENV_FILE.exists():
        with open(ENV_FILE, "r", encoding="utf-8") as f:
            lines = f.readlines()
    
    config_map = {
        "GEMINI_BASE_URL": new_settings.get("gemini_base_url"),
        "GEMINI_API_KEY": new_settings.get("gemini_api_key"),
        "DEFAULT_MODEL": new_settings.get("default_model"),
        "CUSTOM_FFMPEG_PATH": new_settings.get("custom_ffmpeg_path"),
    }
    
    # Update or add keys
    updated_keys = set()
    new_lines = []
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            new_lines.append(line)
            continue
        if "=" in stripped:
            k, _ = stripped.split("=", 1)
            k = k.strip()
            if k in config_map and config_map[k] is not None:
                new_lines.append(f"{k}={config_map[k]}\n")
                os.environ[k] = str(config_map[k])
                updated_keys.add(k)
            else:
                new_lines.append(line)
        else:
            new_lines.append(line)
            
    for k, v in config_map.items():
        if k not in updated_keys and v is not None:
            new_lines.append(f"{k}={v}\n")
            os.environ[k] = str(v)
            
    with open(ENV_FILE, "w", encoding="utf-8") as f:
        f.writelines(new_lines)
        
    return get_settings()
