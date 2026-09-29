@echo off
setlocal
cd /d "%~dp0"

if not exist ".venv\Scripts\python.exe" (
    echo Creating the local Python environment...
    where py >nul 2>nul
    if not errorlevel 1 (
        py -3 -m venv .venv
    ) else (
        python -m venv .venv
    )
    if errorlevel 1 (
        echo Python 3.10 or newer is required. Install it from https://www.python.org/downloads/
        pause
        exit /b 1
    )
)

if not exist ".env" (
    copy /y ".env.example" ".env" >nul
    echo Created .env. Add provider tokens there only when needed.
)

".venv\Scripts\python.exe" "minecraft_server_hosting_tool.py" %*
if errorlevel 1 pause
