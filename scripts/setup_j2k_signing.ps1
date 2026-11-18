# One-time permanent J2K Custom signing setup for Windows.
# Creates a private keystore outside Git, then writes GitHub Actions secrets.
# Requires Java keytool and GitHub CLI (run: gh auth login once).
[CmdletBinding()]
param(
    [string]$Repository = 'braxtondcoleman/tachiyomiJ2K',
    [string]$KeystorePath = (Join-Path $HOME 'J2K-Custom.jks'),
    [string]$Alias = 'j2kcustom'
)
$ErrorActionPreference = 'Stop'
if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw 'Repository must be OWNER/REPO.' }
if ($Alias -notmatch '^[A-Za-z0-9_-]+$') { throw 'Unsafe signing alias.' }
$keytool = (Get-Command keytool -ErrorAction Stop).Source
$gh = (Get-Command gh -ErrorAction Stop).Source
& $gh auth status --hostname github.com | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Run gh auth login first.' }

function Set-ActionSecret {
    param([string]$Name, [string]$Value)
    # Use redirected standard input; secret values never appear in command arguments.
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $gh
    $start.Arguments = "secret set $Name --repo $Repository"
    $start.UseShellExecute = $false
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.CreateNoWindow = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw "Unable to start GitHub CLI for $Name" }
        $process.StandardInput.Write($Value)
        $process.StandardInput.Close()
        $out = $process.StandardOutput.ReadToEnd()
        $err = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw "Could not configure $Name; gh exit $($process.ExitCode): $err" }
        Write-Host "Configured $Name"
    } finally {
        $process.Dispose()
    }
}

$existing = Test-Path -LiteralPath $KeystorePath -PathType Leaf
if ($existing) {
    Write-Host "Reusing existing keystore at $KeystorePath. It will NOT be replaced."
} else {
    Write-Host "Creating a NEW private signing key at $KeystorePath."
}
$securePassword = Read-Host 'Signing password (12+ characters; save it offline)' -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
}
if ($password.Length -lt 12) { throw 'Use a password with at least 12 characters.' }
$env:J2K_SETUP_STOREPASS = $password
try {
    if (-not $existing) {
        & $keytool -genkeypair -noprompt -storetype PKCS12 -keystore $KeystorePath -alias $Alias -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=J2K Custom,O=Personal,C=US' -storepass:env J2K_SETUP_STOREPASS -keypass:env J2K_SETUP_STOREPASS
        if ($LASTEXITCODE -ne 0) { throw 'keytool could not create the keystore.' }
    }
    $detail = & $keytool -list -v -keystore $KeystorePath -alias $Alias -storepass:env J2K_SETUP_STOREPASS
    if ($LASTEXITCODE -ne 0) { throw 'Could not read keystore. Verify password and alias.' }
    $match = [regex]::Match(($detail -join [Environment]::NewLine), '(?im)^\s*SHA256:\s*([0-9a-f:]+)\s*$')
    if (-not $match.Success) { throw 'Unable to find certificate SHA256 fingerprint.' }
    $fingerprint = $match.Groups[1].Value.Replace(':', '').ToLowerInvariant()
    if ($fingerprint -notmatch '^[a-f0-9]{64}$') { throw 'Malformed certificate SHA256 fingerprint.' }
    $keystoreBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($KeystorePath))
    Set-ActionSecret 'J2K_SIGNING_KEY_B64' $keystoreBase64
    Set-ActionSecret 'J2K_STORE_PASSWORD' $password
    Set-ActionSecret 'J2K_KEY_ALIAS' $Alias
    Set-ActionSecret 'J2K_KEY_PASSWORD' $password
    Set-ActionSecret 'J2K_SIGNING_CERT_SHA256' $fingerprint
    Write-Host 'All five GitHub Actions signing secrets configured.'
    Write-Host "Public certificate SHA256: $fingerprint"
    Write-Host 'Save an encrypted offline copy of this keystore AND its password.'
    Write-Host 'Never commit the keystore or passwords. Reuse this key for every future release.'
} finally {
    Remove-Item Env:\J2K_SETUP_STOREPASS -ErrorAction SilentlyContinue
    $password = $null
    $keystoreBase64 = $null
}
