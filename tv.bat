@echo off
rem Runs the tv command, rebuilding when the sources have changed:  tv.bat info ^| tv.bat key home ^| tv.bat browse ^<url^>
setlocal
call "%~dp0gradlew.bat" -q -p "%~dp0." installDist 1>&2 || exit /b 1
call "%~dp0build\install\tv\bin\tv.bat" %*
exit /b %errorlevel%
