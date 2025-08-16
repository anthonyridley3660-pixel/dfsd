@echo off
setlocal enabledelayedexpansion
title Blue Nine Access — One‑Click Build & Install (Wireless or USB)

echo.
echo =====================================================
echo   Blue Nine Access — Build and Install (Debug)
echo   Supports USB and Wireless Debugging
echo =====================================================
echo.

REM 0) Jump to the folder this script is in (project root)
cd /d "%~dp0"

REM 1) Resolve adb path (PATH first, then default SDK path)
for %%X in (adb.exe) do (set "ADB_ON_PATH=%%~$PATH:X")
if defined ADB_ON_PATH (
  set "ADB=%ADB_ON_PATH%"
) else (
  set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
)
if not exist "%ADB%" (
  echo [ERROR] adb.exe not found. Install Android SDK Platform-Tools or add adb to PATH.
  echo         Expected at: %LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
  pause
  exit /b 1
)

echo [1/5] Checking device connection...
"%ADB%" devices

echo.
echo If your device is connected via USB, you can skip pairing.
echo If using Wireless Debugging, you may need to pair and connect now.
echo.

choice /C WY /M "Pair/connect over Wi-Fi now? (W=yes, Y=no - continue)"
if errorlevel 2 goto SKIP_WIFI

REM --- Wi-Fi Pairing/Connect ---
set /p ADBIP=Enter device IP:PORT for CONNECT (e.g. 192.168.1.20:5555): 
set /p PAIRIP=Enter device IP:PAIRING_PORT (e.g. 192.168.1.20:37135) or leave blank to skip pairing: 
if not "%PAIRIP%"=="" (
  set /p PAIRCODE=Enter 6-digit pairing code from phone: 
  echo Pairing...
  "%ADB%" pair %PAIRIP% %PAIRCODE%
)

echo Connecting...
"%ADB%" connect %ADBIP%

:SKIP_WIFI

echo.
echo [2/5] Verifying device list after connect...
"%ADB%" devices
echo (Device must show 'device' state; not 'offline' or 'unauthorized')
echo.

REM 2) Ensure gradlew.bat exists
if not exist "gradlew.bat" (
  echo [ERROR] gradlew.bat not found in this folder.
  echo         Make sure this script is inside the project root (folder with settings.gradle).
  pause
  exit /b 1
)

REM 3) Build
echo [3/5] Building app (assembleDebug)...
call gradlew.bat :app:assembleDebug
if errorlevel 1 (
  echo [ERROR] Build failed. See errors above.
  pause
  exit /b 1
)

REM 4) Locate APK
set "APK=app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" (
  echo [WARN] app-debug.apk not found at expected path. Searching...
  set "APK="
  for /R "app\build\outputs\apk" %%F in (*.apk) do set "APK=%%F"
)
if not defined APK (
  echo [ERROR] No APK found after build.
  pause
  exit /b 1
)

echo Found APK: "%APK%"
echo.

REM 5) Install
echo [4/5] Installing APK...
"%ADB%" install -r "%APK%"
if errorlevel 1 (
  echo [ERROR] Install failed. If device is offline, try:
  echo   "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb" kill-server
  echo   "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb" start-server
  echo and run this script again.
  pause
  exit /b 1
)

echo.
echo [5/5] Done! Blue Nine Access (debug) installed.
echo Launch the app on your phone and test Sign In (Selfie), then Admin (password: Bluenin1).
pause
