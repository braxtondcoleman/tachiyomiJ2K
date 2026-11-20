#requires -Version 5.1
<#
Create/reuse a permanent LOCAL user-owned signing key and build a separately
installed custom J2K release. No uninstall, install, or upload.
Run from repository root: .\scripts\build_stable_j2k.ps1
#>
[CmdletBinding()]
param(
    [string]$KeystorePath = (Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'J2K Signing\j2k-user-owned.jks'),
    [string]$KeyAlias = 'j2k',
    [int]$VersionCode = 115
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
if (-not $sdk) { throw 'Set ANDROID_HOME to the Android SDK folder.' }
if (-not (Test-Path (Join-Path $repoRoot 'gradlew.bat'))) { throw 'The complete repository and Gradle wrapper are required.' }
$buildTools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory |
    Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending |
    Select-Object -First 1
if (-not $buildTools) { throw 'Android build-tools not installed.' }
$aapt = Join-Path $buildTools.FullName 'aapt.exe'
$signer = Join-Path $buildTools.FullName 'apksigner.bat'
if (-not (Test-Path $aapt) -or -not (Test-Path $signer)) { throw 'aapt or apksigner missing.' }
if ($VersionCode -lt 114) { throw 'Version code must be at least 114.' }

$oldEnv = @{
    J2K_RELEASE_KEYSTORE = $env:J2K_RELEASE_KEYSTORE
    J2K_STORE_PASSWORD = $env:J2K_STORE_PASSWORD
    J2K_KEY_ALIAS = $env:J2K_KEY_ALIAS
    J2K_KEY_PASSWORD = $env:J2K_KEY_PASSWORD
    J2K_RELEASE_VERSION_CODE = $env:J2K_RELEASE_VERSION_CODE
}
try {
    $parent = Split-Path $KeystorePath -Parent
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    $storeSecret = Read-Host 'Signing keystore password (private)' -AsSecureString
    $keySecret = Read-Host 'Signing key password (private)' -AsSecureString
    $p1 = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($storeSecret)
    $p2 = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($keySecret)
    try {
        $env:J2K_STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($p1)
        $env:J2K_KEY_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($p2)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p1)
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p2)
    }
    $keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\keytool.exe' } else { 'keytool' }
    if ($env:JAVA_HOME -and -not (Test-Path $keytool)) { $keytool = 'keytool' }
    if (-not (Test-Path -LiteralPath $KeystorePath)) {
        Write-Host 'Creating permanent user-owned signing key. Keep this JKS and passwords backed up offline.'
        $keyArgs = @('-genkeypair','-noprompt','-keystore',$KeystorePath,
          '-storepass:env','J2K_STORE_PASSWORD','-keypass:env','J2K_KEY_PASSWORD',
          '-alias',$KeyAlias,'-keyalg','RSA','-keysize','4096','-validity','10000',
          '-dname','CN=Personal J2K Custom Release,O=User Owned,C=US')
        & $keytool @keyArgs
        if ($LASTEXITCODE -ne 0) { throw 'Key generation failed; do not delete existing installations.' }
    } else {
        Write-Host 'Reusing existing key; the JKS file will never be overwritten.'
        & $keytool '-list' '-keystore' $KeystorePath '-storepass:env' 'J2K_STORE_PASSWORD' '-alias' $KeyAlias
        if ($LASTEXITCODE -ne 0) { throw 'Could not verify the existing key.' }
    }
    $env:J2K_RELEASE_KEYSTORE = (Resolve-Path -LiteralPath $KeystorePath).Path
    $env:J2K_KEY_ALIAS = $KeyAlias
    $env:J2K_RELEASE_VERSION_CODE = [string]$VersionCode
    Push-Location $repoRoot
    try {
        & (Join-Path $repoRoot 'gradlew.bat') ':app:testStandardReleaseUnitTest' ':app:assembleStandardRelease' '--stacktrace'
        if ($LASTEXITCODE -ne 0) { throw 'Release build/tests failed; no APK is approved.' }
    } finally { Pop-Location }
    $outputDir = Join-Path $repoRoot 'app\build\outputs\apk\standard\release'
    $apk = Get-ChildItem -LiteralPath $outputDir -File -Filter '*.apk' |
        Where-Object { $_.Name -match 'universal.*release|release.*universal' } |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if (-not $apk) { throw 'No universal signed release APK was generated.' }
    $identity = & $aapt dump badging $apk.FullName | Select-String '^package:' | Select-Object -First 1
    if ($LASTEXITCODE -ne 0 -or -not $identity -or $identity.Line -notmatch "name='eu.kanade.tachiyomi.customj2k'") {
        throw 'The APK has an unexpected package identity.'
    }
    if ($identity.Line -notmatch "versionCode='$VersionCode'") { throw 'The APK versionCode does not match.' }
    & $signer verify --verbose --print-certs $apk.FullName
    if ($LASTEXITCODE -ne 0) { throw 'APK signing verification failed.' }
    $destination = Join-Path $repoRoot 'update-apk'
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    $target = Join-Path $destination ("J2K-stable-release-v$VersionCode.apk")
    Copy-Item -LiteralPath $apk.FullName -Destination $target -Force
    Write-Host ("PASS: New custom app APK: " + $target) -ForegroundColor Green
    Write-Host ("Private signing key: " + $KeystorePath)
    Write-Host 'IMPORTANT: This is a NEW, separate app. Export a J2K backup and restore it into the new app BEFORE considering uninstalling any existing copy.'
    Write-Host 'Future updates must reuse this JKS and increment versionCode.'
} finally {
    foreach ($name in $oldEnv.Keys) { Set-Item -Path ('Env:' + $name) -Value ([string]$oldEnv[$name]) }
}
