@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo [1/3] Compiling sources...
if exist "bin" rmdir /s /q "bin"
mkdir "bin"
javac -encoding UTF-8 -Xlint:none -d bin src\PPoEDialer.java src\com\lexo0522\ppoe\*.java src\model\*.java src\service\*.java src\storage\*.java src\util\*.java src\ui\*.java
if errorlevel 1 (
  echo Compile failed
  exit /b 1
)

echo [2/3] Compiling zero-dep SelfTest...
if exist "bin-test" rmdir /s /q "bin-test"
mkdir "bin-test"
javac -encoding UTF-8 -d bin-test -cp bin test\SelfTest.java
if errorlevel 1 (
  echo Test compile failed
  exit /b 1
)

echo [3/3] Running SelfTest...
java -cp "bin-test;bin" SelfTest
set ERR=%ERRORLEVEL%

where mvn >nul 2>nul
if not errorlevel 1 (
  echo.
  echo [optional] Maven JUnit tests...
  call mvn -q test
  if errorlevel 1 set ERR=1
)

exit /b %ERR%
