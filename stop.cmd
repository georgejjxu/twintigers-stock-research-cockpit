@echo off
rem Stops the Stock Research Cockpit and its monitor (data kept)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop.ps1"
pause
