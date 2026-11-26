@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ====================================================================
echo   KidsGuard Pro — Автосборка Windows .exe (Агент ПК + Родитель ПК)
echo ====================================================================
echo.

set "PY_CMD="
where python >nul 2>&1
if not errorlevel 1 (
    python -c "import sys; raise SystemExit(0 if sys.version_info >= (3,9) else 1)" >nul 2>&1
    if not errorlevel 1 set "PY_CMD=python"
)

if "%PY_CMD%"=="" (
    where py >nul 2>&1
    if not errorlevel 1 (
        py -3 -c "import sys; raise SystemExit(0 if sys.version_info >= (3,9) else 1)" >nul 2>&1
        if not errorlevel 1 set "PY_CMD=py -3"
    )
)

if "%PY_CMD%"=="" (
    for %%V in (313 312 311 310 39) do (
        if exist "%LOCALAPPDATA%\Programs\Python\Python%%V\python.exe" (
            set "PY_CMD=%LOCALAPPDATA%\Programs\Python\Python%%V\python.exe"
            goto :found_python
        )
    )
)

if "%PY_CMD%"=="" (
    echo [0/4] Python 3 не найден. Пробуем автоматически установить Python 3.12 через winget...
    where winget >nul 2>&1
    if not errorlevel 1 (
        winget install -e --id Python.Python.3.12 --accept-package-agreements --accept-source-agreements --silent
        for %%V in (312 313 311 310) do (
            if exist "%LOCALAPPDATA%\Programs\Python\Python%%V\python.exe" (
                set "PY_CMD=%LOCALAPPDATA%\Programs\Python\Python%%V\python.exe"
                goto :found_python
            )
        )
        where python >nul 2>&1
        if not errorlevel 1 set "PY_CMD=python"
    )
)

:found_python
if "%PY_CMD%"=="" (
    echo [ОШИБКА] Не удалось найти или автоматически установить Python 3.
    echo Установите Python 3.12 вручную с https://www.python.org/downloads/
    echo (обязательно отметьте "Add Python to PATH") и запустите этот файл снова.
    pause
    exit /b 1
)

echo Найден Python: %PY_CMD%
echo.

echo [1/4] Установка библиотек (pillow, pywebview, pyinstaller)...
%PY_CMD% -m pip install --upgrade pip
%PY_CMD% -m pip install pillow pywebview pyinstaller
if errorlevel 1 (
    echo [ОШИБКА] Не удалось установить зависимости через pip. Проверьте интернет-соединение.
    pause
    exit /b 1
)

echo.
echo [2/4] Сборка hosts_blocker.exe (Агент на ПК ребёнка)...
echo ВАЖНО: сборка без --uac-admin, чтобы автозапуск в профиле ребёнка работал без окон UAC.
%PY_CMD% -m PyInstaller --noconfirm --clean --noconsole --onefile --name hosts_blocker hosts_blocker.py
if errorlevel 1 (
    echo [ОШИБКА] Не удалось собрать hosts_blocker.exe.
    pause
    exit /b 1
)

echo.
echo [3/4] Сборка KidsGuardParent.exe (Программа родителя для Windows)...
%PY_CMD% -m PyInstaller --noconfirm --clean --noconsole --onefile --name KidsGuardParent kidsguard_parent.py
if errorlevel 1 (
    echo [ОШИБКА] Не удалось собрать KidsGuardParent.exe.
    pause
    exit /b 1
)

echo.
echo ====================================================================
echo [4/4] ГОТОВО! Оба .exe файла созданы в папке dist\:
if exist "dist\hosts_blocker.exe"   echo   1. dist\hosts_blocker.exe     — на ПК ребёнка (первый запуск от администратора)
if exist "dist\KidsGuardParent.exe" echo   2. dist\KidsGuardParent.exe   — на ПК родителя (обычный запуск)
echo ====================================================================
echo.
if exist "dist" start "" explorer "%~dp0dist"
pause
