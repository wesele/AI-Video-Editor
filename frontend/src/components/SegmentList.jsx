import React from 'react';
import { FastForward, Check, Trash2, Combine, Sparkles, ArrowRight, Clock, Plus, Zap } from 'lucide-react';

export default function SegmentList({
  segments,
  selectedSegmentId,
  onSelectSegment,
  onUpdateSegment,
  onMergeWithNext,
  onDeleteOrRestoreSegment,
  onOpenExportModal,
  totalOriginalDuration,
}) {
  const formatTime = (sec) => {
    if (!sec || isNaN(sec)) return '00:00.0';
    const m = Math.floor(sec / 60);
    const s = (sec % 60).toFixed(1);
    return `${m.toString().padStart(2, '0')}:${parseFloat(s) < 10 ? '0' : ''}${s}`;
  };

  // Compute stats
  let totalEstimatedOutputSec = 0;
  let keepCount = 0;
  let ffCount = 0;
  let delCount = 0;

  segments.forEach(s => {
    if (s.action === 'keep') {
      keepCount++;
      totalEstimatedOutputSec += s.duration;
    } else if (s.action === 'fast_forward') {
      ffCount++;
      const spd = s.speed || 4.0;
      totalEstimatedOutputSec += (s.duration / spd);
    } else {
      delCount++;
    }
  });

  const compressionRatio = totalOriginalDuration > 0 
    ? Math.round((totalEstimatedOutputSec / totalOriginalDuration) * 100) 
    : 100;

  const handleTimeAdjust = (segId, field, delta) => {
    const seg = segments.find(s => s.id === segId);
    if (!seg) return;

    let newStart = seg.start_time;
    let newEnd = seg.end_time;

    if (field === 'start') {
      newStart = Math.max(0, Math.min(newEnd - 0.2, newStart + delta));
    } else if (field === 'end') {
      newEnd = Math.max(newStart + 0.2, Math.min(totalOriginalDuration, newEnd + delta));
    }

    const dur = Math.round((newEnd - newStart) * 10) / 10;
    onUpdateSegment(segId, {
      start_time: Math.round(newStart * 10) / 10,
      end_time: Math.round(newEnd * 10) / 10,
      duration: dur,
    });
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 shadow-xl space-y-6">
      {/* Header & Stats Banner */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 border-b border-slate-800 pb-5">
        <div>
          <h2 className="text-base font-bold text-white flex items-center gap-2">
            剪辑分段方案明细
            <span className="text-xs font-normal text-slate-400 bg-slate-800 px-2.5 py-0.5 rounded-full border border-slate-700">
              共 {segments.length} 个分段
            </span>
          </h2>
          <p className="text-xs text-slate-400 mt-1">
            可任意修改每段动作类型、调节快进倍速、微调时间或合并相邻片段
          </p>
        </div>

        {/* Stats Pill */}
        <div className="flex items-center space-x-3 text-xs">
          <div className="bg-slate-800/80 border border-slate-700/80 px-3.5 py-2 rounded-xl flex items-center gap-4">
            <div>
              <span className="text-slate-400 text-[11px] block">预计成片时长</span>
              <span className="text-sky-400 font-bold font-mono text-sm">
                {formatTime(totalEstimatedOutputSec)}
              </span>
              <span className="text-[10px] text-slate-500 ml-1">
                ({compressionRatio}%)
              </span>
            </div>
            <div className="h-6 w-[1px] bg-slate-700" />
            <div className="flex items-center gap-2 text-[11px]">
              <span className="text-emerald-400 font-medium">保留: {keepCount}</span>
              <span className="text-amber-400 font-medium">快进: {ffCount}</span>
              <span className="text-slate-500 font-medium">删除: {delCount}</span>
            </div>
          </div>

          {/* Export Button */}
          <button
            onClick={onOpenExportModal}
            className="px-5 py-2.5 bg-gradient-to-r from-sky-500 to-indigo-600 hover:from-sky-400 hover:to-indigo-500 text-white font-semibold rounded-xl text-xs shadow-lg shadow-sky-500/20 flex items-center gap-1.5 transition cursor-pointer"
          >
            <Zap className="w-4 h-4" />
            <span>配置并导出视频</span>
          </button>
        </div>
      </div>

      {/* Segment Cards List */}
      <div className="space-y-3 max-h-[560px] overflow-y-auto pr-1">
        {segments.map((seg, idx) => {
          const isSelected = seg.id === selectedSegmentId;
          const isLast = idx === segments.length - 1;
          const outputDuration = seg.action === 'keep' 
            ? seg.duration 
            : seg.action === 'fast_forward' 
              ? (seg.duration / (seg.speed || 4.0)) 
              : 0;

          return (
            <div
              key={seg.id}
              onClick={() => onSelectSegment(seg.id)}
              className={`p-4 rounded-xl border transition cursor-pointer flex flex-col md:flex-row md:items-center justify-between gap-4 ${
                isSelected
                  ? 'bg-slate-800/90 border-sky-500/60 shadow-lg shadow-sky-500/10 ring-1 ring-sky-500/30'
                  : 'bg-slate-800/40 border-slate-800 hover:border-slate-700'
              } ${seg.action === 'delete' ? 'opacity-60 bg-slate-900/40' : ''}`}
            >
              {/* Left Column: Number, Action Badge, Time */}
              <div className="flex items-start md:items-center space-x-3.5">
                <span className="text-xs font-mono font-bold text-slate-500 w-6 shrink-0">
                  #{idx + 1}
                </span>

                {/* Action Selector Buttons */}
                <div className="flex items-center space-x-1 bg-slate-900/80 p-1 rounded-xl border border-slate-700/80 shrink-0">
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      onUpdateSegment(seg.id, { action: 'keep', speed: 1.0 });
                    }}
                    className={`px-2.5 py-1 rounded-lg text-xs font-medium flex items-center gap-1 transition ${
                      seg.action === 'keep'
                        ? 'bg-emerald-500 text-white font-bold shadow'
                        : 'text-slate-400 hover:text-white'
                    }`}
                  >
                    <Check className="w-3 h-3" />
                    <span>保留</span>
                  </button>

                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      onUpdateSegment(seg.id, { action: 'fast_forward', speed: seg.speed > 1.0 ? seg.speed : 4.0 });
                    }}
                    className={`px-2.5 py-1 rounded-lg text-xs font-medium flex items-center gap-1 transition ${
                      seg.action === 'fast_forward'
                        ? 'bg-amber-500 text-white font-bold shadow'
                        : 'text-slate-400 hover:text-white'
                    }`}
                  >
                    <FastForward className="w-3 h-3" />
                    <span>快进</span>
                  </button>

                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      onUpdateSegment(seg.id, { action: 'delete', speed: 0.0 });
                    }}
                    className={`px-2.5 py-1 rounded-lg text-xs font-medium flex items-center gap-1 transition ${
                      seg.action === 'delete'
                        ? 'bg-rose-600 text-white font-bold shadow'
                        : 'text-slate-400 hover:text-white'
                    }`}
                  >
                    <Trash2 className="w-3 h-3" />
                    <span>删除</span>
                  </button>
                </div>

                {/* Speed selector if fast_forward */}
                {seg.action === 'fast_forward' && (
                  <select
                    value={seg.speed || 4.0}
                    onClick={(e) => e.stopPropagation()}
                    onChange={(e) => {
                      onUpdateSegment(seg.id, { speed: parseFloat(e.target.value) });
                    }}
                    className="bg-slate-900 border border-amber-500/40 text-amber-300 rounded-lg px-2 py-1 text-xs font-mono font-bold focus:outline-none"
                  >
                    <option value={2.0}>2x 倍速</option>
                    <option value={4.0}>4x 倍速</option>
                    <option value={8.0}>8x 倍速</option>
                    <option value={16.0}>16x 倍速</option>
                  </select>
                )}

                {/* Time range with fine-tune buttons */}
                <div className="flex items-center space-x-2 text-xs font-mono">
                  <div className="flex items-center bg-slate-900 border border-slate-700/80 rounded-lg px-2 py-0.5">
                    <button
                      onClick={(e) => { e.stopPropagation(); handleTimeAdjust(seg.id, 'start', -0.5); }}
                      className="text-slate-500 hover:text-white px-1 font-bold"
                      title="提前 0.5s"
                    >-</button>
                    <span className="text-white px-1">{formatTime(seg.start_time)}</span>
                    <button
                      onClick={(e) => { e.stopPropagation(); handleTimeAdjust(seg.id, 'start', 0.5); }}
                      className="text-slate-500 hover:text-white px-1 font-bold"
                      title="推迟 0.5s"
                    >+</button>
                  </div>

                  <ArrowRight className="w-3 h-3 text-slate-500" />

                  <div className="flex items-center bg-slate-900 border border-slate-700/80 rounded-lg px-2 py-0.5">
                    <button
                      onClick={(e) => { e.stopPropagation(); handleTimeAdjust(seg.id, 'end', -0.5); }}
                      className="text-slate-500 hover:text-white px-1 font-bold"
                      title="提前 0.5s"
                    >-</button>
                    <span className="text-white px-1">{formatTime(seg.end_time)}</span>
                    <button
                      onClick={(e) => { e.stopPropagation(); handleTimeAdjust(seg.id, 'end', 0.5); }}
                      className="text-slate-500 hover:text-white px-1 font-bold"
                      title="推迟 0.5s"
                    >+</button>
                  </div>

                  <span className="text-slate-400 text-[11px]">
                    (原 {seg.duration}s → 成片 {Math.round(outputDuration * 10) / 10}s)
                  </span>
                </div>
              </div>

              {/* Middle & Right: AI Rationale and Merge */}
              <div className="flex items-center justify-between md:justify-end space-x-3 text-xs w-full md:w-auto">
                {/* Score badge & AI Rationale / Summary */}
                <div className="flex items-center space-x-2 truncate max-w-sm" title={`${seg.summary ? seg.summary + ' - ' : ''}${seg.reason || ''}`}>
                  <span className={`px-2 py-0.5 rounded text-[11px] font-mono font-bold shrink-0 border ${
                    (Number(seg.score) || 5.0) >= 7.5
                      ? 'bg-emerald-500/15 border-emerald-500/30 text-emerald-300'
                      : (Number(seg.score) || 5.0) >= 5.0
                        ? 'bg-sky-500/15 border-sky-500/30 text-sky-300'
                        : 'bg-amber-500/15 border-amber-500/30 text-amber-300'
                  }`}>
                    {(Number(seg.score) || 5.0).toFixed(1)}分
                  </span>
                  <div className="text-slate-400 text-xs truncate">
                    {seg.summary && <span className="text-slate-200 font-medium mr-1.5">{seg.summary}</span>}
                    <span className="text-slate-500">{seg.reason || 'AI 判定段落'}</span>
                  </div>
                </div>

                {/* Merge with next button */}
                {!isLast && (
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      onMergeWithNext(idx);
                    }}
                    className="p-1.5 bg-slate-900 hover:bg-slate-700 border border-slate-700 text-slate-400 hover:text-white rounded-lg transition"
                    title="将本段与下一段合并为一个片段"
                  >
                    <Combine className="w-3.5 h-3.5" />
                  </button>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
