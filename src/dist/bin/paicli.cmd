@echo off
setlocal
set "PAICLI_JAR=%~dp0..\lib\paicli-1.2.0.jar"
if not exist "%PAICLI_JAR%" (
  >&2 echo PaiCLI launcher error: distribution JAR not found.
  exit /b 2
)
where java >nul 2>nul
if errorlevel 1 (
  >&2 echo PaiCLI launcher error: Java 21 is not available on PATH.
  exit /b 2
)
java -jar "%PAICLI_JAR%" %*
set "PAICLI_EXIT=%ERRORLEVEL%"
endlocal & exit /b %PAICLI_EXIT%
