@echo off
setlocal
cd /d "%~dp0"

call gradlew.bat runClient
set "exitCode=%ERRORLEVEL%"

if not "%exitCode%"=="0" (
    echo.
    echo Client launch failed with exit code %exitCode%.
    pause
)

exit /b %exitCode%
