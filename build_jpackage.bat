@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo ========================================
echo   Build EXE with jpackage (JDK 14+)
echo ========================================
echo.

java -version >nul 2>nul
if errorlevel 1 (
    echo [Error] Java runtime not found!
    pause
    exit /b 1
)

javac -version >nul 2>nul
if errorlevel 1 (
    echo [Error] javac not found! Please install JDK and check PATH
    pause
    exit /b 1
)

jar --version >nul 2>nul
if errorlevel 1 (
    echo [Error] jar tool not found! Please install JDK and check PATH
    pause
    exit /b 1
)

jpackage --version >nul 2>&1
if errorlevel 1 (
    echo [Error] jpackage not found!
    echo Please install JDK 14 or higher
    pause
    exit /b 1
)

if exist "bin" rmdir /s /q "bin"
if exist "build" rmdir /s /q "build"
if exist "output" rmdir /s /q "output"

mkdir "bin"
mkdir "build"

echo [1/3] Compiling Java sources...
javac -encoding UTF-8 -Xlint:none -d bin src\PPoEDialer.java src\model\*.java src\service\*.java src\storage\*.java src\util\*.java src\ui\*.java
if errorlevel 1 (
    echo.
    echo [Error] Compile failed!
    pause
    exit /b 1
)

echo.
echo [2/3] Building JAR...
> build\MANIFEST.MF echo Main-Class: PPoEDialer
if exist "lib\flatlaf-3.5.4.jar" (
    >> build\MANIFEST.MF echo Class-Path: flatlaf-3.5.4.jar
    copy /y "lib\flatlaf-3.5.4.jar" "build\flatlaf-3.5.4.jar" >nul
)
jar cfm build\PPoEDialer.jar build\MANIFEST.MF -C bin .
if errorlevel 1 (
    echo.
    echo [Error] JAR build failed!
    pause
    exit /b 1
)

echo.
echo [3/3] Building EXE...
echo.
jpackage --input build --name PPoEDialer --main-jar PPoEDialer.jar --main-class PPoEDialer --type app-image --dest output --app-version 1.0.0 --java-options "-Xms16m" --java-options "-Xmx96m" --java-options "-XX:+UseSerialGC" --java-options "-XX:MaxMetaspaceSize=96m" --java-options "-Dfile.encoding=UTF-8" --jlink-options "--strip-debug --compress=zip-6 --no-header-files --no-man-pages"

if errorlevel 1 (
    echo.
    echo [Error] Build failed!
    pause
    exit /b 1
)

echo.
if exist "output\PPoEDialer\runtime\lib\ct.sym" del /q "output\PPoEDialer\runtime\lib\ct.sym"

echo.
echo ========================================
echo   Build complete!
echo ========================================
echo JAR location: build\PPoEDialer.jar
echo EXE location: output\PPoEDialer\PPoEDialer.exe
echo.
pause
