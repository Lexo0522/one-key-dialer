@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo ========================================
echo   PPPoE校园网拨号工具 - 编译运行 (Maven)
echo ========================================
echo.

where mvn >nul 2>nul
if errorlevel 1 (
    if exist "mvnw.cmd" (
        set "MVN_CMD=mvnw.cmd"
        goto :run
    )
    echo [错误] 未检测到 Maven，请安装 Maven 3.9+ 或使用 mvnw。
    pause
    exit /b 1
)
set "MVN_CMD=mvn"

:run
echo [1/2] Maven 编译打包（跳过测试）...
call %MVN_CMD% -B -q -DskipTests package
if errorlevel 1 (
    echo [错误] Maven 打包失败！
    pause
    exit /b 1
)

set "APP_VER="
for /f "tokens=2 delims==" %%A in ('findstr /b /c:"-Drevision=" ".mvn\maven.config"') do set "APP_VER=%%A"
if not defined APP_VER (
    echo [错误] .mvn\maven.config 中缺少 revision
    pause
    exit /b 1
)

echo [2/2] 运行程序...
echo.
java -Xms16m -Xmx96m -XX:+UseSerialGC -XX:MaxMetaspaceSize=96m -Dfile.encoding=UTF-8 -cp "target\one-key-dialer-%APP_VER%.jar;target\lib\*" com.lexo0522.ppoe.PPoEDialer
pause
