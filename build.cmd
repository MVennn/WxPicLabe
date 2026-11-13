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
REM  caller may export a stale path: drop it so Gradle falls back to sdk.dir
if defined ANDROID_SDK_ROOT if not exist "%ANDROID_SDK_ROOT%" echo [build] ANDROID_SDK_ROOT=%ANDROID_SDK_ROOT% does not exist, ignored.
if defined ANDROID_SDK_ROOT if not exist "%ANDROID_SDK_ROOT%" set "ANDROID_SDK_ROOT="
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
REM ---------------------------------------------------------------------------
REM  Target variant:  build.cmd          -> release (default, R8 on; the one to install)
REM                  build.cmd debug    -> debug (same R8 + performance profile as release)
REM  Everything after the variant word is forwarded to Gradle.
REM ---------------------------------------------------------------------------
REM  %* 不受 shift 影响，所以这里手动把变体词摘掉、其余参数逐个收集
REM  Default is release on purpose: release is the product artifact. Since 2026-11-13 the
REM  debug variant is built with isDebuggable=false + R8 too, so it no longer drops frames.
REM  Need a REAL debuggable build (breakpoints / run-as / readable stack):
REM    gradlew :app:assembleDebug -Pwx.debuggable=true -Pwx.minifyDebug=false
set "VARIANT=release"
if /I "%~1"=="release" (
    shift
) else if /I "%~1"=="debug" (
    set "VARIANT=debug"
    shift
)

set "EXTRA="
:collect_args
if "%~1"=="" goto args_done
set "EXTRA=%EXTRA% %1"
shift
goto collect_args
:args_done
set "TASK=:app:assembleDebug"
set "APK=%ROOT%\app\build\outputs\apk\debug\app-debug.apk"
if /I "%VARIANT%"=="release" (
    set "TASK=:app:assembleRelease"
    set "APK=%ROOT%\app\build\outputs\apk\release\app-release.apk"
)

echo [build] variant=%VARIANT%
call "%GRADLE_BIN%" %TASK% --no-daemon --console=plain %EXTRA%
if errorlevel 1 (
    echo [FAILED] build error
    exit /b 1
)

echo [ok] APK: %APK%
endlocal
