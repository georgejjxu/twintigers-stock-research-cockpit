@echo off
rem Starts the Stock Research Cockpit (and Docker Desktop if needed), then opens http://localhost:8099
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start.ps1"
if errorlevel 1 pause
