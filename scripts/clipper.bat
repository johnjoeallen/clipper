@echo off
setlocal

where java >nul 2>&1
if %errorlevel% neq 0 (
    echo Error: Java 17 or later is required.
    echo Download from https://adoptium.net
    exit /b 1
)

for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVA_VER=%%v
)
set JAVA_VER=%JAVA_VER:"=%
for /f "delims=." %%m in ("%JAVA_VER%") do set JAVA_MAJOR=%%m
if %JAVA_MAJOR% LSS 17 (
    echo Error: Java 17 or later is required ^(found Java %JAVA_MAJOR%^).
    echo Download from https://adoptium.net
    exit /b 1
)

java -jar "%~dp0clipper.jar" %*
