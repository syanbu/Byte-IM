$ErrorActionPreference = "Stop"

$envFile = Join-Path $PSScriptRoot ".env.local.ps1"
if (Test-Path -LiteralPath $envFile) {
    . $envFile
} else {
    Write-Warning "OSS env file not found: $envFile"
}

Push-Location $PSScriptRoot
try {
    mvn -q -Dexec.mainClass=com.codex.imserver.tools.MockFriendSeeder compile exec:java
} finally {
    Pop-Location
}
