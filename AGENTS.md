# Gemini 接口及视频处理配置说明

## 1. 接口基本信息

- **Base URL**: `http://192.168.31.233:8317`
- **Models Endpoint**: `http://192.168.31.233:8317/v1beta/models`
- **API Key**: `sk-X3FATzGIIlF5Q7HQx`
- **鉴权方式**:
  - URL Query 参数: `?key=sk-X3FATzGIIlF5Q7HQx`
  - 或 HTTP Header: `x-goog-api-key: sk-X3FATzGIIlF5Q7HQx`

---

## 2. Gemini 模型列表及视频处理支持情况

经接口元数据查询与调用实测，该服务提供的绝大多数 Gemini 模型均支持视频输入与理解。

| 模型标识 (name) | 显示名称 (displayName) | 支持输入模态 (supportedInputModalities) | 支持输出模态 (supportedOutputModalities) | 视频处理支持 |
| :--- | :--- | :--- | :--- | :---: |
| `models/gemini-3.8-flash-high` | Gemini 3.8 Flash | `text`, `image`, `audio`, `video` | `text` | **支持** |
| `models/gemini-3.7-flash-high` | Gemini 3.7 Flash | `text`, `image`, `audio`, `video` | `text` | **支持** |
| `models/gemini-3.6-flash-high` | Gemini 3.6 Flash | `text`, `image`, `audio`, `video` | `text` | **支持** |
| `models/gemini-3-flash` | Gemini 3 Flash | `text`, `image`, `audio`, `video` | `text` | **支持** |
| `models/gemini-3.5-flash-lite` | Gemini 3.5 Flash Lite | `text`, `image`, `audio`, `video` | `text` | **支持** |
| `models/gemini-3.1-flash-lite` | Gemini 3.1 Flash Lite | `text`, `image`, `audio`, `video` | `text` | **支持** |
| `models/gemini-pro-agent` | Gemini 3.1 Pro (High) | `text`, `image`, `audio`, `video` | `text` | **支持** |
| `models/gemini-3.1-pro-low` | Gemini 3.1 Pro (Low) | `text`, `image`, `audio`, `video` | `text` | **支持** |
| `models/gemini-3.1-flash-image` | Gemini 3.1 Flash Image | `text`, `image` | `text`, `image` | **不支持**（仅限图像） |

---

## 3. 视频处理调用特性与注意事项

1. **调用方式**：
   - 该代理端点未开放独立的文件上传接口（`GET /v1beta/files` 返回 `404 Not Found`）。
   - 视频处理需通过 `generateContent` 接口中的 `inline_data` 传递 Base64 编码的视频二进制数据。
2. **支持格式**：
   - 常见视频格式如 `video/mp4`, `video/webm` 等。
3. **大小限制说明**：
   - 采用 `inline_data` Base64 方式直接上传通常适用于几 MB 至 ~20MB 左右的视频切片或短视频片段。

---

## 4. 调用示例

### 4.1 HTTP 请求示例

```http
POST http://192.168.31.233:8317/v1beta/models/gemini-3.8-flash-high:generateContent?key=sk-X3FATzGIIlF5Q7HQx
Content-Type: application/json

{
  "contents": [
    {
      "parts": [
        {
          "text": "请详细描述这段视频中的场景与人物行为："
        },
        {
          "inline_data": {
            "mime_type": "video/mp4",
            "data": "<BASE64_ENCODED_VIDEO_STRING>"
          }
        }
      ]
    }
  ]
}
```

### 4.2 Python 调用示例

```python
import base64
import json
import urllib.request

api_key = "sk-X3FATzGIIlF5Q7HQx"
model_name = "gemini-3.8-flash-high"
url = f"http://192.168.31.233:8317/v1beta/models/{model_name}:generateContent?key={api_key}"

# 读取视频并转为 Base64
with open("sample.mp4", "rb") as f:
    video_b64 = base64.b64encode(f.read()).decode("utf-8")

payload = {
    "contents": [
        {
            "parts": [
                {"text": "分析视频中的内容："},
                {
                    "inline_data": {
                        "mime_type": "video/mp4",
                        "data": video_b64
                    }
                }
            ]
        }
    ]
}

req = urllib.request.Request(
    url,
    data=json.dumps(payload).encode("utf-8"),
    headers={"Content-Type": "application/json"}
)

with urllib.request.urlopen(req) as response:
    result = json.loads(response.read().decode("utf-8"))
    print(result["candidates"][0]["content"]["parts"][-1]["text"])
```
