import React, { useState, useRef, useEffect } from 'react';
import { Play, Pause, Volume2, VolumeX, Scissors, Wand2, Eye, RotateCcw, Sliders, Sparkles, TrendingUp, Check, Zap } from 'lucide-react';

export default function TimelinePlayer({
  videoMeta,
  segments,
  selectedSegmentId,
  onSelectSegment,
  onSplitSegment,
  scoreThreshold = 6.0,
  onThresholdChange,
  belowThresholdSpeed = 4.0,
  onBelowSpeedChange,
  deleteLowScore = true,
  onDeleteLowToggle,
}) {
  const videoRef = useRef(null);
  const timelineRef = useRef(null);
  
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentTime, setCurrentTime] = useState(0);
  const [isMuted, setIsMuted] = useState(false);
  const [simulatePreview, setSimulatePreview] = useState(false);
  const [activeSpeed, setActiveSpeed] = useState(1.0);
  const [hoverInfo, setHoverInfo] = useState(null);

  const duration = videoMeta?.duration || 1;

  const formatTime = (sec) => {
    if (!sec || isNaN(sec)) return '00:00.0';
    const m = Math.floor(sec / 60);
    const s = (sec % 60).toFixed(1);
    return `${m.toString().padStart(2, '0')}:${parseFloat(s) < 10 ? '0' : ''}${s}`;
  };

  // Find active segment for a given timestamp
  const findSegmentAt = (time) => {
    return segments.find(s => time >= s.start_time && time < s.end_time);
  };

  // Video time update handler
  const handleTimeUpdate = () => {
    if (!videoRef.current) return;
    const t = videoRef.current.currentTime;
    setCurrentTime(t);

    if (simulatePreview) {
      const curSeg = findSegmentAt(t);
      if (curSeg) {
        if (curSeg.action === 'delete') {
          // Skip past deleted segment
          videoRef.current.currentTime = Math.min(curSeg.end_time + 0.05, duration);
        } else if (curSeg.action === 'fast_forward') {
          const spd = Math.min(16, Math.max(0.5, curSeg.speed || 4.0));
          if (videoRef.current.playbackRate !== spd) {
            videoRef.current.playbackRate = spd;
            setActiveSpeed(spd);
          }
        } else {
          // keep
          if (videoRef.current.playbackRate !== 1.0) {
            videoRef.current.playbackRate = 1.0;
            setActiveSpeed(1.0);
          }
        }
      }
    } else {
      if (videoRef.current.playbackRate !== 1.0) {
        videoRef.current.playbackRate = 1.0;
        setActiveSpeed(1.0);
      }
    }
  };

  const togglePlay = () => {
    if (!videoRef.current) return;
    if (videoRef.current.paused) {
      videoRef.current.play();
      setIsPlaying(true);
    } else {
      videoRef.current.pause();
      setIsPlaying(false);
    }
  };

  const handleSeek = (e) => {
    if (!timelineRef.current || !videoRef.current) return;
    const rect = timelineRef.current.getBoundingClientRect();
    const clickX = e.clientX - rect.left;
    const pct = Math.max(0, Math.min(1, clickX / rect.width));
    const newTime = pct * duration;
    videoRef.current.currentTime = newTime;
    setCurrentTime(newTime);
  };

  const jumpToSegment = (seg) => {
    if (!videoRef.current) return;
    videoRef.current.currentTime = seg.start_time;
    setCurrentTime(seg.start_time);
    onSelectSegment(seg.id);
  };

  const handleSplitClick = () => {
    onSplitSegment(currentTime);
  };

  // Color mapping for segment actions
  const getActionColor = (action, isSelected) => {
    if (action === 'keep') {
      return isSelected 
        ? 'bg-emerald-500 shadow-lg shadow-emerald-500/30 ring-2 ring-white ring-offset-1 ring-offset-slate-900' 
        : 'bg-emerald-600/90 hover:bg-emerald-500';
    } else if (action === 'fast_forward') {
      return isSelected 
        ? 'bg-amber-500 shadow-lg shadow-amber-500/30 ring-2 ring-white ring-offset-1 ring-offset-slate-900' 
        : 'bg-amber-600/90 hover:bg-amber-500';
    } else {
      // delete
      return isSelected 
        ? 'bg-slate-700 opacity-60 line-through ring-2 ring-rose-400' 
        : 'bg-slate-800/80 hover:bg-slate-700/80 opacity-50';
    }
  };

  // Calculate real-time output duration & stats based on current threshold
  let totalEstimatedOutputSec = 0;
  let keepCount = 0;
  let ffCount = 0;
  let delCount = 0;
  let keepDur = 0;
  let ffDur = 0;
  let delDur = 0;

  segments.forEach(s => {
    const spd = s.speed || 4.0;
    if (s.action === 'keep') {
      keepCount++;
      keepDur += s.duration;
      totalEstimatedOutputSec += s.duration;
    } else if (s.action === 'fast_forward') {
      ffCount++;
      ffDur += s.duration;
      totalEstimatedOutputSec += (s.duration / spd);
    } else {
      delCount++;
      delDur += s.duration;
    }
  });

  const compressionRatio = duration > 0 
    ? Math.round((totalEstimatedOutputSec / duration) * 100) 
    : 100;

  // Map 1.0 - 10.0 score to SVG Y (Y: 10 down to 70)
  const yFromScore = (sc) => {
    const num = Math.max(1.0, Math.min(10.0, Number(sc) || 5.0));
    return 70 - ((num - 1.0) / 9.0) * 58;
  };
  const threshY = yFromScore(scoreThreshold);

  const handleTimelineMouseMove = (e) => {
    if (!timelineRef.current) return;
    const rect = timelineRef.current.getBoundingClientRect();
    const clickX = e.clientX - rect.left;
    const pct = Math.max(0, Math.min(1, clickX / rect.width));
    const hoverTime = pct * duration;
    const hoverSeg = findSegmentAt(hoverTime);
    setHoverInfo({
      x: clickX,
      pct: pct * 100,
      time: hoverTime,
      seg: hoverSeg
    });
  };

  const handleTimelineMouseLeave = () => setHoverInfo(null);

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 shadow-xl space-y-5">
      {/* Top Bar: Preview Mode Toggle & Controls */}
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-800 pb-3">
        <div className="flex items-center space-x-2">
          <button
            onClick={togglePlay}
            className="p-2 rounded-xl bg-sky-500 hover:bg-sky-400 text-white transition shadow-md shadow-sky-500/20 cursor-pointer"
          >
            {isPlaying ? <Pause className="w-5 h-5" /> : <Play className="w-5 h-5 ml-0.5" />}
          </button>
          <div className="text-xs font-mono text-slate-300">
            <span className="text-white font-bold">{formatTime(currentTime)}</span> / {formatTime(duration)}
          </div>
          {simulatePreview && activeSpeed !== 1.0 && (
            <span className="text-xs bg-amber-500/20 text-amber-300 border border-amber-500/30 px-2 py-0.5 rounded font-mono font-bold animate-pulse">
              快进模拟: {activeSpeed}x
            </span>
          )}
        </div>

        <div className="flex items-center space-x-3">
          {/* Split at playhead */}
          <button
            onClick={handleSplitClick}
            className="flex items-center space-x-1.5 px-3 py-1.5 bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-200 rounded-xl text-xs font-medium transition cursor-pointer"
            title="在播放头位置切分当前片段"
          >
            <Scissors className="w-3.5 h-3.5 text-sky-400" />
            <span>当前帧裁切 (Split)</span>
          </button>

          {/* Simulated Preview Mode Toggle */}
          <button
            onClick={() => setSimulatePreview(!simulatePreview)}
            className={`flex items-center space-x-1.5 px-3 py-1.5 rounded-xl text-xs font-semibold transition border cursor-pointer ${
              simulatePreview
                ? 'bg-gradient-to-r from-emerald-600 to-teal-600 text-white border-emerald-400/30 shadow-lg shadow-emerald-600/20'
                : 'bg-slate-800 text-slate-300 border-slate-700 hover:bg-slate-700'
            }`}
          >
            <Eye className="w-3.5 h-3.5" />
            <span>{simulatePreview ? '✨ 模拟成片预览中 (跳删+变倍速)' : '常规原片预览'}</span>
          </button>
        </div>
      </div>

      {/* Video Container */}
      <div className="relative rounded-xl overflow-hidden bg-black aspect-video max-h-[440px] flex items-center justify-center border border-slate-800">
        <video
          ref={videoRef}
          src={videoMeta?.stream_url}
          onTimeUpdate={handleTimeUpdate}
          onPlay={() => setIsPlaying(true)}
          onPause={() => setIsPlaying(false)}
          className="w-full h-full object-contain"
        />
      </div>

      {/* Step 3: Interactive Score Threshold Controller Card */}
      <div className="bg-slate-950/70 border border-slate-800/90 rounded-xl p-4 shadow-lg space-y-3">
        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-3 border-b border-slate-800/80 pb-3">
          <div className="space-y-0.5">
            <div className="flex items-center space-x-2">
              <TrendingUp className="w-4 h-4 text-sky-400" />
              <h3 className="text-sm font-bold text-white tracking-wide">
                全局价值度曲线与动态阈值决策
              </h3>
              <span className="text-[10px] bg-sky-500/15 text-sky-300 border border-sky-500/30 px-2 py-0.5 rounded-full font-mono">
                第三步 交互测算
              </span>
            </div>
            <p className="text-xs text-slate-400">
              拖动滑块调节保留阈值，全片分段毫秒级自动重划动作与重新测算成片总时长
            </p>
          </div>

          {/* Real-time Calculated Stats Banner */}
          <div className="flex items-center flex-wrap gap-2.5 bg-slate-900/90 border border-slate-800 px-3.5 py-1.5 rounded-xl text-xs font-mono">
            <div>
              <span className="text-slate-400 text-[10px] block">预计成片时长</span>
              <span className="text-emerald-400 font-bold text-sm">{formatTime(totalEstimatedOutputSec)}</span>
              <span className="text-[10px] text-slate-500 ml-1">({compressionRatio}%)</span>
            </div>
            <div className="h-6 w-[1px] bg-slate-800" />
            <div className="text-[11px] space-y-0.5">
              <div className="flex items-center gap-2">
                <span className="text-emerald-400">保留: {keepCount}段 ({Math.round(keepDur)}s)</span>
                <span className="text-amber-400">快进: {ffCount}段 ({Math.round(ffDur)}s)</span>
                {delCount > 0 && <span className="text-rose-400">删除: {delCount}段</span>}
              </div>
            </div>
          </div>
        </div>

        {/* Controls Row: Slider, Presets, FF Speed, Delete Toggle */}
        <div className="grid grid-cols-1 md:grid-cols-12 gap-3 items-center text-xs">
          {/* Threshold Slider */}
          <div className="md:col-span-5 flex items-center space-x-3 bg-slate-900/80 border border-slate-800/80 px-3.5 py-2 rounded-xl">
            <span className="text-slate-300 font-medium shrink-0">保留阈值:</span>
            <input
              type="range"
              min="1.0"
              max="9.5"
              step="0.5"
              value={scoreThreshold}
              onChange={(e) => onThresholdChange && onThresholdChange(parseFloat(e.target.value))}
              className="w-full accent-sky-400 cursor-pointer h-1.5 bg-slate-800 rounded-lg"
            />
            <span className="font-mono font-bold text-sky-300 bg-sky-500/20 border border-sky-500/40 px-2 py-0.5 rounded text-xs shrink-0">
              {scoreThreshold.toFixed(1)} 分
            </span>
          </div>

          {/* Preset Buttons */}
          <div className="md:col-span-4 flex items-center space-x-1.5">
            <button
              onClick={() => onThresholdChange && onThresholdChange(7.5)}
              className={`px-2.5 py-1.5 rounded-lg text-xs font-medium transition cursor-pointer border ${
                scoreThreshold === 7.5
                  ? 'bg-sky-500 text-white border-sky-400 shadow-sm'
                  : 'bg-slate-900 border-slate-800 text-slate-400 hover:text-slate-200'
              }`}
            >
              精简高潮 (7.5)
            </button>
            <button
              onClick={() => onThresholdChange && onThresholdChange(6.0)}
              className={`px-2.5 py-1.5 rounded-lg text-xs font-medium transition cursor-pointer border ${
                scoreThreshold === 6.0
                  ? 'bg-sky-500 text-white border-sky-400 shadow-sm'
                  : 'bg-slate-900 border-slate-800 text-slate-400 hover:text-slate-200'
              }`}
            >
              标准推荐 (6.0)
            </button>
            <button
              onClick={() => onThresholdChange && onThresholdChange(4.5)}
              className={`px-2.5 py-1.5 rounded-lg text-xs font-medium transition cursor-pointer border ${
                scoreThreshold === 4.5
                  ? 'bg-sky-500 text-white border-sky-400 shadow-sm'
                  : 'bg-slate-900 border-slate-800 text-slate-400 hover:text-slate-200'
              }`}
            >
              饱满铺垫 (4.5)
            </button>
          </div>

          {/* Below Threshold Speed & Low Score Deletion */}
          <div className="md:col-span-3 flex items-center justify-end space-x-3">
            <div className="flex items-center space-x-1.5">
              <span className="text-slate-400 text-[11px]">快进:</span>
              <select
                value={belowThresholdSpeed}
                onChange={(e) => onBelowSpeedChange && onBelowSpeedChange(parseFloat(e.target.value))}
                className="bg-slate-900 border border-slate-700 text-amber-300 font-mono font-bold rounded-lg px-2 py-1 text-xs focus:outline-none"
              >
                <option value={2.0}>2x</option>
                <option value={4.0}>4x</option>
                <option value={8.0}>8x</option>
                <option value={16.0}>16x</option>
              </select>
            </div>

            <label className="flex items-center space-x-1.5 cursor-pointer text-slate-300 select-none text-[11px]">
              <input
                type="checkbox"
                checked={deleteLowScore}
                onChange={(e) => onDeleteLowToggle && onDeleteLowToggle(e.target.checked)}
                className="rounded accent-rose-500 cursor-pointer"
              />
              <span>≤2分丢弃</span>
            </label>
          </div>
        </div>
      </div>

      {/* SVG Value Curve Chart & Multi-color Timeline Track */}
      <div className="space-y-1 pt-1">
        <div className="flex items-center justify-between text-xs text-slate-400 pb-1">
          <div className="flex items-center space-x-2">
            <span className="font-semibold text-slate-300">价值度曲线 (1-10分) 与总览时间轴</span>
            <span className="text-[11px] text-slate-500 font-mono">
              [高于 {scoreThreshold.toFixed(1)} 分绿区保留 1x，低于快进/删除]
            </span>
          </div>
          <div className="flex items-center space-x-4 text-[11px]">
            <span className="flex items-center gap-1">
              <span className="w-2.5 h-2.5 rounded-sm bg-emerald-500"></span> ≥{scoreThreshold.toFixed(1)}分 (1x保留)
            </span>
            <span className="flex items-center gap-1">
              <span className="w-2.5 h-2.5 rounded-sm bg-amber-500"></span> &lt;{scoreThreshold.toFixed(1)}分 ({belowThresholdSpeed}x快进)
            </span>
            <span className="flex items-center gap-1">
              <span className="w-2.5 h-2.5 rounded-sm bg-slate-700 border border-slate-600"></span> ≤2分 (丢弃)
            </span>
          </div>
        </div>

        {/* Unified Timeline Container */}
        <div
          ref={timelineRef}
          onClick={handleSeek}
          onMouseMove={handleTimelineMouseMove}
          onMouseLeave={handleTimelineMouseLeave}
          className="relative w-full rounded-2xl overflow-hidden border border-slate-800 bg-slate-950 cursor-pointer shadow-2xl select-none group"
        >
          {/* 1. SVG Value Curve Area Chart */}
          <div className="relative w-full h-24 bg-slate-950 border-b border-slate-800/80">
            <svg
              className="w-full h-full"
              viewBox="0 0 1000 80"
              preserveAspectRatio="none"
            >
              <defs>
                <linearGradient id="curveGradKeep" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#10b981" stopOpacity="0.45" />
                  <stop offset="100%" stopColor="#10b981" stopOpacity="0.05" />
                </linearGradient>
                <linearGradient id="curveGradFF" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#f59e0b" stopOpacity="0.35" />
                  <stop offset="100%" stopColor="#f59e0b" stopOpacity="0.05" />
                </linearGradient>
                <linearGradient id="curveGradDel" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#ef4444" stopOpacity="0.25" />
                  <stop offset="100%" stopColor="#ef4444" stopOpacity="0.0" />
                </linearGradient>
              </defs>

              {/* Horizontal Reference Grids */}
              {/* Score 8.0: High energy */}
              <line x1="0" y1={yFromScore(8.0)} x2="1000" y2={yFromScore(8.0)} stroke="#334155" strokeWidth="0.8" strokeDasharray="3 3" />
              <text x="6" y={yFromScore(8.0) - 2} fill="#64748b" fontSize="9" fontFamily="monospace">8.0分 高能</text>

              {/* Score 5.0: Mid level */}
              <line x1="0" y1={yFromScore(5.0)} x2="1000" y2={yFromScore(5.0)} stroke="#1e293b" strokeWidth="0.8" strokeDasharray="3 3" />
              <text x="6" y={yFromScore(5.0) - 2} fill="#475569" fontSize="9" fontFamily="monospace">5.0分 过渡</text>

              {/* Score 2.0: Low waste line */}
              <line x1="0" y1={yFromScore(2.0)} x2="1000" y2={yFromScore(2.0)} stroke="#1e293b" strokeWidth="0.8" strokeDasharray="3 3" />
              <text x="6" y={yFromScore(2.0) - 2} fill="#475569" fontSize="9" fontFamily="monospace">2.0分 冗余</text>

              {/* Render Value Curve Segments & Area */}
              {segments.map((s, sIdx) => {
                const x1 = (s.start_time / duration) * 1000;
                const x2 = (s.end_time / duration) * 1000;
                const sc = Number(s.score) || 5.0;
                const y = yFromScore(sc);
                const isKeep = sc >= scoreThreshold;
                const isDel = deleteLowScore && sc <= 2.0;

                const gradId = isKeep ? "url(#curveGradKeep)" : (isDel ? "url(#curveGradDel)" : "url(#curveGradFF)");
                const strokeColor = isKeep ? "#10b981" : (isDel ? "#ef4444" : "#f59e0b");

                return (
                  <g key={`curve-${s.id || sIdx}`}>
                    {/* Filled Area below curve */}
                    <polygon
                      points={`${x1},75 ${x1},${y} ${x2},${y} ${x2},75`}
                      fill={gradId}
                    />
                    {/* Top Stroke for this segment */}
                    <line
                      x1={x1}
                      y1={y}
                      x2={x2}
                      y2={y}
                      stroke={strokeColor}
                      strokeWidth="2.5"
                    />
                    {/* Connector line to next segment */}
                    {sIdx < segments.length - 1 && (
                      <line
                        x1={x2}
                        y1={y}
                        x2={x2}
                        y2={yFromScore(segments[sIdx + 1]?.score || 5.0)}
                        stroke={strokeColor}
                        strokeWidth="1.5"
                        strokeDasharray="1 1"
                        opacity="0.6"
                      />
                    )}
                  </g>
                );
              })}

              {/* Dynamic Horizontal Threshold Line */}
              <line
                x1="0"
                y1={threshY}
                x2="1000"
                y2={threshY}
                stroke="#38bdf8"
                strokeWidth="2"
                strokeDasharray="6 3"
              />
              <text
                x="992"
                y={Math.max(14, threshY - 4)}
                fill="#38bdf8"
                fontSize="10"
                fontFamily="monospace"
                fontWeight="bold"
                textAnchor="end"
              >
                保留阈值: {scoreThreshold.toFixed(1)}分
              </text>
            </svg>

            {/* Hover Tooltip on Curve */}
            {hoverInfo && hoverInfo.seg && (
              <div
                className="absolute top-1 pointer-events-none z-30 bg-slate-900/95 border border-sky-500/50 px-2.5 py-1.5 rounded-lg shadow-xl text-xs font-mono backdrop-blur-sm transform -translate-x-1/2"
                style={{ left: `${hoverInfo.pct}%` }}
              >
                <div className="flex items-center space-x-2">
                  <span className="text-white font-bold">{formatTime(hoverInfo.time)}</span>
                  <span className={`px-1.5 py-0.2 rounded font-bold text-[11px] ${
                    (hoverInfo.seg.score || 5) >= scoreThreshold ? 'bg-emerald-500/20 text-emerald-300' : 'bg-amber-500/20 text-amber-300'
                  }`}>
                    价值度: {(Number(hoverInfo.seg.score) || 5.0).toFixed(1)}分
                  </span>
                </div>
                <div className="text-[10px] text-slate-400 truncate max-w-[200px] mt-0.5">
                  {hoverInfo.seg.summary || hoverInfo.seg.reason || '剪辑片段'}
                </div>
              </div>
            )}
          </div>

          {/* 2. Multi-color Segment Timeline Track (Below Curve) */}
          <div className="relative w-full h-8 bg-slate-950 flex">
            {segments.map((s) => {
              const widthPct = (s.duration / duration) * 100;
              const isSelected = s.id === selectedSegmentId;
              const sc = Number(s.score) || 5.0;
              return (
                <div
                  key={s.id}
                  onClick={(e) => {
                    e.stopPropagation();
                    jumpToSegment(s);
                  }}
                  style={{ width: `${widthPct}%` }}
                  title={`${s.start_time}s - ${s.end_time}s [价值度 ${sc.toFixed(1)}分]: ${s.action === 'keep' ? '保留' : s.action === 'fast_forward' ? `${s.speed}x 快进` : '删除'} - ${s.reason || ''}`}
                  className={`h-full border-r border-slate-900/80 transition-all flex items-center justify-between px-1.5 overflow-hidden group ${getActionColor(s.action, isSelected)}`}
                >
                  <span className="text-[10px] font-mono font-bold text-white/95 truncate">
                    {s.action === 'keep' ? '1x' : s.action === 'fast_forward' ? `${s.speed}x` : 'DEL'}
                  </span>
                  <span className="text-[9px] font-mono font-semibold text-white/80 shrink-0 ml-1">
                    {sc.toFixed(1)}★
                  </span>
                </div>
              );
            })}
          </div>

          {/* Unified Vertical Playhead Cursor (Runs across BOTH Curve & Timeline) */}
          <div
            className="absolute top-0 bottom-0 w-[2px] bg-white shadow-2xl pointer-events-none z-20 transition-transform duration-75"
            style={{ left: `${(currentTime / duration) * 100}%` }}
          >
            <div className="w-3 h-3 bg-white border border-slate-900 rounded-full -ml-[5px] -mt-0.5 shadow-lg" />
          </div>
        </div>
      </div>
    </div>
  );
}
