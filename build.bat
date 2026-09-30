@echo off
cd /d "%~dp0"
if exist out rmdir /s /q out
mkdir out
dir /s /b src\main\java\*.java > out\sources.txt
javac --release 17 -d out @out\sources.txt || exit /b 1
xcopy /e /i /q src\main\resources\web out\web >nul
echo Main-Class: mcht.Main> out\MANIFEST.MF
jar cfm mc-host.jar out\MANIFEST.MF -C out mcht -C out web
rmdir /s /q out
echo Built mc-host.jar
