@echo off
chcp 65001 >nul
title SmartVideo - Gemini 视频智能剪辑应用

echo ========================================================
echo        SmartVideo - Gemini 视频智能剪辑应用启动器       
echo ========================================================
echo.

where python >nul 2>nul
if %errorlevel% neq 0 (
    echo [错误] 未检测到 Python，请先安装 Python 3.10+ 并勾选 "Add to PATH"。
    pause
    exit /b 1
)

python run.py

if %errorlevel% neq 0 (
    echo.
    echo [提示] 应用程序已退出或发生异常。
    pause
)
