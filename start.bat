@echo off
cd /d "%~dp0"
where java >nul 2>nul || (echo Java 17 or newer is required: https://adoptium.net & pause & exit /b 1)
if not exist mc-host.jar call build.bat
if not exist .env copy .env.example .env >nul
java -jar mc-host.jar %*
pause
