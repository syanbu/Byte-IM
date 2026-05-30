$ErrorActionPreference = "Stop"

# Load OSS local env file if present
$envFile = Join-Path $PSScriptRoot ".env.local.ps1"
if (Test-Path -LiteralPath $envFile) {
    . $envFile
} else {
    Write-Warning "OSS env file not found: $envFile"
}

# Android SDK adb path
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"

if (-not (Test-Path -LiteralPath $adb)) {
    throw "adb.exe not found at: $adb"
}

# Physical Android device serials
$deviceSerials = @(
    "AQWPUT4518005994"
)

$localPort = 8080
$remotePort = 8080

Write-Host "Checking connected Android devices..."
& $adb devices

foreach ($deviceSerial in $deviceSerials) {
    Write-Host ""
    Write-Host "Setting adb reverse for device $deviceSerial : phone tcp:$remotePort -> computer tcp:$localPort"

    & $adb -s $deviceSerial reverse tcp:$remotePort tcp:$localPort

    Write-Host "Current adb reverse rules for device $deviceSerial :"
    & $adb -s $deviceSerial reverse --list
}

Write-Host ""
Write-Host "Starting mock server..."
Push-Location $PSScriptRoot
try {
    mvn -q compile exec:java
} finally {
    Pop-Location
}