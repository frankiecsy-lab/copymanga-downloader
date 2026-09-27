@echo off
setlocal EnableExtensions
cd /d "%~dp0"

REM ============================================================
REM  MangaCopy portable EXE builder (Windows)
REM  Produces:  dist\MangaCopy\MangaCopy.exe   (+ _internal\, playwright-browsers\)
REM  Copy the whole "MangaCopy" folder anywhere and run the exe.
REM
REM  IMPORTANT: PyInstaller wipes the ENTIRE dist\MangaCopy output dir on every
REM  build. The portable app keeps its library DB (data\mangacopy.db) and all
REM  downloaded comics (downloads\) inside that same folder, so this script
REM  moves them aside to dist\_userdata before building and back afterwards.
REM  Same-volume move = instant rename, safe even for huge downloads folders.
REM ============================================================

set "APP_DIR=%~dp0dist\MangaCopy"
set "STAGE_DIR=%~dp0dist\_userdata"

echo.
echo === [1/5] Staging portable app user data (PyInstaller wipes the output dir) ===
if exist "%APP_DIR%\data\" (
    if not exist "%STAGE_DIR%" mkdir "%STAGE_DIR%"
    move /Y "%APP_DIR%\data" "%STAGE_DIR%\data" >nul || goto :err_move
)
if exist "%APP_DIR%\downloads\" (
    if not exist "%STAGE_DIR%" mkdir "%STAGE_DIR%"
    move /Y "%APP_DIR%\downloads" "%STAGE_DIR%\downloads" >nul || goto :err_move
)
echo   OK - user data staged at dist\_userdata

echo.
echo === [2/5] Installing Python dependencies ===
python -m pip install --upgrade pip >nul 2>&1
python -m pip install -r requirements.txt
if errorlevel 1 goto :err

echo.
echo === [3/5] Building the EXE with PyInstaller (one-dir) ===
python -m PyInstaller MangaCopy.spec --noconfirm --clean
if errorlevel 1 goto :err

echo.
echo === [4/5] Installing Chromium into the portable output folder ===
set "PLAYWRIGHT_BROWSERS_PATH=%APP_DIR%\playwright-browsers"
python -m playwright install chromium
if errorlevel 1 goto :err

call :restore_data

echo.
echo ============================================================
echo   Build complete!
echo   Run:        dist\MangaCopy\MangaCopy.exe
echo   To share:   copy the whole "MangaCopy" folder to any PC (no install).
echo ============================================================
goto :eof

:err_move
echo.
echo ERROR: could not stage user data - is MangaCopy.exe still running? Close it and retry.
exit /b 1

:err
echo.
echo BUILD FAILED - see the messages above.
call :restore_data
exit /b 1

:restore_data
echo === [5/5] Restoring portable app user data ===
if exist "%STAGE_DIR%\data\" (
    if not exist "%APP_DIR%" mkdir "%APP_DIR%"
    move /Y "%STAGE_DIR%\data" "%APP_DIR%\data" >nul
)
if exist "%STAGE_DIR%\downloads\" (
    if not exist "%APP_DIR%" mkdir "%APP_DIR%"
    move /Y "%STAGE_DIR%\downloads" "%APP_DIR%\downloads" >nul
)
REM plain rmdir only removes the staging dir when it is empty - never deletes data
rmdir "%STAGE_DIR%" 2>nul
exit /b 0
