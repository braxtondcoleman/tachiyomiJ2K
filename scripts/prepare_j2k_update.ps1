#requires -Version 5.1
<#
Build a J2K Android update ONLY if it can replace an already installed custom
debug APK without changing Android package identity or signing certificate.
No uninstall, adb install, or private key upload. Windows PowerShell 5.1+.

Example:
  .\scripts\prepare_j2k_update.ps1 -KeystorePath "C:\Keys\j2k-user-owned.jks" -KeyAlias "j2k"

Requires: connected authorized Android device with USB debugging, Android SDK,
JDK 21, Gradle wrapper and the SAME user-owned keystore that signed the installed
eu.kanade.tachiyomi.customj2k.debug. A CI-generated debug key is not recoverable.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [string]$KeystorePath,

    [Parameter(Mandatory=$true)]
    [string]$KeyAlias,

    [string]$PackageName = 'eu.kanade.tachiyomi.customj2k.debug'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($PackageName -ne 'eu.kanade.tachiyomi.customj2k.debug') {
    throw 'This safety-checked updater only targets the dedicated custom J2K debug package. It will not overwrite original J2K or an unverified release package.'
}
if (-not (Test-Path -LiteralPath $KeystorePath -PathType Leaf)) {
    throw 'Keystore not found. Do not generate a replacement key: Android requires the original signing certificate for an in-place update. Keep the existing app and make a backup.'
}
$KeystorePath = (Resolve-Path -LiteralPath $KeystorePath).Path
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$gradle = Join-Path $repoRoot 'gradlew.bat'
if (-not (Test-Path $gradle)) { throw 'Gradle wrapper not found. Run this script from a full repository checkout.' }

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $null }
if (-not $sdk) { throw 'Set ANDROID_HOME or ANDROID_SDK_ROOT to your Android SDK folder.' }
$tools = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
    Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending |
    Select-Object -First 1
if (-not $tools) { throw 'Android build-tools are not installed.' }
$aapt = Join-Path $tools.FullName 'aapt.exe'
$signer = Join-Path $tools.FullName 'apksigner.bat'
if (-not (Test-Path $aapt) -or -not (Test-Path $signer)) {
    throw 'Android build-tools are missing aapt.exe or apksigner.bat.'
}
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw 'Android platform-tools (adb) are missing.' }

function Invoke-Checked([string]$command, [string[]]$commandArgs) {
    $output = & $command @commandArgs 2>&1
    if ($LASTEXITCODE -ne 0) { throw ('Command failed (' + (Split-Path $command -Leaf) + '): ' + ($output -join [Environment]::NewLine)) }
    return $output
}
function Get-SigningCertificate([string]$apkFile) {
    $lines = Invoke-Checked $signer @('verify','--print-certs', $apkFile)
    $found = ($lines | Select-String -Pattern 'certificate SHA-256 digest:\s*([A-Fa-f0-9:]+)' | Select-Object -First 1)
    if (-not $found) { throw 'Unable to read APK signer certificate SHA-256.' }
    return ($found.Matches[0].Groups[1].Value -replace ':','').ToLowerInvariant()
}
function Get-ApkIdentity([string]$apkFile) {
    $lines = Invoke-Checked $aapt @('dump','badging',$apkFile)
    $info = $lines | Where-Object { $_ -match '^package:\s' } | Select-Object -First 1
    if (-not $info -or $info -notmatch "name='([^']+)'") { throw "Unable to read package name from $apkFile" }
    $name = $Matches[1]
    if ($info -notmatch "versionCode='([0-9]+)'") { throw "Unable to read APK versionCode from $apkFile" }
    return [pscustomobject]@{ PackageName=$name; VersionCode=[int]$Matches[1] }
}

$temp = Join-Path ([IO.Path]::GetTempPath()) ('j2k-update-check-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
$oldEnv = @{
    J2K_RELEASE_KEYSTORE = $env:J2K_RELEASE_KEYSTORE
    J2K_STORE_PASSWORD = $env:J2K_STORE_PASSWORD
    J2K_KEY_ALIAS = $env:J2K_KEY_ALIAS
    J2K_KEY_PASSWORD = $env:J2K_KEY_PASSWORD
    J2K_RELEASE_VERSION_CODE = $env:J2K_RELEASE_VERSION_CODE
}
try {
    $devices = Invoke-Checked $adb @('devices')
    $connected = @($devices | Where-Object { $_ -match '\sdevice\s*$' })
    if ($connected.Count -ne 1) { throw 'Connect exactly one unlocked Android device with USB debugging authorized.' }

    $paths = Invoke-Checked $adb @('shell', 'pm', 'path', $PackageName)
    $base = $paths | Where-Object { $_ -match '^package:.*base\.apk\s*$' } | Select-Object -First 1
    if (-not $base) { throw "The expected $PackageName app is not installed. No update can be verified; DO NOT uninstall your other J2K app." }
    $remote = ($base -replace '^package:', '').Trim()
    $oldApk = Join-Path $temp 'currently-installed.apk'
    [void](Invoke-Checked $adb @('pull', $remote, $oldApk))
    $oldIdentity = Get-ApkIdentity $oldApk
    $oldCert = Get-SigningCertificate $oldApk
    if ($oldIdentity.PackageName -ne $PackageName) { throw 'Installed APK package identity mismatch.' }

    $secureStore = Read-Host 'Existing keystore password (will not be printed)' -AsSecureString
    $secureKey = Read-Host 'Existing signing key password (will not be printed)' -AsSecureString
    $ptrStore = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureStore)
    $ptrKey = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
    try {
        $env:J2K_STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptrStore)
        $env:J2K_KEY_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptrKey)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptrStore)
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptrKey)
    }
    $env:J2K_RELEASE_KEYSTORE = $KeystorePath
    $env:J2K_KEY_ALIAS = $KeyAlias
    $env:J2K_RELEASE_VERSION_CODE = [string][Math]::Max(114, $oldIdentity.VersionCode + 1)

    Write-Host ('Installed: ' + $oldIdentity.PackageName + ' v' + $oldIdentity.VersionCode)
    Write-Host ('Signing certificate SHA-256: ' + $oldCert)
    Write-Host ('Building versionCode ' + $env:J2K_RELEASE_VERSION_CODE + ' with the existing keystore...')
    Push-Location $repoRoot
    try {
        & $gradle ':app:testStandardDebugUnitTest' ':app:assembleStandardDebug' '--stacktrace'
        if ($LASTEXITCODE -ne 0) { throw 'Android Gradle build or tests failed. No APK is approved.' }
    } finally { Pop-Location }

    $outputDir = Join-Path $repoRoot 'app\build\outputs\apk\standard\debug'
    $built = Get-ChildItem -LiteralPath $outputDir -File -Filter '*.apk' |
        Where-Object { $_.Name -match 'universal.*debug|debug.*universal' } |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if (-not $built) { throw 'No universal debug APK found after Gradle build.' }
    $newIdentity = Get-ApkIdentity $built.FullName
    $newCert = Get-SigningCertificate $built.FullName
    if ($newIdentity.PackageName -ne $oldIdentity.PackageName) { throw 'BLOCKED: APK package name differs from the installed app.' }
    if ($newIdentity.VersionCode -le $oldIdentity.VersionCode) { throw 'BLOCKED: APK versionCode is not newer.' }
    if ($newCert -ne $oldCert) {
        throw 'BLOCKED: signing certificate differs. This APK cannot update the installed app. DO NOT uninstall anything. Preserve the backup and locate the original signing key.'
    }

    $releaseDir = Join-Path $repoRoot 'update-apk'
    New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null
    $target = Join-Path $releaseDir ('J2K-update-v' + $newIdentity.VersionCode + '.apk')
    Copy-Item -LiteralPath $built.FullName -Destination $target -Force
    Write-Host ('PASS: update-ready APK: ' + $target) -ForegroundColor Green
    Write-Host 'Package, signing certificate and versionCode match an Android in-place update.'
    Write-Host 'No APK was installed. Back up your manga library first; then you may manually update.'
} finally {
    foreach ($key in $oldEnv.Keys) {
        Set-Item -Path ('Env:' + $key) -Value ([string]$oldEnv[$key])
    }
    if (Test-Path -LiteralPath $temp) {
        Remove-Item -LiteralPath $temp -Recurse -Force
    }
}
