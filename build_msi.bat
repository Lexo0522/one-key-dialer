@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo ========================================
echo   Build MSI installer with jpackage
echo ========================================
echo.

REM Same version as model.AppVersion.NUMERIC / pom.xml
set "APP_VER=1.1.0"

java -version >nul 2>nul
if errorlevel 1 (
    echo [Error] Java runtime not found!
    call :maybe_pause
    exit /b 1
)

jpackage --version >nul 2>&1
if errorlevel 1 (
    echo [Error] jpackage not found! Need JDK 14+
    call :maybe_pause
    exit /b 1
)

if not exist "build\PPoEDialer.jar" (
    echo [1/2] Building app-image prerequisites via build_jpackage.bat ...
    call "%~dp0build_jpackage.bat"
    if errorlevel 1 (
        echo [Error] Prerequisite build failed
        call :maybe_pause
        exit /b 1
    )
)

if not exist "build\PPoEDialer.jar" (
    echo [Error] build\PPoEDialer.jar missing
    call :maybe_pause
    exit /b 1
)

if exist "installer" rmdir /s /q "installer"
mkdir "installer"

echo [2/2] Building MSI...
jpackage --input build --name PPoEDialer --main-jar PPoEDialer.jar --main-class com.lexo0522.ppoe.PPoEDialer --type msi --dest installer --app-version %APP_VER% --vendor "Lexo0522" --description "PPPoE campus dialer" --win-menu --win-shortcut --java-options "-Xms16m" --java-options "-Xmx96m" --java-options "-XX:+UseSerialGC" --java-options "-XX:MaxMetaspaceSize=96m" --java-options "-Dfile.encoding=UTF-8" --jlink-options "--strip-debug --compress=zip-6 --no-header-files --no-man-pages"

if errorlevel 1 (
    echo.
    echo [Error] MSI build failed. On some machines WiX is required for --type msi.
    echo You can still use the portable app-image under output\PPoEDialer\
    call :maybe_pause
    exit /b 1
)

echo.
echo ========================================
echo   MSI complete: installer\
echo ========================================
dir /b installer
echo.
call :maybe_pause
exit /b 0

:maybe_pause
if /I "%CI%"=="true" exit /b 0
if /I "%GITHUB_ACTIONS%"=="true" exit /b 0
pause
exit /b 0
