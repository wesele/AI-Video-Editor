import React, { useState, useEffect } from 'react';
import axios from 'axios';
import Header from './components/Header';
import VideoUploader from './components/VideoUploader';
import GeminiPanel from './components/GeminiPanel';
import TimelinePlayer from './components/TimelinePlayer';
import SegmentList from './components/SegmentList';
import ExportModal from './components/ExportModal';
import SettingsModal from './components/SettingsModal';

export default function App() {
  const [systemStatus, setSystemStatus] = useState(null);
  const [videoMeta, setVideoMeta] = useState(null);
  const [segments, setSegments] = useState([]);
  const [selectedSegmentId, setSelectedSegmentId] = useState(null);
  const [scoreThreshold, setScoreThreshold] = useState(6.0);
  const [belowThresholdSpeed, setBelowThresholdSpeed] = useState(4.0);
  const [deleteLowScore, setDeleteLowScore] = useState(true);
  
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [isExportOpen, setIsExportOpen] = useState(false);

  // Helper to re-classify segments based on score and threshold
  const applyThresholdToSegments = (newThreshold, newSpeed, newDeleteLow, currentSegs) => {
    const list = currentSegs || segments;
    return list.map(s => {
      const score = s.score !== undefined ? Number(s.score) : 5.0;
      let action = 'keep';
      let speed = 1.0;
      if (score >= newThreshold) {
        action = 'keep';
        speed = 1.0;
      } else if (newDeleteLow && score <= 2.0) {
        action = 'delete';
        speed = 0.0;
      } else {
        action = 'fast_forward';
        speed = newSpeed;
      }
      return { ...s, action, speed };
    });
  };

  const handleThresholdChange = (newThreshold) => {
    setScoreThreshold(newThreshold);
    setSegments(prev => applyThresholdToSegments(newThreshold, belowThresholdSpeed, deleteLowScore, prev));
  };

  const handleBelowSpeedChange = (newSpeed) => {
    setBelowThresholdSpeed(newSpeed);
    setSegments(prev => applyThresholdToSegments(scoreThreshold, newSpeed, deleteLowScore, prev));
  };

  const handleDeleteLowToggle = (checked) => {
    setDeleteLowScore(checked);
    setSegments(prev => applyThresholdToSegments(scoreThreshold, belowThresholdSpeed, checked, prev));
  };

  // Fetch system status on mount
  const fetchSystemStatus = async () => {
    try {
      const res = await axios.get('/api/system/status');
      setSystemStatus(res.data);
    } catch (e) {
      console.error('Failed to load system status:', e);
    }
  };

  useEffect(() => {
    fetchSystemStatus();
  }, []);

  const handleVideoUploaded = (meta) => {
    setVideoMeta(meta);
    // Initialize with a default single segment covering the entire video
    const initialSegment = {
      id: 'init-1',
      start_time: 0.0,
      end_time: Math.round(meta.duration * 10) / 10,
      duration: Math.round(meta.duration * 10) / 10,
      action: 'keep',
      speed: 1.0,
      score: 6.0,
      reason: '未分析原视频段落',
      summary: '完整视频',
    };
    setSegments([initialSegment]);
    setSelectedSegmentId('init-1');
  };

  const handleResetVideo = () => {
    setVideoMeta(null);
    setSegments([]);
    setSelectedSegmentId(null);
  };

  const handleAnalysisComplete = (newSegments) => {
    const thresholded = applyThresholdToSegments(scoreThreshold, belowThresholdSpeed, deleteLowScore, newSegments);
    setSegments(thresholded);
    if (thresholded.length > 0) {
      setSelectedSegmentId(thresholded[0].id);
    }
  };

  const handleUpdateSegment = (id, fields) => {
    setSegments(prev => prev.map(s => s.id === id ? { ...s, ...fields } : s));
  };

  const handleMergeWithNext = (idx) => {
    if (idx < 0 || idx >= segments.length - 1) return;
    const cur = segments[idx];
    const nxt = segments[idx + 1];

    const merged = {
      id: cur.id,
      start_time: cur.start_time,
      end_time: nxt.end_time,
      duration: Math.round((nxt.end_time - cur.start_time) * 10) / 10,
      action: cur.action,
      speed: cur.speed,
      score: Math.max(cur.score || 5, nxt.score || 5),
      reason: `[合并段落] ${cur.reason || ''} + ${nxt.reason || ''}`.trim(),
      summary: cur.summary || nxt.summary,
    };

    const newArr = [...segments];
    newArr.splice(idx, 2, merged);
    setSegments(newArr);
    setSelectedSegmentId(merged.id);
  };

  const handleSplitSegment = (timestamp) => {
    const targetIdx = segments.findIndex(s => timestamp > s.start_time + 0.3 && timestamp < s.end_time - 0.3);
    if (targetIdx === -1) {
      alert('切分位置距离片段首尾太近（需至少相隔 0.3 秒），请微调播放位置后再试');
      return;
    }

    const cur = segments[targetIdx];
    const cutTime = Math.round(timestamp * 10) / 10;

    const segA = {
      ...cur,
      id: `${cur.id}-a`,
      end_time: cutTime,
      duration: Math.round((cutTime - cur.start_time) * 10) / 10,
    };

    const segB = {
      ...cur,
      id: `${cur.id}-b`,
      start_time: cutTime,
      duration: Math.round((cur.end_time - cutTime) * 10) / 10,
      reason: `${cur.reason || ''} (切分段)`,
    };

    const newArr = [...segments];
    newArr.splice(targetIdx, 1, segA, segB);
    setSegments(newArr);
    setSelectedSegmentId(segB.id);
  };

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col">
      {/* Header */}
      <Header
        systemStatus={systemStatus}
        onOpenSettings={() => setIsSettingsOpen(true)}
      />

      {/* Main Workspace */}
      <main className="flex-1 max-w-7xl w-full mx-auto p-4 sm:p-6 space-y-6">
        {/* Step 1: Upload or Video Metadata */}
        <VideoUploader
          videoMeta={videoMeta}
          onVideoUploaded={handleVideoUploaded}
          onReset={handleResetVideo}
        />

        {videoMeta && (
          <>
            {/* Step 2: Gemini AI Analysis Panel */}
            <GeminiPanel
              videoMeta={videoMeta}
              onAnalysisComplete={handleAnalysisComplete}
              onPartialUpdate={setSegments}
              systemStatus={systemStatus}
            />

            {/* Step 3: Interactive Visual Timeline & Player with Value Curve */}
            {segments.length > 0 && (
              <TimelinePlayer
                videoMeta={videoMeta}
                segments={segments}
                selectedSegmentId={selectedSegmentId}
                onSelectSegment={setSelectedSegmentId}
                onSplitSegment={handleSplitSegment}
                scoreThreshold={scoreThreshold}
                onThresholdChange={handleThresholdChange}
                belowThresholdSpeed={belowThresholdSpeed}
                onBelowSpeedChange={handleBelowSpeedChange}
                deleteLowScore={deleteLowScore}
                onDeleteLowToggle={handleDeleteLowToggle}
              />
            )}

            {/* Step 4: Segments List and Actions */}
            {segments.length > 0 && (
              <SegmentList
                segments={segments}
                selectedSegmentId={selectedSegmentId}
                onSelectSegment={setSelectedSegmentId}
                onUpdateSegment={handleUpdateSegment}
                onMergeWithNext={handleMergeWithNext}
                onOpenExportModal={() => setIsExportOpen(true)}
                totalOriginalDuration={videoMeta.duration}
              />
            )}
          </>
        )}
      </main>

      {/* Export Modal */}
      {videoMeta && (
        <ExportModal
          isOpen={isExportOpen}
          onClose={() => setIsExportOpen(false)}
          videoMeta={videoMeta}
          segments={segments}
          systemStatus={systemStatus}
        />
      )}

      {/* Settings Modal */}
      <SettingsModal
        isOpen={isSettingsOpen}
        onClose={() => setIsSettingsOpen(false)}
        systemStatus={systemStatus}
        onSettingsSaved={fetchSystemStatus}
      />
    </div>
  );
}
