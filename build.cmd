@echo off
setlocal
call "%~dp0gradlew.bat" %*
exit /b %ERRORLEVEL%
