@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul
cd /d "%~dp0"

set "CALLER_CI=%CI%"
set "CALLER_GITHUB_ACTIONS=%GITHUB_ACTIONS%"
set "APP_VER="
for /f "tokens=2 delims==" %%A in ('findstr /b /c:"-Drevision=" ".mvn\maven.config"') do set "APP_VER=%%A"
if not defined APP_VER (
    echo [Error] revision missing from .mvn\maven.config
    call :maybe_pause
    exit /b 1
)
echo %APP_VER% | findstr /r "^[0-9][0-9.]*$" >nul
if errorlevel 1 (
    echo [Error] Invalid revision: %APP_VER%
    call :maybe_pause
    exit /b 1
)

set "RELEASE_DIR=release"
set "ZIP_ASSET=%RELEASE_DIR%\PPoEDialer-%APP_VER%-windows.zip"
set "MSI_ASSET=%RELEASE_DIR%\PPoEDialer-%APP_VER%-windows.msi"
set "CHECKSUMS=%RELEASE_DIR%\SHA256SUMS.txt"

if not exist "%RELEASE_DIR%" mkdir "%RELEASE_DIR%"
if errorlevel 1 (
    echo [Error] Could not create %RELEASE_DIR%\
    call :maybe_pause
    exit /b 1
)
if exist "%ZIP_ASSET%" (
    echo [Error] Refusing to overwrite existing %ZIP_ASSET%
    call :maybe_pause
    exit /b 1
)
if exist "%MSI_ASSET%" (
    echo [Error] Refusing to overwrite existing %MSI_ASSET%
    call :maybe_pause
    exit /b 1
)
if exist "%CHECKSUMS%" (
    echo [Error] Refusing to overwrite existing %CHECKSUMS%
    call :maybe_pause
    exit /b 1
)

if not exist "build_jpackage.bat" (
    echo [Error] build_jpackage.bat missing
    call :maybe_pause
    exit /b 1
)
if not exist "build_msi.bat" (
    echo [Error] build_msi.bat missing
    call :maybe_pause
    exit /b 1
)
powershell -NoProfile -Command "exit 0" >nul 2>nul
if errorlevel 1 (
    echo [Error] Windows PowerShell is required to create release assets
    call :maybe_pause
    exit /b 1
)

set "STAGING_ROOT=%RELEASE_DIR%\.staging"
set "STAGING_DIR=%STAGING_ROOT%\PPoEDialer-%APP_VER%-%RANDOM%-%RANDOM%"
set "ZIP_STAGE=%STAGING_DIR%\PPoEDialer-%APP_VER%-windows.zip"
set "MSI_STAGE=%STAGING_DIR%\PPoEDialer-%APP_VER%-windows.msi"
set "CHECKSUMS_STAGE=%STAGING_DIR%\SHA256SUMS.txt"

if not exist "%STAGING_ROOT%" mkdir "%STAGING_ROOT%"
if errorlevel 1 (
    echo [Error] Could not create %STAGING_ROOT%\
    call :maybe_pause
    exit /b 1
)
mkdir "%STAGING_DIR%"
if errorlevel 1 (
    echo [Error] Could not create staging directory
    call :maybe_pause
    exit /b 1
)

REM Suppress pauses in the two child build scripts, without changing the caller environment.
set "CI=true"
set "GITHUB_ACTIONS=true"

echo ========================================
echo   Prepare release assets v%APP_VER%
echo ========================================
echo.

echo [1/4] Building portable app-image...
call build_jpackage.bat
if errorlevel 1 (
    echo [Error] App-image build failed
    call :cleanup_staging
    call :maybe_pause
    exit /b 1
)

if not exist "output\PPoEDialer\PPoEDialer.exe" (
    echo [Error] output\PPoEDialer\PPoEDialer.exe missing
    call :cleanup_staging
    call :maybe_pause
    exit /b 1
)

echo [2/4] Creating %ZIP_ASSET%...
powershell -NoProfile -Command "Compress-Archive -LiteralPath 'output\PPoEDialer' -DestinationPath '%ZIP_STAGE%' -ErrorAction Stop"
if errorlevel 1 (
    echo [Error] ZIP creation failed
    call :cleanup_staging
    call :maybe_pause
    exit /b 1
)

echo [3/4] Building and naming MSI...
call build_msi.bat --reuse-app-image
if errorlevel 1 (
    echo [Error] MSI build failed
    call :cleanup_staging
    call :maybe_pause
    exit /b 1
)

set "MSI_SOURCE="
for %%F in ("installer\*.msi") do (
    if exist "%%~fF" (
        if defined MSI_SOURCE (
            echo [Error] Multiple MSI files found in installer\
            call :cleanup_staging
            call :maybe_pause
            exit /b 1
        )
        set "MSI_SOURCE=%%~fF"
    )
)
if not defined MSI_SOURCE (
    echo [Error] No MSI file found in installer\
    call :cleanup_staging
    call :maybe_pause
    exit /b 1
)
copy /y "%MSI_SOURCE%" "%MSI_STAGE%" >nul
if errorlevel 1 (
    echo [Error] Could not stage the MSI asset
    call :cleanup_staging
    call :maybe_pause
    exit /b 1
)

echo [4/4] Generating SHA-256 manifest...
powershell -NoProfile -Command "$ErrorActionPreference='Stop'; $files=@('%ZIP_STAGE%','%MSI_STAGE%'); $lines=foreach($path in $files) { $hash=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant(); $hash + '  ' + [System.IO.Path]::GetFileName($path) }; [System.IO.File]::WriteAllLines((Join-Path (Get-Location) '%CHECKSUMS_STAGE%'), [string[]]$lines, [System.Text.Encoding]::ASCII)"
if errorlevel 1 (
    echo [Error] SHA-256 manifest generation failed
    call :cleanup_staging
    call :maybe_pause
    exit /b 1
)

echo Publishing release assets...
move /y "%ZIP_STAGE%" "%ZIP_ASSET%" >nul
if errorlevel 1 (
    echo [Error] Could not publish %ZIP_ASSET%
    call :cleanup_staging
    call :maybe_pause
    exit /b 1
)
set "PUBLISHED_ZIP=true"

move /y "%MSI_STAGE%" "%MSI_ASSET%" >nul
if errorlevel 1 (
    echo [Error] Could not publish %MSI_ASSET%
    call :cleanup_published
    call :cleanup_staging
    call :maybe_pause
    exit /b 1
)
set "PUBLISHED_MSI=true"

move /y "%CHECKSUMS_STAGE%" "%CHECKSUMS%" >nul
if errorlevel 1 (
    echo [Error] Could not publish %CHECKSUMS%
    call :cleanup_published
    call :cleanup_staging
    call :maybe_pause
    exit /b 1
)

call :cleanup_staging

echo.
echo ========================================
echo   Release assets ready
echo ========================================
echo Upload these three files to GitHub Release tag v%APP_VER%:
echo   %ZIP_ASSET%
echo   %MSI_ASSET%
echo   %CHECKSUMS%
echo.
call :maybe_pause
exit /b 0

:cleanup_published
if defined PUBLISHED_MSI if exist "%MSI_ASSET%" del /q "%MSI_ASSET%" >nul 2>nul
if defined PUBLISHED_ZIP if exist "%ZIP_ASSET%" del /q "%ZIP_ASSET%" >nul 2>nul
exit /b 0

:cleanup_staging
if defined STAGING_DIR if exist "%STAGING_DIR%" rmdir /s /q "%STAGING_DIR%" >nul 2>nul
if defined STAGING_ROOT if exist "%STAGING_ROOT%" rmdir "%STAGING_ROOT%" >nul 2>nul
exit /b 0

:maybe_pause
if /I "%CALLER_CI%"=="true" exit /b 0
if /I "%CALLER_GITHUB_ACTIONS%"=="true" exit /b 0
pause
exit /b 0
