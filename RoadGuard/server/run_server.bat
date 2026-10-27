@echo off
title RoadGuard API Server
echo ==============================================================================
echo RoadGuard REST + WebSocket API Server (v4)
echo Department of Computer Science ^& Engineering ^| IDEA Lab
echo Ramdeobaba University, Academic Year 2026-27
echo ==============================================================================
echo.

cd /d "%~dp0"
set PY=python
if exist ".venv\Scripts\python.exe" set PY=.venv\Scripts\python.exe
echo Starting RoadGuard on http://localhost:8000 ...
%PY% app.py
pause
