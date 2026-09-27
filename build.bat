@echo off
setlocal EnableExtensions
cd /d "%~dp0"

REM ============================================================
REM  MangaCopy portable EXE builder (Windows)
REM  Produces:  dist\MangaCopy\MangaCopy.exe   (+ _internal\, playwright-browsers\)
REM  Copy the whole "MangaCopy" folder anywhere and run the exe.
REM ============================================================

echo.
echo === [1/3] Installing Python dependencies ===
python -m pip install --upgrade pip >nul 2>&1
python -m pip install -r requirements.txt
if errorlevel 1 goto :err

echo.
echo === [2/3] Building the EXE with PyInstaller (one-dir) ===
python -m PyInstaller MangaCopy.spec --noconfirm --clean
if errorlevel 1 goto :err

echo.
echo === [3/3] Installing Chromium into the portable output folder ===
set "PLAYWRIGHT_BROWSERS_PATH=%~dp0dist\MangaCopy\playwright-browsers"
python -m playwright install chromium
if errorlevel 1 goto :err

echo.
echo ============================================================
echo   Build complete!
echo   Run:        dist\MangaCopy\MangaCopy.exe
echo   To share:   copy the whole "MangaCopy" folder to any PC (no install).
echo ============================================================
goto :eof

:err
echo.
echo BUILD FAILED - see the messages above.
exit /b 1
