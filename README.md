# SmartVideo - Gemini AI 视频智能剪辑应用

SmartVideo 是一个基于 Google Gemini 多模态大模型与 FFmpeg 的本地视频智能粗剪与合成应用。通过 AI 自动分析整段视频的视觉画面与音频内容，智能判定“保留（1.0x 原速）”、“快进（2x/4x/8x/16x 加速）”与“废片删除”，提供现代化的 Web 工作台让用户进行可视化微调与模拟成片预览，并支持通过显卡硬件加速（如 Intel Iris Xe QSV、NVIDIA NVENC）极速导出成片。

---

## 核心特性

1. **Gemini 多模态深度理解**
   - 对接 AGENTS.md 规范的专用端点与 `inline_data` Base64 视频传输机制。
   - 默认搭载 `gemini-3.8-flash-high`，支持 `gemini-3.7-flash`、`gemini-3-flash`、`gemini-pro-agent` 等 8 款视频理解模型。
   - 智能预处理：针对较大视频，自动在本地生成极速轻量临时代理传输给 AI，既突破网络大小限制，又保障最终导出时基于原始高清源片。
   - 提供 5 种剪辑风格预设（通用均衡、Vlog日常、游戏高光、网课教程、会议记录），支持用户输入个性化自定义剪辑指示。

2. **双轨联动与实时模拟成片预览 Web UI**
   - **多色时间轴总览**：按时间比例可视化呈现整条视频的分段分布（绿色=保留，黄色=快进，灰色=删除）。
   - **✨ 模拟成片预览（Simulated Preview）**：无需等待任何转码，直接在浏览器中体验剪辑后的节奏！播放时遇到删除段自动跳帧跳过，遇到快进段自动按设定的倍速（2x/4x/8x）加速播放。
   - **分段卡片微调**：支持每段起止时间 ±0.5s 微调、当前播放头一键分割（Split）、相邻片段一键合并（Merge）、动作切换与快进倍速调整。

3. **FFmpeg 硬件加速导出引擎**
   - 自动检测并优先采用系统显卡硬件编码器（已实测支持 Intel Iris Xe `h264_qsv`、NVIDIA `h264_nvenc`、AMD `h264_amf`，兼具 `libx264` 软件编码兜底）。
   - 自由选择输出分辨率（原始画质、1080p、720p、4K）、封装格式（MP4、MKV）、码率控制（动态质量或指定 Mbps）。
   - 智能快进音频处理：支持 4x 及以上高倍速自动静音降噪、`atempo` 原声变频全加速或快进一律静音。
   - 实时进度反馈（0-100% 进度条、耗时与预计时间），导出完成后支持一键在 Windows 资源管理器中打开定位或直接浏览器下载。

4. **开箱即用与一键启动**
   - 内置 `run.bat` 与 `run.py`，全自动检测 Python 依赖、Node.js 与 FFmpeg 环境。
   - 前端已预先编译打包并由 FastAPI 统一挂载静态资源，无需手动分窗口管理。

---

## 快速启动

### 方式一：Windows 双击一键启动（推荐）
直接双击运行项目根目录下的：
```bat
run.bat
```
脚本将自动完成环境检测，启动本地 Web 服务并在默认浏览器打开 `http://127.0.0.1:8000`。

### 方式二：命令行启动
```bash
python run.py
```

### 方式三：前后端分离独立开发模式
```bash
# 启动后端 (端口 8000)
python -m uvicorn backend.main:app --reload --port 8000

# 启动前端 (端口 5173，自动代理至 8000)
cd frontend
npm run dev
```

---

## 项目结构

```
SmartVideo/
├── backend/                  # Python FastAPI 后端
│   ├── config.py             # 配置管理 (.env 与可用模型)
│   ├── models.py             # Pydantic 数据契约定义
│   ├── main.py               # FastAPI 入口与静态文件托管
│   ├── routers/
│   │   └── api.py            # 上传、Gemini 分析、导出与系统状态路由
│   └── services/
│       ├── ffmpeg_service.py # FFmpeg 硬件加速探测、探测元数据、Filter Graph 导出引擎
│       └── gemini_service.py # Gemini inline_data 视频分析与时间轴补齐逻辑
├── frontend/                 # React 18 + Vite + Tailwind CSS 前端
│   ├── src/
│   │   ├── components/
│   │   │   ├── Header.jsx         # 顶部导航、FFmpeg/GPU 状态徽章与设置
│   │   │   ├── VideoUploader.jsx  # 视频拖拽上传与元数据卡片
│   │   │   ├── GeminiPanel.jsx    # AI 分析参数、模型切换与风格选择
│   │   │   ├── TimelinePlayer.jsx # 播放器、彩色时间轴与模拟预览
│   │   │   ├── SegmentList.jsx    # 分段明细、微调、分割、合并与统计
│   │   │   ├── ExportModal.jsx    # 导出参数弹窗与实时渲染进度条
│   │   │   └── SettingsModal.jsx  # API Key、Base URL 与 FFmpeg 路径配置
│   │   ├── App.jsx           # 工作台主界面编排
│   │   └── main.jsx
│   └── dist/                 # 预编译静态前端包 (由 FastAPI 自动托管)
├── tests/                    # 单元测试与端到端测试
│   ├── test_clipper.py       # 编码器探测与时间轴规范化单测
│   ├── test_e2e_pipeline.py  # FFmpeg 剪辑与 QSV 硬件加速转码联调
│   └── test_api_integration.py # 完整 API 与 Gemini 模型交互联调
├── data/                     # 数据目录 (临时目录、上传视频、导出成片)
├── AGENTS.md                 # 局域网 Gemini 代理接口规范
├── requirements.txt          # Python 依赖清单
├── run.py                    # 启动管理脚本
└── run.bat                   # Windows 一键启动批处理
```

---

## 系统接口配置说明

默认已根据 `AGENTS.md` 配置好局域网 Gemini 代理：
- **Base URL**: `http://192.168.31.233:8317`
- **Default Model**: `gemini-3.8-flash-high`
- **API Key**: `sk-X3FATzGIIlF5Q7HQx`

您可以在 Web UI 右上角的“设置”按钮中随时修改这些配置，修改后会自动持久化到 `.env` 文件。
