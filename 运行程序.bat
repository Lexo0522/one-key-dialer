@echo off
chcp 65001 >nul
cd /d "%~dp0"

set "APP_VER="
for /f "tokens=2 delims==" %%A in ('findstr /b /c:"-Drevision=" ".mvn\maven.config"') do set "APP_VER=%%A"

if exist "output\PPoEDialer\PPoEDialer.exe" (
  start "" "output\PPoEDialer\PPoEDialer.exe"
  exit /b 0
)

if defined APP_VER if exist "target\one-key-dialer-%APP_VER%.jar" (
  start "" javaw -Xms16m -Xmx96m -XX:+UseSerialGC -XX:MaxMetaspaceSize=96m -Dfile.encoding=UTF-8 -cp "target\one-key-dialer-%APP_VER%.jar;target\lib\*" com.lexo0522.ppoe.PPoEDialer
  exit /b 0
)

echo [错误] 未找到可运行的程序
echo 请先运行 build_jpackage.bat 或 compile_and_run.bat
pause
exit /b 1
