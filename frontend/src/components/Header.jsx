import React from 'react';
import { Video, Cpu, Sparkles, Settings, CheckCircle2, AlertCircle } from 'lucide-react';

export default function Header({ systemStatus, onOpenSettings }) {
  const ffmpegOk = systemStatus?.ffmpeg?.ffmpeg_installed;
  const ffmpegVer = systemStatus?.ffmpeg?.ffmpeg_version || '';
  const recommendedEnc = systemStatus?.ffmpeg?.recommended_encoder || 'cpu';
  const currentModel = systemStatus?.settings?.default_model || 'gemini-3.8-flash-high';

  const encLabelMap = {
    qsv: 'Intel QSV 加速',
    nvenc: 'NVIDIA NVENC 加速',
    amf: 'AMD AMF 加速',
    cpu: 'CPU 软解软编'
  };

  return (
    <header className="border-b border-slate-800 bg-slate-900/80 backdrop-blur sticky top-0 z-30 px-6 py-3.5 flex items-center justify-between">
      <div className="flex items-center space-x-3">
        <div className="bg-gradient-to-tr from-sky-500 to-indigo-600 p-2 rounded-xl text-white shadow-lg shadow-sky-500/20">
          <Video className="w-6 h-6" />
        </div>
        <div>
          <div className="flex items-center space-x-2">
            <h1 className="text-lg font-bold tracking-tight text-white">SmartVideo</h1>
            <span className="text-xs bg-sky-500/10 text-sky-400 border border-sky-500/20 px-2 py-0.5 rounded-full font-medium flex items-center gap-1">
              <Sparkles className="w-3 h-3" /> Gemini 驱动
            </span>
          </div>
          <p className="text-xs text-slate-400">智能多模态视频粗剪与快进合成引擎</p>
        </div>
      </div>

      <div className="flex items-center space-x-3 text-xs">
        {/* FFmpeg status badge */}
        <div className={`flex items-center space-x-1.5 px-3 py-1.5 rounded-lg border ${
          ffmpegOk 
            ? 'bg-emerald-500/10 border-emerald-500/20 text-emerald-300' 
            : 'bg-rose-500/10 border-rose-500/20 text-rose-300'
        }`}>
          {ffmpegOk ? <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" /> : <AlertCircle className="w-3.5 h-3.5 text-rose-400" />}
          <span>FFmpeg {ffmpegOk ? (ffmpegVer ? `v${ffmpegVer}` : '就绪') : '未检测到'}</span>
        </div>

        {/* Hardware Acceleration badge */}
        <div className="flex items-center space-x-1.5 px-3 py-1.5 rounded-lg border bg-indigo-500/10 border-indigo-500/20 text-indigo-300">
          <Cpu className="w-3.5 h-3.5 text-indigo-400" />
          <span>{encLabelMap[recommendedEnc] || '硬件加速'}</span>
        </div>

        {/* Current Gemini Model */}
        <div className="hidden md:flex items-center space-x-1.5 px-3 py-1.5 rounded-lg border bg-slate-800 border-slate-700 text-slate-300">
          <Sparkles className="w-3.5 h-3.5 text-amber-400" />
          <span className="font-mono">{currentModel.replace('models/', '')}</span>
        </div>

        {/* Settings Button */}
        <button
          onClick={onOpenSettings}
          className="flex items-center space-x-1.5 px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-700 transition cursor-pointer"
          title="系统与 Gemini API 设置"
        >
          <Settings className="w-4 h-4 text-slate-400" />
          <span>设置</span>
        </button>
      </div>
    </header>
  );
}
