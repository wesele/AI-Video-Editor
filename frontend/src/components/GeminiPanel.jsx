import React, { useState, useEffect } from 'react';
import { Sparkles, Play, RefreshCw, Wand2, Compass, Gamepad2, BookOpen, Users, Sliders, AlertCircle } from 'lucide-react';
import axios from 'axios';

const styles = [
  { id: 'general', name: '通用剪辑', desc: '保留精彩高潮与对话，过渡段快进', icon: Wand2 },
  { id: 'vlog', name: 'Vlog 日常', desc: '保留生动互动特写，赶路转场快进', icon: Compass },
  { id: 'gaming', name: '游戏高光', desc: '保留击杀团战对决，跑图复活快进', icon: Gamepad2 },
  { id: 'tutorial', name: '网课教程', desc: '保留要点操作讲解，等待缓冲快进', icon: BookOpen },
  { id: 'meeting', name: '会议记录', desc: '保留关键发言决策，翻找材料快进', icon: Users },
];

export default function GeminiPanel({ videoMeta, onAnalysisComplete, onPartialUpdate, systemStatus }) {
  const [model, setModel] = useState(systemStatus?.settings?.default_model || 'gemini-3.8-flash-high');
  const [stylePreset, setStylePreset] = useState('general');
  const [customPrompt, setCustomPrompt] = useState('');
  const [defaultSpeed, setDefaultSpeed] = useState(4.0);
  const [concurrency, setConcurrency] = useState(systemStatus?.settings?.default_concurrency || 4);
  const [analyzing, setAnalyzing] = useState(false);
  const [progress, setProgress] = useState(0);
  const [statusText, setStatusText] = useState('');
  const [elapsedSec, setElapsedSec] = useState(0);
  const [streamedCount, setStreamedCount] = useState(0);
  const [errorMsg, setErrorMsg] = useState('');
  const [checkpointInfo, setCheckpointInfo] = useState(null);

  const fetchCheckpoint = async (vid) => {
    if (!vid) return;
    try {
      const res = await axios.get(`/api/video/analyze/checkpoint?video_id=${vid}`);
      if (res.data?.has_checkpoint && res.data.checkpoint?.video_id === vid) {
        setCheckpointInfo(res.data);
        if (res.data.checkpoint?.config) {
          const cfg = res.data.checkpoint.config;
          if (cfg.model) setModel(cfg.model);
          if (cfg.style_preset) setStylePreset(cfg.style_preset);
          if (cfg.custom_prompt !== undefined) setCustomPrompt(cfg.custom_prompt);
          if (cfg.default_fast_forward_speed) setDefaultSpeed(cfg.default_fast_forward_speed);
          if (cfg.concurrency) setConcurrency(cfg.concurrency);
        }
        if (res.data.partial_segments && res.data.partial_segments.length > 0) {
          setStreamedCount(res.data.partial_segments.length);
          if (onPartialUpdate) {
            onPartialUpdate(res.data.partial_segments);
          }
        }
        if (res.data.checkpoint.status === 'failed' && res.data.checkpoint.error_message) {
          setErrorMsg(res.data.checkpoint.error_message);
        }
      } else {
        setCheckpointInfo(null);
      }
    } catch (e) {
      console.warn('查询断点失败', e);
    }
  };

  useEffect(() => {
    if (videoMeta?.video_id) {
      fetchCheckpoint(videoMeta.video_id);
    } else {
      setCheckpointInfo(null);
    }
  }, [videoMeta?.video_id]);

  const formatTimer = (sec) => {
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  const handleStartAnalyze = async (resume = false) => {
    if (!videoMeta) return;
    setAnalyzing(true);
    setErrorMsg('');

    let initialPct = 0;
    let initialMsg = '正在初始化视频分析任务...';
    if (resume && checkpointInfo?.checkpoint?.stage1_completed_chunks) {
      const done = checkpointInfo.checkpoint.stage1_completed_chunks.length;
      const total = checkpointInfo.checkpoint.total_chunks || 1;
      initialPct = Math.round((done / total) * 75);
      initialMsg = `正在从断点恢复...（已复用前 ${done}/${total} 段事实）`;
    }

    setProgress(initialPct);
    setStatusText(initialMsg);
    setElapsedSec(0);

    // Start timer ticker
    const timerInterval = setInterval(() => {
      setElapsedSec(prev => prev + 1);
    }, 1000);

    try {
      const initRes = await axios.post('/api/video/analyze', {
        video_id: videoMeta.video_id,
        model: model,
        style_preset: stylePreset,
        custom_prompt: customPrompt,
        default_fast_forward_speed: defaultSpeed,
        concurrency: Number(concurrency) || 4,
        resume: resume,
      });

      const jobId = initRes.data.job_id;

      // Poll status every 1 second
      const pollInterval = setInterval(async () => {
        try {
          const checkRes = await axios.get(`/api/video/analyze/status/${jobId}`);
          const data = checkRes.data;
          
          if (data.status === 'processing') {
            setProgress(data.progress || 0);
            setStatusText(data.progress_message || 'AI 正在分析视频画面与音轨...');
            if (data.segments && data.segments.length > 0) {
              setStreamedCount(data.segments.length);
              if (onPartialUpdate) {
                onPartialUpdate(data.segments);
              }
            }
          } else if (data.status === 'success') {
            clearInterval(pollInterval);
            clearInterval(timerInterval);
            setProgress(100);
            setStatusText(data.progress_message || '分析完成！');
            setAnalyzing(false);
            setCheckpointInfo(null);
            onAnalysisComplete(data.segments);
          } else if (data.status === 'failed') {
            clearInterval(pollInterval);
            clearInterval(timerInterval);
            setAnalyzing(false);
            setErrorMsg(data.error || '分析失败');
            fetchCheckpoint(videoMeta.video_id);
          }
        } catch (pollErr) {
          clearInterval(pollInterval);
          clearInterval(timerInterval);
          setAnalyzing(false);
          setErrorMsg('获取分析状态失败: ' + pollErr.message);
          fetchCheckpoint(videoMeta.video_id);
        }
      }, 1000);

    } catch (e) {
      clearInterval(timerInterval);
      setAnalyzing(false);
      setErrorMsg('触发 AI 分析失败: ' + (e.response?.data?.detail || e.message));
      fetchCheckpoint(videoMeta.video_id);
    }
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 shadow-xl space-y-6">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-2 border-b border-slate-800 pb-4">
        <div>
          <h2 className="text-base font-bold text-white flex items-center gap-2">
            <Sparkles className="w-5 h-5 text-amber-400" />
            Gemini 智能剪辑配置
          </h2>
          <p className="text-xs text-slate-400 mt-0.5">选择剪辑风格并设定 AI 判断规则，一键提取精彩保留段与快进段</p>
        </div>

        {/* Model Select */}
        <div className="flex items-center space-x-2 text-xs">
          <span className="text-slate-400 font-medium">模型:</span>
          <select
            value={model}
            onChange={(e) => setModel(e.target.value)}
            disabled={analyzing}
            className="bg-slate-800 border border-slate-700 text-slate-200 rounded-xl px-3 py-1.5 focus:outline-none focus:border-sky-500 font-mono"
          >
            {(systemStatus?.models || [{ id: 'gemini-3.8-flash-high', name: 'Gemini 3.8 Flash' }]).map(m => (
              <option key={m.id} value={m.id}>{m.name}</option>
            ))}
          </select>
        </div>
      </div>

      {/* Style Presets */}
      <div>
        <label className="block text-xs font-semibold text-slate-300 mb-2.5">
          剪辑风格偏好
        </label>
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-5 gap-3">
          {styles.map(s => {
            const Icon = s.icon;
            const isSelected = stylePreset === s.id;
            return (
              <button
                key={s.id}
                onClick={() => setStylePreset(s.id)}
                disabled={analyzing}
                className={`p-3 rounded-xl border text-left transition flex flex-col justify-between cursor-pointer ${
                  isSelected
                    ? 'bg-sky-500/10 border-sky-500 text-sky-300 shadow-md shadow-sky-500/10'
                    : 'bg-slate-800/60 border-slate-800 hover:border-slate-700 text-slate-300'
                }`}
              >
                <div>
                  <Icon className={`w-5 h-5 mb-1.5 ${isSelected ? 'text-sky-400' : 'text-slate-400'}`} />
                  <div className="font-semibold text-xs text-white">{s.name}</div>
                </div>
                <div className="text-[11px] text-slate-400 mt-1 leading-snug line-clamp-2">{s.desc}</div>
              </button>
            );
          })}
        </div>
      </div>

      {/* Speed, Concurrency & Custom Prompt Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-4 gap-4">
        {/* Default Fast Forward Speed */}
        <div className="bg-slate-800/40 border border-slate-800 p-4 rounded-xl space-y-3">
          <label className="block text-xs font-semibold text-slate-300 flex items-center justify-between">
            <span className="flex items-center gap-1.5">
              <Sliders className="w-3.5 h-3.5 text-sky-400" /> 默认快进倍速
            </span>
            <span className="text-sky-400 font-bold font-mono">{defaultSpeed}x 倍速</span>
          </label>
          <div className="grid grid-cols-4 gap-2">
            {[2.0, 4.0, 8.0, 16.0].map(spd => (
              <button
                key={spd}
                type="button"
                onClick={() => setDefaultSpeed(spd)}
                disabled={analyzing}
                className={`py-1.5 rounded-lg text-xs font-mono font-medium border transition cursor-pointer ${
                  defaultSpeed === spd
                    ? 'bg-sky-500 text-white border-sky-400'
                    : 'bg-slate-800 border-slate-700 text-slate-300 hover:border-slate-600'
                }`}
              >
                {spd}x
              </button>
            ))}
          </div>
          <p className="text-[11px] text-slate-500">无趣片段默认按该倍速快进</p>
        </div>

        {/* Concurrency Threads (1-8) */}
        <div className="bg-slate-800/40 border border-slate-800 p-4 rounded-xl space-y-3">
          <label className="block text-xs font-semibold text-slate-300 flex items-center justify-between">
            <span className="flex items-center gap-1.5">
              <Sparkles className="w-3.5 h-3.5 text-emerald-400" /> 粗剪并发线程 (1-8)
            </span>
            <span className="text-emerald-400 font-bold font-mono">{concurrency} 线程</span>
          </label>
          <div className="grid grid-cols-4 gap-2">
            {[1, 2, 4, 8].map(th => (
              <button
                key={th}
                type="button"
                onClick={() => setConcurrency(th)}
                disabled={analyzing}
                className={`py-1.5 rounded-lg text-xs font-mono font-medium border transition cursor-pointer ${
                  concurrency === th
                    ? 'bg-emerald-500 text-white border-emerald-400'
                    : 'bg-slate-800 border-slate-700 text-slate-300 hover:border-slate-600'
                }`}
              >
                {th}线程
              </button>
            ))}
          </div>
          <div className="flex items-center gap-2 pt-0.5">
            <input
              type="range"
              min="1"
              max="8"
              step="1"
              value={concurrency}
              onChange={(e) => setConcurrency(parseInt(e.target.value, 10))}
              disabled={analyzing}
              className="w-full h-1.5 bg-slate-700 rounded-lg appearance-none cursor-pointer accent-emerald-500"
            />
            <span className="text-[11px] font-mono text-slate-400 shrink-0">{concurrency}/8</span>
          </div>
        </div>

        {/* Custom Instructions */}
        <div className="sm:col-span-2 md:col-span-2 bg-slate-800/40 border border-slate-800 p-4 rounded-xl space-y-2">
          <label className="block text-xs font-semibold text-slate-300">
            补充自定义指示 (可选)
          </label>
          <input
            type="text"
            value={customPrompt}
            onChange={(e) => setCustomPrompt(e.target.value)}
            disabled={analyzing}
            placeholder="例如：重点保留有人声说话的段落，长时间无声走路一律快进..."
            className="w-full bg-slate-900 border border-slate-700 rounded-xl px-3.5 py-2.5 text-xs text-slate-100 placeholder-slate-500 focus:outline-none focus:border-sky-500"
          />
          <p className="text-[11px] text-slate-500">AI 将严格结合本指示与风格偏好对画面做出裁切判定</p>
        </div>
      </div>

      {errorMsg && (
        <div className="p-3.5 bg-rose-500/10 border border-rose-500/20 text-rose-300 rounded-xl text-xs flex items-start gap-2.5">
          <AlertCircle className="w-4 h-4 text-rose-400 shrink-0 mt-0.5" />
          <div>
            <div className="font-semibold text-rose-200">粗剪处理中断</div>
            <div className="text-rose-300/90 mt-0.5">{errorMsg}</div>
            <div className="text-slate-400 text-[11px] mt-1">已分析成功的前序分段已完整保留在下方时间轴，您可以直接点击「继续处理」断点续跑。</div>
          </div>
        </div>
      )}

      {/* Start Button & Real-Time Progress Container */}
      <div className="space-y-4 pt-2">
        {analyzing ? (
          <div className="bg-slate-950/80 border border-sky-500/30 rounded-2xl p-5 shadow-2xl space-y-4 animate-in fade-in duration-300">
            {/* Header: Stage and Timer */}
            <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
              <div className="flex items-center space-x-2">
                <span className="relative flex h-3 w-3">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-sky-400 opacity-75"></span>
                  <span className="relative inline-flex rounded-full h-3 w-3 bg-sky-500"></span>
                </span>
                <span className="font-bold text-white tracking-wide">
                  AI 智能分析中
                </span>
                <span className="text-slate-500">|</span>
                <span className="text-sky-300 font-mono font-medium">
                  {statusText}
                </span>
              </div>
              <div className="flex items-center space-x-3">
                <span className="bg-slate-800 text-slate-300 border border-slate-700 px-2.5 py-1 rounded-lg font-mono text-[11px]">
                  已耗时: <strong className="text-white">{formatTimer(elapsedSec)}</strong>
                </span>
                <span className="text-sm font-bold font-mono text-sky-400">
                  {progress}%
                </span>
              </div>
            </div>

            {/* Smooth Progress Bar */}
            <div className="w-full bg-slate-800 rounded-full h-2.5 overflow-hidden shadow-inner">
              <div
                className="bg-gradient-to-r from-sky-500 via-indigo-500 to-emerald-400 h-full transition-all duration-300 ease-out"
                style={{ width: `${Math.max(2, progress)}%` }}
              />
            </div>

            {/* 3-Step Pipeline Stage Indicator */}
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-[11px] pt-1">
              <div className="p-2.5 rounded-xl border bg-slate-900/80 border-slate-800 text-slate-300 flex items-center justify-between">
                <span>1. 分段事实提取</span>
                <span className="text-sky-400 font-mono text-[10px] bg-sky-500/10 px-1.5 py-0.5 rounded">
                  {streamedCount > 0 ? `${streamedCount}段事实` : '3分钟/片'}
                </span>
              </div>
              <div className="p-2.5 rounded-xl border bg-slate-900/80 border-slate-800 text-slate-300 flex items-center justify-between">
                <span>2. 全局宏观研读</span>
                <span className={`text-[10px] font-mono px-1.5 py-0.5 rounded ${
                  progress >= 75 ? 'text-indigo-400 bg-indigo-500/10 font-bold' : 'text-slate-500 bg-slate-800'
                }`}>
                  {progress >= 75 ? '全局通盘' : '等待汇总'}
                </span>
              </div>
              <div className="p-2.5 rounded-xl border bg-slate-900/80 border-slate-800 text-slate-300 flex items-center justify-between">
                <span>3. 1-10分价值曲线</span>
                <span className={`text-[10px] font-mono px-1.5 py-0.5 rounded ${
                  progress >= 95 ? 'text-emerald-400 bg-emerald-500/10 font-bold' : 'text-slate-500 bg-slate-800'
                }`}>
                  {progress >= 95 ? '已生成' : '计算中'}
                </span>
              </div>
              <div className="p-2.5 rounded-xl border bg-slate-900/80 border-slate-800 text-slate-300 flex items-center justify-between">
                <span>4. 动态阈值交互</span>
                <span className={`text-[10px] font-mono px-1.5 py-0.5 rounded ${
                  progress >= 100 ? 'text-emerald-400 bg-emerald-500/10 font-bold' : 'text-slate-500 bg-slate-800'
                }`}>
                  {progress >= 100 ? '随时可调' : '就绪待用'}
                </span>
              </div>
            </div>
          </div>
        ) : (
          <div>
            {checkpointInfo?.has_checkpoint && checkpointInfo.checkpoint?.status !== 'success' ? (
              <div className="flex flex-col sm:flex-row items-center justify-between gap-4 p-4 rounded-xl bg-slate-950/60 border border-sky-500/20">
                <div className="text-xs text-slate-300 space-y-1">
                  <div className="flex items-center gap-1.5 text-sky-400 font-semibold">
                    <RefreshCw className="w-3.5 h-3.5" />
                    <span>检测到可恢复的粗剪断点</span>
                  </div>
                  <div className="text-slate-400 text-[11px]">
                    已完成前 {checkpointInfo.checkpoint.stage1_completed_chunks?.length || 0}/{checkpointInfo.checkpoint.total_chunks || 0} 段分析（已在下方时间轴载入 {checkpointInfo.partial_segments?.length || 0} 个事件）
                  </div>
                </div>

                <div className="flex items-center gap-3 w-full sm:w-auto">
                  <button
                    onClick={() => handleStartAnalyze(false)}
                    disabled={analyzing}
                    className="px-4 py-2.5 bg-slate-800 hover:bg-slate-700 text-slate-300 hover:text-white font-medium rounded-xl text-xs transition border border-slate-700 cursor-pointer"
                  >
                    重新开始
                  </button>

                  <button
                    onClick={() => handleStartAnalyze(true)}
                    disabled={analyzing}
                    className="px-6 py-2.5 bg-gradient-to-r from-emerald-500 to-sky-600 hover:from-emerald-400 hover:to-sky-500 text-white font-semibold rounded-xl text-xs shadow-lg shadow-emerald-500/20 flex items-center justify-center space-x-2 transition cursor-pointer"
                  >
                    <RefreshCw className="w-3.5 h-3.5" />
                    <span>
                      {checkpointInfo.checkpoint.failed_stage === 'stage2'
                        ? '重试第二阶段（全局宏观研读）'
                        : `继续处理（从第 ${(checkpointInfo.checkpoint.stage1_completed_chunks?.length || 0) + 1} 段继续）`}
                    </span>
                  </button>
                </div>
              </div>
            ) : (
              <div className="flex flex-col sm:flex-row items-center justify-between gap-4">
                <div className="text-xs text-slate-400">
                  点击后 Gemini 将自动研读整段视频并生成连续的分段剪辑方案（支持超长视频秒级精准切片与断点续跑）
                </div>

                <button
                  onClick={() => handleStartAnalyze(false)}
                  disabled={analyzing}
                  className="w-full sm:w-auto px-6 py-3 bg-gradient-to-r from-sky-500 to-indigo-600 hover:from-sky-400 hover:to-indigo-500 text-white font-semibold rounded-xl text-sm shadow-lg shadow-sky-500/25 flex items-center justify-center space-x-2 transition disabled:opacity-50 cursor-pointer"
                >
                  <Sparkles className="w-4 h-4" />
                  <span>开始 Gemini 智能粗剪</span>
                </button>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
