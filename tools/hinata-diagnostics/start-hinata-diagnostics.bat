@echo off
cd /d "%~dp0"
where node >nul 2>nul
if errorlevel 1 (
  echo Node.js was not found in PATH.
  pause
  exit /b 1
)
echo Keep this window open while using the diagnostics and local relay.
node relay-server.mjs
pause
