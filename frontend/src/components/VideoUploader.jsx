import React, { useState, useRef } from 'react';
import { UploadCloud, FileVideo, Clock, Monitor, HardDrive, RefreshCw, Layers } from 'lucide-react';
import axios from 'axios';

export default function VideoUploader({ videoMeta, onVideoUploaded, onReset }) {
  const [dragActive, setDragActive] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [errorMsg, setErrorMsg] = useState('');
  const fileInputRef = useRef(null);

  const formatDuration = (sec) => {
    if (!sec) return '00:00';
    const m = Math.floor(sec / 60);
    const s = Math.floor(sec % 60);
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  const handleFiles = async (files) => {
    if (!files || files.length === 0) return;
    const file = files[0];
    setErrorMsg('');
    setUploading(true);
    setUploadProgress(0);

    const formData = new FormData();
    formData.append('file', file);

    try {
      const res = await axios.post('/api/video/upload', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
        onUploadProgress: (progressEvent) => {
          if (progressEvent.total) {
            const percent = Math.round((progressEvent.loaded * 100) / progressEvent.total);
            setUploadProgress(percent);
          }
        },
      });
      onVideoUploaded(res.data);
    } catch (err) {
      setErrorMsg('上传/解析视频失败: ' + (err.response?.data?.detail || err.message));
    } finally {
      setUploading(false);
    }
  };

  const handleDrag = (e) => {
    e.preventDefault();
    e.stopPropagation();
    if (e.type === 'dragenter' || e.type === 'dragover') {
      setDragActive(true);
    } else if (e.type === 'dragleave') {
      setDragActive(false);
    }
  };

  const handleDrop = (e) => {
    e.preventDefault();
    e.stopPropagation();
    setDragActive(false);
    if (e.dataTransfer.files && e.dataTransfer.files[0]) {
      handleFiles(e.dataTransfer.files);
    }
  };

  if (videoMeta) {
    return (
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 shadow-lg flex flex-col md:flex-row items-center justify-between gap-4">
        <div className="flex items-center space-x-4">
          <div className="bg-sky-500/10 border border-sky-500/20 p-3 rounded-xl text-sky-400">
            <FileVideo className="w-8 h-8" />
          </div>
          <div>
            <div className="flex items-center space-x-2">
              <h3 className="font-semibold text-white truncate max-w-xs md:max-w-md" title={videoMeta.filename}>
                {videoMeta.filename}
              </h3>
              <span className="text-xs bg-slate-800 text-slate-300 px-2 py-0.5 rounded-full border border-slate-700">
                {videoMeta.filesize_formatted}
              </span>
            </div>
            <div className="flex flex-wrap items-center gap-4 text-xs text-slate-400 mt-1.5">
              <span className="flex items-center gap-1">
                <Clock className="w-3.5 h-3.5 text-slate-500" />
                时长: <strong className="text-slate-200 font-mono">{formatDuration(videoMeta.duration)}</strong> ({Math.round(videoMeta.duration)}s)
              </span>
              <span className="flex items-center gap-1">
                <Monitor className="w-3.5 h-3.5 text-slate-500" />
                分辨率: <strong className="text-slate-200">{videoMeta.width} x {videoMeta.height}</strong>
              </span>
              <span className="flex items-center gap-1">
                <Layers className="w-3.5 h-3.5 text-slate-500" />
                帧率: <strong className="text-slate-200">{videoMeta.fps} fps</strong>
              </span>
            </div>
          </div>
        </div>

        <button
          onClick={onReset}
          className="px-4 py-2 bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-300 hover:text-white rounded-xl text-xs font-medium transition cursor-pointer flex items-center gap-1.5 shrink-0"
        >
          <RefreshCw className="w-3.5 h-3.5" />
          <span>更换视频</span>
        </button>
      </div>
    );
  }

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 shadow-xl">
      <div
        onDragEnter={handleDrag}
        onDragOver={handleDrag}
        onDragLeave={handleDrag}
        onDrop={handleDrop}
        onClick={() => fileInputRef.current?.click()}
        className={`border-2 border-dashed rounded-2xl p-10 text-center cursor-pointer transition-all duration-200 ${
          dragActive
            ? 'border-sky-500 bg-sky-500/5 scale-[1.01]'
            : 'border-slate-700 hover:border-slate-600 bg-slate-900/50 hover:bg-slate-800/50'
        }`}
      >
        <input
          ref={fileInputRef}
          type="file"
          accept="video/mp4,video/mkv,video/webm,video/quicktime,video/avi"
          className="hidden"
          onChange={(e) => handleFiles(e.target.files)}
        />

        <div className="max-w-md mx-auto space-y-3">
          <div className="mx-auto w-14 h-14 rounded-2xl bg-sky-500/10 border border-sky-500/20 text-sky-400 flex items-center justify-center shadow-inner">
            {uploading ? (
              <RefreshCw className="w-7 h-7 animate-spin text-sky-400" />
            ) : (
              <UploadCloud className="w-7 h-7" />
            )}
          </div>

          <div>
            <h3 className="text-base font-semibold text-white">
              {uploading ? `正在上传并解析视频... ${uploadProgress}%` : '点击选择或将视频拖拽至此处'}
            </h3>
            <p className="text-xs text-slate-400 mt-1">
              支持 MP4, MKV, MOV, WebM 等主流高清视频格式
            </p>
          </div>

          {uploading && (
            <div className="w-full bg-slate-800 rounded-full h-2 overflow-hidden mt-3">
              <div
                className="bg-gradient-to-r from-sky-500 to-indigo-500 h-full transition-all duration-300"
                style={{ width: `${uploadProgress}%` }}
              />
            </div>
          )}

          {errorMsg && (
            <div className="p-3 bg-rose-500/10 border border-rose-500/20 text-rose-300 rounded-xl text-xs mt-2">
              {errorMsg}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
