@echo off
setlocal
cd /d "%~dp0"
echo.
echo =====================================================
echo  J2K Custom - permanent signing and new APK
echo =====================================================
echo.
echo This will NOT modify, delete, or uninstall your current app.
echo The first custom stable APK installs as a SEPARATE app.
echo Make an in-app J2K backup before moving any library data.
echo Signing key is stored locally in Documents\J2K Signing.
echo Keep a copy of that key and its password somewhere safe.
echo.
set /p "J2K_VER=Version code [115 first time, increase for later releases]: "
if "%J2K_VER%"=="" set "J2K_VER=115"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build_stable_j2k.ps1" -VersionCode %J2K_VER%
if errorlevel 1 (
    echo.
    echo BUILD FAILED OR WAS BLOCKED. No APK is approved for installation.
    echo Preserve the current J2K app and your existing backup.
) else (
    echo.
    echo A signed APK was prepared under update-apk\.
    echo Do not uninstall old J2K; verify backup restore in the new custom app.
)
echo.
pause
