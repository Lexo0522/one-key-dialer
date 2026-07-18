@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo ========================================
echo   PPPoE校园网拨号工具 - 编译运行
echo ========================================
echo.

java -version 2>nul
if errorlevel 1 (
    echo [错误] 未检测到Java运行环境！
    pause
    exit /b 1
)

javac -version >nul 2>nul
if errorlevel 1 (
    echo [错误] 未检测到Java编译器 javac，请安装JDK并检查环境变量！
    pause
    exit /b 1
)

if not exist "lib\flatlaf-3.5.4.jar" (
    echo [提示] 未找到 lib\flatlaf-3.5.4.jar，将使用系统外观。
    echo        可运行: curl -fsSL -o lib\flatlaf-3.5.4.jar https://repo1.maven.org/maven2/com/formdev/flatlaf/3.5.4/flatlaf-3.5.4.jar
)

echo [1/2] 编译Java源码...
if exist "bin" rmdir /s /q "bin"
mkdir "bin"

javac -encoding UTF-8 -Xlint:none -d bin src\PPoEDialer.java src\com\lexo0522\ppoe\*.java src\model\*.java src\service\*.java src\storage\*.java src\util\*.java src\ui\*.java
if errorlevel 1 (
    echo [错误] 编译失败！
    pause
    exit /b 1
)
echo 编译成功！

set "CP=bin"
if exist "lib\flatlaf-3.5.4.jar" set "CP=bin;lib\flatlaf-3.5.4.jar"

echo [2/2] 运行程序...
echo.
java -Xms16m -Xmx96m -XX:+UseSerialGC -XX:MaxMetaspaceSize=96m -Dfile.encoding=UTF-8 -cp "%CP%" com.lexo0522.ppoe.PPoEDialer
pause
