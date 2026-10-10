@echo off
setlocal
pushd "%~dp0"
where node >nul 2>nul
if errorlevel 1 (
  echo Install Node.js 22 or newer and run this launcher again.
  pause
  exit /b 1
)
if not exist node_modules (
  call npm.cmd ci --no-audit --no-fund
  if errorlevel 1 (
    pause
    exit /b 1
  )
)
node server.cjs --open
if errorlevel 1 pause
popd
