@echo off
cd /d "%~dp0"
java -XX:ReservedCodeCacheSize=256m -jar "%~dp0target\qrcodeForwin-1.0-SNAPSHOT.jar"
pause
