$ErrorActionPreference = "Stop"

$envFile = Join-Path $PSScriptRoot ".env.local.ps1"
if (Test-Path -LiteralPath $envFile) {
    . $envFile
} else {
    Write-Warning "OSS env file not found: $envFile"
}

Push-Location $PSScriptRoot
try {
    mvn -q compile exec:java
} finally {
    Pop-Location
}
