@echo off
setlocal
set VER=9.6.0
set ROOT=%~dp0
set BASE=%ROOT%.gradle-dist
set ZIP=%BASE%\gradle-%VER%-bin.zip
set GH=%BASE%\gradle-%VER%
if not exist "%GH%\bin\gradle.bat" (
  if not exist "%BASE%" mkdir "%BASE%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%VER%-bin.zip' -OutFile '%ZIP%'"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%ZIP%' -DestinationPath '%BASE%' -Force"
)
call "%GH%\bin\gradle.bat" %*
