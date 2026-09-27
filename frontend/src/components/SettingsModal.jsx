import React, { useState, useEffect } from 'react';
import { X, Save, Check, RefreshCw, Key, Link2, Cpu } from 'lucide-react';
import axios from 'axios';

export default function SettingsModal({ isOpen, onClose, systemStatus, onSettingsSaved }) {
  const [baseUrl, setBaseUrl] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [model, setModel] = useState('gemini-3.8-flash-high');
  const [concurrency, setConcurrency] = useState(4);
  const [ffmpegPath, setFfmpegPath] = useState('');
  const [loading, setLoading] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');

  useEffect(() => {
    if (isOpen) {
      axios.get('/api/settings').then(res => {
        const d = res.data;
        setBaseUrl(d.gemini_base_url || 'http://192.168.31.233:8317');
        setApiKey(d.gemini_api_key || 'sk-X3FATzGIIlF5Q7HQx');
        setModel(d.default_model || 'gemini-3.8-flash-high');
        setConcurrency(d.default_concurrency || 4);
        setFfmpegPath(d.custom_ffmpeg_path || '');
        setSaveSuccess(false);
        setErrorMsg('');
      }).catch(err => {
        setErrorMsg('获取设置失败: ' + err.message);
      });
    }
  }, [isOpen]);

  if (!isOpen) return null;

  const modelsList = systemStatus?.models || [
    { id: 'gemini-3.8-flash-high', name: 'Gemini 3.8 Flash (推荐)', desc: '高性能且速度极快' },
    { id: 'gemini-3.7-flash-high', name: 'Gemini 3.7 Flash', desc: '高画质理解' },
    { id: 'gemini-3-flash', name: 'Gemini 3 Flash', desc: '通用快速' },
    { id: 'gemini-pro-agent', name: 'Gemini 3.1 Pro (High)', desc: '深度推理' }
  ];

  const handleSave = async () => {
    setLoading(true);
    setErrorMsg('');
    try {
      await axios.post('/api/settings', {
        gemini_base_url: baseUrl.trim(),
        gemini_api_key: apiKey.trim(),
        default_model: model,
        default_concurrency: Number(concurrency) || 4,
        custom_ffmpeg_path: ffmpegPath.trim(),
      });
      setSaveSuccess(true);
      if (onSettingsSaved) onSettingsSaved();
      setTimeout(() => {
        setSaveSuccess(false);
        onClose();
      }, 1000);
    } catch (e) {
      setErrorMsg('保存设置失败: ' + (e.response?.data?.detail || e.message));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4">
      <div className="bg-slate-900 border border-slate-800 rounded-2xl w-full max-w-lg shadow-2xl overflow-hidden">
        {/* Modal Header */}
        <div className="px-6 py-4 border-b border-slate-800 flex items-center justify-between">
          <h2 className="text-base font-bold text-white flex items-center gap-2">
            配置与系统设置
          </h2>
          <button
            onClick={onClose}
            className="text-slate-400 hover:text-white p-1 rounded-lg hover:bg-slate-800 transition"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Modal Body */}
        <div className="p-6 space-y-4 text-sm max-h-[75vh] overflow-y-auto">
          {errorMsg && (
            <div className="p-3 bg-rose-500/10 border border-rose-500/30 text-rose-300 rounded-xl text-xs">
              {errorMsg}
            </div>
          )}

          {/* Gemini Base URL */}
          <div>
            <label className="block text-xs font-semibold text-slate-300 mb-1.5 flex items-center gap-1.5">
              <Link2 className="w-3.5 h-3.5 text-sky-400" />
              Gemini API Base URL
            </label>
            <input
              type="text"
              value={baseUrl}
              onChange={e => setBaseUrl(e.target.value)}
              placeholder="http://192.168.31.233:8317"
              className="w-full bg-slate-800 border border-slate-700 rounded-xl px-3.5 py-2 text-slate-100 placeholder-slate-500 focus:outline-none focus:border-sky-500"
            />
            <p className="text-[11px] text-slate-500 mt-1">本地局域网或官方 Gemini 代理端点地址</p>
          </div>

          {/* Gemini API Key */}
          <div>
            <label className="block text-xs font-semibold text-slate-300 mb-1.5 flex items-center gap-1.5">
              <Key className="w-3.5 h-3.5 text-amber-400" />
              Gemini API Key
            </label>
            <input
              type="text"
              value={apiKey}
              onChange={e => setApiKey(e.target.value)}
              placeholder="sk-..."
              className="w-full bg-slate-800 border border-slate-700 rounded-xl px-3.5 py-2 text-slate-100 placeholder-slate-500 focus:outline-none focus:border-sky-500 font-mono text-xs"
            />
            <p className="text-[11px] text-slate-500 mt-1">默认密钥: sk-X3FATzGIIlF5Q7HQx（已自动载入）</p>
          </div>

          {/* Default Model */}
          <div>
            <label className="block text-xs font-semibold text-slate-300 mb-1.5">
              默认分析模型 (Gemini Multimodal)
            </label>
            <select
              value={model}
              onChange={e => setModel(e.target.value)}
              className="w-full bg-slate-800 border border-slate-700 rounded-xl px-3.5 py-2 text-slate-100 focus:outline-none focus:border-sky-500"
            >
              {modelsList.map(m => (
                <option key={m.id} value={m.id}>
                  {m.name} - {m.desc}
                </option>
              ))}
            </select>
          </div>

          {/* Default Concurrency Threads */}
          <div>
            <label className="block text-xs font-semibold text-slate-300 mb-1.5 flex items-center justify-between">
              <span>默认粗剪并发线程数 (1-8 线程)</span>
              <span className="text-emerald-400 font-mono font-bold">{concurrency} 线程</span>
            </label>
            <div className="flex items-center gap-3">
              <input
                type="range"
                min="1"
                max="8"
                step="1"
                value={concurrency}
                onChange={e => setConcurrency(parseInt(e.target.value, 10))}
                className="w-full h-2 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-emerald-500"
              />
              <span className="text-xs font-mono text-slate-400 shrink-0 w-8 text-right">{concurrency}</span>
            </div>
            <p className="text-[11px] text-slate-500 mt-1">控制第一阶段多切片并行请求 Gemini 的并发窗口大小（默认 4）</p>
          </div>

          {/* FFmpeg custom path */}
          <div>
            <label className="block text-xs font-semibold text-slate-300 mb-1.5 flex items-center gap-1.5">
              <Cpu className="w-3.5 h-3.5 text-indigo-400" />
              自定义 FFmpeg 路径 (留空默认自动检测)
            </label>
            <input
              type="text"
              value={ffmpegPath}
              onChange={e => setFfmpegPath(e.target.value)}
              placeholder="C:\ffmpeg\bin\ffmpeg.exe (可选)"
              className="w-full bg-slate-800 border border-slate-700 rounded-xl px-3.5 py-2 text-slate-100 placeholder-slate-500 focus:outline-none focus:border-sky-500 text-xs font-mono"
            />
            <p className="text-[11px] text-slate-500 mt-1">
              当前系统状态: {systemStatus?.ffmpeg?.ffmpeg_installed ? `检测到 ${systemStatus.ffmpeg.ffmpeg_path}` : '未检测到系统 FFmpeg'}
            </p>
          </div>
        </div>

        {/* Modal Footer */}
        <div className="px-6 py-4 border-t border-slate-800 flex items-center justify-end space-x-3">
          <button
            onClick={onClose}
            className="px-4 py-2 rounded-xl text-slate-400 hover:text-slate-200 hover:bg-slate-800 transition"
          >
            取消
          </button>
          <button
            onClick={handleSave}
            disabled={loading}
            className={`px-5 py-2 rounded-xl font-medium text-white flex items-center gap-2 transition shadow-lg ${
              saveSuccess 
                ? 'bg-emerald-600 hover:bg-emerald-500' 
                : 'bg-sky-600 hover:bg-sky-500 shadow-sky-600/25'
            }`}
          >
            {loading ? (
              <RefreshCw className="w-4 h-4 animate-spin" />
            ) : saveSuccess ? (
              <Check className="w-4 h-4" />
            ) : (
              <Save className="w-4 h-4" />
            )}
            <span>{saveSuccess ? '已保存！' : '保存配置'}</span>
          </button>
        </div>
      </div>
    </div>
  );
}
