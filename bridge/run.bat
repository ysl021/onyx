@echo off
setlocal
cd /d "%~dp0"

python -c "import pypresence" 2>nul
if errorlevel 1 (
    echo Installing pypresence...
    python -m pip install --quiet pypresence
)

echo.
echo ONYX Discord bridge. Keep this window open while you listen.
echo Phone and PC must be on the same Wi-Fi network.
echo.
python onyx_bridge.py %*
pause
