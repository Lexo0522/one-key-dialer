@echo off
chcp 65001 >nul
cd /d "%~dp0"

if not exist "build\PPoEDialer.jar" (
  echo [错误] 未找到 build\PPoEDialer.jar
  echo 请先运行 build_jpackage.bat 或手动打包 JAR
  pause
  exit /b 1
)

start "" javaw -Xms16m -Xmx96m -XX:+UseSerialGC -XX:MaxMetaspaceSize=96m -Dfile.encoding=UTF-8 -jar "build\PPoEDialer.jar"
if errorlevel 1 (
  echo 启动失败，尝试使用 java 查看错误...
  java -Xms16m -Xmx96m -XX:+UseSerialGC -XX:MaxMetaspaceSize=96m -Dfile.encoding=UTF-8 -jar "build\PPoEDialer.jar"
  pause
)
