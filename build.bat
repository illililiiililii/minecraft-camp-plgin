@echo off
cd /d "%~dp0"
where mvn >nul 2>nul
if errorlevel 1 (
  echo Install Apache Maven 3.9+ and Java JDK 21+ first.
  pause
  exit /b 1
)
call mvn -B -ntp clean verify
pause
