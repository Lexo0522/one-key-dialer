@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo Running Maven tests...
where mvn >nul 2>nul
if errorlevel 1 (
    echo [错误] 未检测到 Maven，请安装 Maven 3.9+ 或使用 mvnw。
    pause
    exit /b 1
)
call mvn -B test
exit /b %ERRORLEVEL%
