import React, { useState, useEffect } from 'react';
import { X, Zap, Download, FolderOpen, RefreshCw, CheckCircle2, AlertCircle, Cpu, Volume2, Film } from 'lucide-react';
import axios from 'axios';

export default function ExportModal({
  isOpen,
  onClose,
  videoMeta,
  segments,
  systemStatus,
}) {
  const [resolution, setResolution] = useState('original');
  const [format, setFormat] = useState('mp4');
  const [bitrateMode, setBitrateMode] = useState('auto');
  const [customBitrate, setCustomBitrate] = useState(12.0);
  const [encoder, setEncoder] = useState('auto');
  const [audioMode, setAudioMode] = useState('auto_mute');
  
  const [exporting, setExporting] = useState(false);
  const [progress, setProgress] = useState(0);
  const [progressMsg, setProgressMsg] = useState('');
  const [isCompleted, setIsCompleted] = useState(false);
  const [outputUrl, setOutputUrl] = useState('');
  const [outputPath, setOutputPath] = useState('');
  const [errorMsg, setErrorMsg] = useState('');

  // Default to recommended hardware encoder
  useEffect(() => {
    if (systemStatus?.ffmpeg?.recommended_encoder) {
      setEncoder('auto');
    }
  }, [systemStatus]);

  if (!isOpen) return null;

  const handleStartExport = async () => {
    setExporting(true);
    setProgress(0);
    setProgressMsg('正在初始化导出管线与滤镜图...');
    setIsCompleted(false);
    setErrorMsg('');

    try {
      const initRes = await axios.post('/api/video/export', {
        video_id: videoMeta.video_id,
        segments: segments,
        resolution: resolution,
        format: format,
        bitrate_mode: bitrateMode,
        custom_bitrate_mbps: bitrateMode === 'custom' ? parseFloat(customBitrate) : null,
        encoder: encoder,
        audio_mode: audioMode,
      });

      const taskId = initRes.data.task_id;

      // Poll export progress
      const pollTimer = setInterval(async () => {
        try {
          const statusRes = await axios.get(`/api/video/export/status/${taskId}`);
          const data = statusRes.data;

          if (data.status === 'processing') {
            setProgress(data.progress || 0);
            setProgressMsg(data.message || '渲染转码中...');
          } else if (data.status === 'success') {
            clearInterval(pollTimer);
            setExporting(false);
            setProgress(100);
            setIsCompleted(true);
            setOutputUrl(data.output_url);
            setOutputPath(data.output_path);
          } else if (data.status === 'failed') {
            clearInterval(pollTimer);
            setExporting(false);
            setErrorMsg(data.message || data.error || '导出失败');
          }
        } catch (err) {
          clearInterval(pollTimer);
          setExporting(false);
          setErrorMsg('获取导出进度失败: ' + err.message);
        }
      }, 1000);

    } catch (e) {
      setExporting(false);
      setErrorMsg('启动导出任务失败: ' + (e.response?.data?.detail || e.message));
    }
  };

  const handleOpenFolder = async () => {
    try {
      await axios.post('/api/video/open-folder', { path: outputPath });
    } catch (e) {
      alert('打开文件夹失败: ' + e.message);
    }
  };

  const detectedEncoders = systemStatus?.ffmpeg?.encoders || {};

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4">
      <div className="bg-slate-900 border border-slate-800 rounded-2xl w-full max-w-xl shadow-2xl overflow-hidden flex flex-col max-h-[85vh]">
        {/* Header */}
        <div className="px-6 py-4 border-b border-slate-800 flex items-center justify-between">
          <h2 className="text-base font-bold text-white flex items-center gap-2">
            <Zap className="w-5 h-5 text-sky-400" />
            合成导出成片
          </h2>
          {!exporting && (
            <button
              onClick={onClose}
              className="text-slate-400 hover:text-white p-1 rounded-lg hover:bg-slate-800 transition"
            >
              <X className="w-5 h-5" />
            </button>
          )}
        </div>

        {/* Content */}
        <div className="p-6 space-y-5 overflow-y-auto text-sm">
          {errorMsg && (
            <div className="p-3 bg-rose-500/10 border border-rose-500/30 text-rose-300 rounded-xl text-xs flex items-center gap-2">
              <AlertCircle className="w-4 h-4 shrink-0" />
              <span>{errorMsg}</span>
            </div>
          )}

          {!exporting && !isCompleted ? (
            <>
              {/* Resolution & Format */}
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-semibold text-slate-300 mb-1.5 flex items-center gap-1.5">
                    <Film className="w-3.5 h-3.5 text-sky-400" /> 输出分辨率
                  </label>
                  <select
                    value={resolution}
                    onChange={(e) => setResolution(e.target.value)}
                    className="w-full bg-slate-800 border border-slate-700 rounded-xl px-3 py-2 text-slate-100 text-xs focus:outline-none focus:border-sky-500"
                  >
                    <option value="original">原始画质 ({videoMeta?.width}x{videoMeta?.height})</option>
                    <option value="1080p">1080p 全高清 (1920x1080)</option>
                    <option value="720p">720p 高清 (1280x720)</option>
                    <option value="4k">4K 超高清 (3840x2160)</option>
                  </select>
                </div>

                <div>
                  <label className="block text-xs font-semibold text-slate-300 mb-1.5">
                    容器封装格式
                  </label>
                  <select
                    value={format}
                    onChange={(e) => setFormat(e.target.value)}
                    className="w-full bg-slate-800 border border-slate-700 rounded-xl px-3 py-2 text-slate-100 text-xs focus:outline-none focus:border-sky-500"
                  >
                    <option value="mp4">MP4 (.mp4, 兼容性最佳)</option>
                    <option value="mkv">MKV (.mkv, 包含无损流)</option>
                  </select>
                </div>
              </div>

              {/* Hardware Acceleration & Codec */}
              <div>
                <label className="block text-xs font-semibold text-slate-300 mb-1.5 flex items-center gap-1.5">
                  <Cpu className="w-3.5 h-3.5 text-indigo-400" />
                  硬件加速引擎 (Encoder)
                </label>
                <select
                  value={encoder}
                  onChange={(e) => setEncoder(e.target.value)}
                  className="w-full bg-slate-800 border border-slate-700 rounded-xl px-3 py-2 text-slate-100 text-xs focus:outline-none focus:border-sky-500 font-mono"
                >
                  <option value="auto">
                    🚀 自动最佳加速 (推荐: {systemStatus?.ffmpeg?.recommended_encoder === 'qsv' ? 'Intel QSV Iris Xe' : '系统匹配'})
                  </option>
                  {detectedEncoders.qsv && (
                    <option value="qsv">Intel QuickSync (h264_qsv - 硬件高速)</option>
                  )}
                  {detectedEncoders.nvenc && (
                    <option value="nvenc">NVIDIA NVENC (h264_nvenc - 硬件高速)</option>
                  )}
                  {detectedEncoders.amf && (
                    <option value="amf">AMD AMF (h264_amf - 硬件高速)</option>
                  )}
                  <option value="cpu">CPU 软件编码 (libx264 - 极高画质但耗算力)</option>
                </select>
              </div>

              {/* Bitrate Control */}
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-semibold text-slate-300 mb-1.5">
                    码率控制模式
                  </label>
                  <select
                    value={bitrateMode}
                    onChange={(e) => setBitrateMode(e.target.value)}
                    className="w-full bg-slate-800 border border-slate-700 rounded-xl px-3 py-2 text-slate-100 text-xs focus:outline-none focus:border-sky-500"
                  >
                    <option value="auto">自适应动态恒定质量 (推荐)</option>
                    <option value="custom">自定义固定码率 (CBR/VBR)</option>
                  </select>
                </div>

                {bitrateMode === 'custom' && (
                  <div>
                    <label className="block text-xs font-semibold text-slate-300 mb-1.5">
                      视频码率 (Mbps)
                    </label>
                    <input
                      type="number"
                      step="1"
                      min="1"
                      max="100"
                      value={customBitrate}
                      onChange={(e) => setCustomBitrate(e.target.value)}
                      className="w-full bg-slate-800 border border-slate-700 rounded-xl px-3 py-2 text-slate-100 text-xs focus:outline-none focus:border-sky-500 font-mono"
                    />
                  </div>
                )}
              </div>

              {/* Audio strategy for fast forward */}
              <div>
                <label className="block text-xs font-semibold text-slate-300 mb-1.5 flex items-center gap-1.5">
                  <Volume2 className="w-3.5 h-3.5 text-amber-400" />
                  快进段音频处理策略
                </label>
                <div className="space-y-2">
                  {[
                    { id: 'auto_mute', name: '智能变频降噪 (推荐)', desc: '2x 保留变调加速音效，4x及以上高倍速自动静音避免刺耳尖啸' },
                    { id: 'atempo', name: '全加速保留 (atempo)', desc: '所有快进片段均加速声音播放（声音将变尖细花栗鼠声）' },
                    { id: 'mute_all_ff', name: '快进段全部静音', desc: '只要是快进段落全部做静音处理，观感最清爽' },
                  ].map(opt => (
                    <label
                      key={opt.id}
                      onClick={() => setAudioMode(opt.id)}
                      className={`p-2.5 rounded-xl border flex items-center justify-between cursor-pointer transition ${
                        audioMode === opt.id
                          ? 'bg-sky-500/10 border-sky-500 text-sky-200'
                          : 'bg-slate-800/40 border-slate-800 hover:border-slate-700 text-slate-300'
                      }`}
                    >
                      <div>
                        <div className="font-semibold text-xs text-white">{opt.name}</div>
                        <div className="text-[11px] text-slate-400 mt-0.5">{opt.desc}</div>
                      </div>
                      <input
                        type="radio"
                        name="audio_mode"
                        checked={audioMode === opt.id}
                        onChange={() => setAudioMode(opt.id)}
                        className="text-sky-500"
                      />
                    </label>
                  ))}
                </div>
              </div>
            </>
          ) : (
            /* Progress & Complete UI */
            <div className="py-6 space-y-6 text-center">
              {isCompleted ? (
                <div className="space-y-3">
                  <div className="w-16 h-16 rounded-full bg-emerald-500/10 border border-emerald-500/20 text-emerald-400 mx-auto flex items-center justify-center">
                    <CheckCircle2 className="w-8 h-8" />
                  </div>
                  <h3 className="text-base font-bold text-white">视频合成导出成功！</h3>
                  <p className="text-xs text-slate-400 font-mono break-all px-4">{outputPath}</p>
                </div>
              ) : (
                <div className="space-y-4">
                  <div className="w-16 h-16 rounded-full bg-sky-500/10 border border-sky-500/20 text-sky-400 mx-auto flex items-center justify-center">
                    <RefreshCw className="w-8 h-8 animate-spin" />
                  </div>
                  <div>
                    <h3 className="text-base font-bold text-white">正在合成与硬件加速转码</h3>
                    <p className="text-xs text-slate-400 mt-1">{progressMsg}</p>
                  </div>
                  <div className="w-full bg-slate-800 rounded-full h-3 overflow-hidden shadow-inner max-w-md mx-auto">
                    <div
                      className="bg-gradient-to-r from-sky-500 via-indigo-500 to-emerald-500 h-full transition-all duration-300"
                      style={{ width: `${progress}%` }}
                    />
                  </div>
                  <div className="text-sm font-bold font-mono text-sky-400">
                    {progress}%
                  </div>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-6 py-4 border-t border-slate-800 flex items-center justify-between">
          {!exporting && !isCompleted ? (
            <>
              <button
                onClick={onClose}
                className="px-4 py-2 rounded-xl text-slate-400 hover:text-white transition"
              >
                取消
              </button>
              <button
                onClick={handleStartExport}
                className="px-6 py-2.5 bg-gradient-to-r from-sky-500 to-indigo-600 hover:from-sky-400 hover:to-indigo-500 text-white font-semibold rounded-xl text-xs shadow-lg shadow-sky-500/25 flex items-center gap-2 transition cursor-pointer"
              >
                <Zap className="w-4 h-4" />
                <span>立即开始渲染合成</span>
              </button>
            </>
          ) : isCompleted ? (
            <div className="flex items-center justify-end space-x-3 w-full">
              <button
                onClick={handleOpenFolder}
                className="px-4 py-2 bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-200 rounded-xl text-xs font-medium flex items-center gap-1.5 transition cursor-pointer"
              >
                <FolderOpen className="w-4 h-4 text-sky-400" />
                <span>在文件夹中定位</span>
              </button>
              <a
                href={outputUrl}
                download
                className="px-5 py-2 bg-emerald-600 hover:bg-emerald-500 text-white font-semibold rounded-xl text-xs shadow-lg shadow-emerald-600/20 flex items-center gap-1.5 transition cursor-pointer"
              >
                <Download className="w-4 h-4" />
                <span>下载成片</span>
              </a>
              <button
                onClick={onClose}
                className="px-4 py-2 rounded-xl text-slate-400 hover:text-white transition"
              >
                关闭
              </button>
            </div>
          ) : (
            <div className="text-xs text-slate-500 mx-auto">
              FFmpeg 正在后台分段剪裁与混流合成，请勿关闭窗口...
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
