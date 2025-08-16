@echo off
setlocal enabledelayedexpansion
title Blue Nine Access — Build & Install (Wi‑Fi or USB)

REM --------- Force add adb to PATH (works even if PATH isn't set) ---------
set "PT=%LOCALAPPDATA%\Android\Sdk\platform-tools"
if exist "%PT%\adb.exe" (
  set "PATH=%PT%;%PATH%"
)

echo.
echo =====================================================
echo   Blue Nine Access — Build and Install (Debug)
echo   Supports USB and Wireless Debugging
echo =====================================================
echo.

REM 0) Jump to this script's folder (must be project root with gradlew.bat)
cd /d "%~dp0"

REM 1) Verify adb availability
where adb >nul 2>nul
if errorlevel 1 (
  echo [ERROR] adb.exe not found. Make sure Android Studio SDK Platform-tools are installed.
  echo Expected at: %LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
  pause
  exit /b 1
)

echo [1/6] Current ADB devices:
adb devices
echo.

REM Ask about Wi‑Fi connect/pair (skip if already "device" over _adb-tls_)
set "DO_WIFI="
choice /C YN /N /M "Use wireless debugging (pair/connect) now? [Y/N]: "
if errorlevel 2 goto SKIP_WIFI
  set "DO_WIFI=1"
  echo.
  echo --- Wireless Debugging ---
  echo On your phone: Developer options -> Wireless debugging.
  echo If not yet paired this session, tap 'Pair device with pairing code' and keep it open.
  echo.

  set /p CONNECT_ADDR=Enter CONNECT address (ip:port, e.g. 192.168.1.20:5555): 
  if not defined CONNECT_ADDR (
    echo [WARN] No CONNECT address entered. Skipping Wi‑Fi.
    goto SKIP_WIFI
  )

  set /p PAIR_ADDR=Enter PAIR address (ip:pairing_port) OR leave blank to skip pairing: 
  if defined PAIR_ADDR (
    set /p PAIR_CODE=Enter 6-digit pairing code: 
    if not defined PAIR_CODE (
      echo [WARN] No pairing code entered; skipping pair.
    ) else (
      echo Pairing...
      adb pair %PAIR_ADDR% %PAIR_CODE%
    )
  )

  echo Connecting...
  adb connect %CONNECT_ADDR%
  echo.
:SKIP_WIFI

echo [2/6] Verifying device list (must show 'device'):
adb devices
echo.

REM 2) Ensure gradlew.bat exists
if not exist "gradlew.bat" (
  echo [ERROR] gradlew.bat not found in this folder.
  echo Place this script inside the project root (with settings.gradle).
  pause
  exit /b 1
)

REM 3) Build APK
echo [3/6] Building Debug APK (gradlew :app:assembleDebug)...
call gradlew.bat :app:assembleDebug
if errorlevel 1 (
  echo [ERROR] Build failed. See errors above.
  pause
  exit /b 1
)

REM 4) Locate APK
set "APK=app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" (
  for /R "app\build\outputs\apk" %%F in (*.apk) do set "APK=%%F"
)
if not exist "%APK%" (
  echo [ERROR] Could not find built APK.
  pause
  exit /b 1
)

echo Found APK: "%APK%"
echo.

REM 5) Install APK
echo [4/6] Installing to device (adb install -r)...
adb install -r "%APK%"
if errorlevel 1 (
  echo [ERROR] Install failed.
  echo Try: adb kill-server ^& adb start-server, then rerun this script.
  pause
  exit /b 1
)

echo.
echo [5/6] Installed successfully.
echo [6/6] Launch the app on your phone and test:
echo   - Sign In (Selfie) -> allow Camera
echo   - Admin password: Bluenin1
echo   - Export ZIP (Excel + Selfies)
echo.
pause
