@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"

echo ========================================
echo   Build EXE with jpackage (via Maven)
echo ========================================
echo.

REM Authoritative version source: .mvn\maven.config (also consumed by Maven).
set "APP_VER="
for /f "tokens=2 delims==" %%A in ('findstr /b /c:"-Drevision=" ".mvn\maven.config"') do set "APP_VER=%%A"
if not defined APP_VER (
    echo [Error] revision missing from .mvn\maven.config
    call :maybe_pause
    exit /b 1
)

set "MVN_CMD=mvn"
where mvn >nul 2>nul
if errorlevel 1 (
    if exist "mvnw.cmd" (
        set "MVN_CMD=mvnw.cmd"
    ) else (
        echo [Error] Maven not found. Install Maven 3.9+ or use mvnw.
        call :maybe_pause
        exit /b 1
    )
)

call %MVN_CMD% -B -q -DskipTests package
if errorlevel 1 (
    echo [Error] Maven package build failed
    call :maybe_pause
    exit /b 1
)

if not exist "target\one-key-dialer-%APP_VER%.jar" (
    echo [Error] target\one-key-dialer-%APP_VER%.jar missing
    call :maybe_pause
    exit /b 1
)

jpackage --version >nul 2>&1
if errorlevel 1 (
    echo [Error] jpackage not found! Please install JDK 21+ ^(26 recommended^)
    call :maybe_pause
    exit /b 1
)

if exist "output" rmdir /s /q "output"
if exist "build\jpackage-input" rmdir /s /q "build\jpackage-input"
mkdir "build\jpackage-input" 2>nul
copy /y "target\one-key-dialer-%APP_VER%.jar" "build\jpackage-input\" >nul
xcopy "target\lib" "build\jpackage-input\lib\" /E /I /Y /Q >nul

echo [1/2] Maven package: done
echo [2/2] Building EXE (app-image)...
REM jlink --compress=zip-6 requires JDK 21+ (recommended JDK 26). Do not use legacy 0/1/2 on modern JDKs.
jpackage --input build\jpackage-input --name PPoEDialer --main-jar one-key-dialer-%APP_VER%.jar --main-class com.lexo0522.ppoe.PPoEDialer --type app-image --dest output --app-version %APP_VER% --java-options "-Xms16m" --java-options "-Xmx96m" --java-options "-XX:+UseSerialGC" --java-options "-XX:MaxMetaspaceSize=96m" --java-options "-Dfile.encoding=UTF-8" --jlink-options "--strip-debug --no-header-files --no-man-pages --compress=zip-6"
if errorlevel 1 (
    echo.
    echo [Error] jpackage failed!
    call :maybe_pause
    exit /b 1
)

if exist "output\PPoEDialer\runtime\lib\ct.sym" del /q "output\PPoEDialer\runtime\lib\ct.sym"

echo.
echo ========================================
echo   Build complete!
echo ========================================
echo JAR location: target\one-key-dialer-%APP_VER%.jar
echo EXE location: output\PPoEDialer\PPoEDialer.exe
echo.
call :maybe_pause
exit /b 0

:maybe_pause
if /I "%CI%"=="true" exit /b 0
if /I "%GITHUB_ACTIONS%"=="true" exit /b 0
pause
exit /b 0
