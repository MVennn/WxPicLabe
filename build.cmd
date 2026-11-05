@echo off
setlocal EnableDelayedExpansion

REM ---------------------------------------------------------------------------
REM  WxPicLabel - build script
REM
REM  The sandbox denies writes to %TEMP% and the user .gradle dir, so every
REM  cache is redirected into the project itself. No admin rights needed.
REM
REM  Android SDK resolution order (no absolute path is hardcoded here):
REM    1. ANDROID_SDK_ROOT / ANDROID_HOME already set by the caller (must exist)
REM    2. <repo>\.work\android-sdk (repo-local SDK)
REM    3. otherwise leave it to Gradle, which reads sdk.dir from local.properties
REM  Keep this file ASCII-only: cmd.exe reads .cmd in the OEM code page.
REM ---------------------------------------------------------------------------

cd /d "%~dp0"
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

set "GRADLE_USER_HOME=%ROOT%\.gradle-home"
set "TMP=%ROOT%\.tmp"
set "TEMP=%ROOT%\.tmp"
set "TMPDIR=%ROOT%\.tmp"
set "ANDROID_USER_HOME=%ROOT%\.android-home"

if not exist "%TMP%" mkdir "%TMP%"
if not exist "%ANDROID_USER_HOME%" mkdir "%ANDROID_USER_HOME%"
if not exist "%GRADLE_USER_HOME%" mkdir "%GRADLE_USER_HOME%"

if not defined ANDROID_SDK_ROOT (
    if defined ANDROID_HOME if exist "%ANDROID_HOME%" set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
)
if not defined ANDROID_SDK_ROOT (
    if exist "%ROOT%\.work\android-sdk" set "ANDROID_SDK_ROOT=%ROOT%\.work\android-sdk"
)

set "GRADLE_BIN=%USERPROFILE%\.gradle\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat"
if not exist "%GRADLE_BIN%" (
    echo [ERROR] gradle 8.11.1 not found at:
    echo         %GRADLE_BIN%
    echo         Run gradlew once to download the distribution first.
    exit /b 1
)

echo [build] GRADLE_USER_HOME=%GRADLE_USER_HOME%
if defined ANDROID_SDK_ROOT (
    echo [build] SDK=%ANDROID_SDK_ROOT%
) else (
    echo [build] SDK=sdk.dir from local.properties
)
call "%GRADLE_BIN%" :app:assembleDebug --no-daemon --console=plain %*
if errorlevel 1 (
    echo [FAILED] build error
    exit /b 1
)

echo [ok] APK: %ROOT%\app\build\outputs\apk\debug\app-debug.apk
endlocal
