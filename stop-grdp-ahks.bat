@echo off
setlocal
set "ROOT=%~dp0"
set "PORT=5150"
if /I "%~1"=="-WorkerPort" set "PORT=%~2"
set "PIDFILE=%ROOT%.grdp-worker.pid"
if not exist "%PIDFILE%" (
  echo No Worker PID file found; no process was stopped.
  exit /b 0
)
set /p PID=<"%PIDFILE%"
if not defined PID (
  del /q "%PIDFILE%" >nul 2>&1
  exit /b 0
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$h=try{Invoke-RestMethod -Uri ('http://127.0.0.1:'+%PORT%+'/api/health') -TimeoutSec 3}catch{$null}; if($h.activeRunId){exit 2}; exit 0"
if errorlevel 2 (
  echo Worker has an active Run; stop was refused.
  exit /b 2
)
taskkill /PID %PID% /T /F >nul 2>&1
del /q "%PIDFILE%" >nul 2>&1
echo Worker process %PID% stopped.
exit /b 0
