@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

echo ====================================================================
echo   KidsGuard Pro — Сборка Windows .exe (Агент ПК + Родитель ПК)
echo ====================================================================
echo.

where python >nul 2>&1
if errorlevel 1 (
    echo [ОШИБКА] Python не найден в PATH. Установите Python 3.10+ с python.org
    echo и отметьте галочку "Add Python to PATH".
    pause
    exit /b 1
)

echo [1/4] Установка зависимостей (pillow, pywebview, pyinstaller)...
python -m pip install --upgrade pip
python -m pip install pillow pywebview pyinstaller
if errorlevel 1 (
    echo [ОШИБКА] Не удалось установить зависимости через pip.
    pause
    exit /b 1
)

echo.
echo [2/4] Сборка hosts_blocker.exe (Агент на ПК ребёнка)...
echo ВАЖНО: без --uac-admin, чтобы автозапуск агента пользователя работал без UAC.
python -m PyInstaller --noconfirm --clean --noconsole --onefile --name hosts_blocker hosts_blocker.py
if errorlevel 1 (
    echo [ОШИБКА] Не удалось собрать hosts_blocker.exe.
    pause
    exit /b 1
)

echo.
echo [3/4] Сборка KidsGuardParent.exe (Программа родителя под Windows)...
python -m PyInstaller --noconfirm --clean --noconsole --onefile --name KidsGuardParent kidsguard_parent.py
if errorlevel 1 (
    echo [ОШИБКА] Не удалось собрать KidsGuardParent.exe.
    pause
    exit /b 1
)

echo.
echo [4/4] Готово! Собранные файлы находятся в папке dist\:
if exist "dist\hosts_blocker.exe"   echo   - dist\hosts_blocker.exe     (на ПК ребёнка, первый запуск от администратора)
if exist "dist\KidsGuardParent.exe" echo   - dist\KidsGuardParent.exe   (на ПК родителя, обычный запуск)
echo.
pause
