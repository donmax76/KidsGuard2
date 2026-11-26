@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0"

if exist "dist\KidsGuardParent.exe" (
    start "" "dist\KidsGuardParent.exe"
    exit /b 0
)

set "PY_CMD="
where pythonw >nul 2>&1
if not errorlevel 1 set "PY_CMD=pythonw"

if "%PY_CMD%"=="" (
    where python >nul 2>&1
    if not errorlevel 1 set "PY_CMD=python"
)

if "%PY_CMD%"=="" (
    for %%V in (313 312 311 310) do (
        if exist "%LOCALAPPDATA%\Programs\Python\Python%%V\python.exe" (
            set "PY_CMD=%LOCALAPPDATA%\Programs\Python\Python%%V\python.exe"
            goto :launch_py
        )
    )
)

if "%PY_CMD%"=="" (
    echo Python не найден. Запустите сначала build_windows_exes.bat — он сам установит Python и соберёт KidsGuardParent.exe.
    pause
    exit /b 1
)

:launch_py
start "" %PY_CMD% "%~dp0kidsguard_parent.py"
exit /b 0
