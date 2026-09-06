@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"

set "LOGO_SOURCE=%~dp0src\main\resources\icons\logo.png"
set "ICON_OUTPUT=%~dp0build\logo.ico"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build_icon.ps1" -Source "%LOGO_SOURCE%" -Output "%ICON_OUTPUT%"
if errorlevel 1 (
    echo [Error] Could not generate build\logo.ico from src\main\resources\icons\logo.png
    exit /b 1
)
exit /b 0
