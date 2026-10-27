param([string]$MavenCommand)
$ErrorActionPreference = 'Stop'
# Only this invocation selects cloud mode; do not rewrite backend/.env or the shell's profile.
$taskPreviousProfiles = $env:SPRING_PROFILES_ACTIVE
try {
    $env:SPRING_PROFILES_ACTIVE = 'neon'
    Write-Host 'Explicit Neon startup: application URL from .env.local; Flyway uses its direct URL.'
    & "$PSScriptRoot\run.ps1" -MavenCommand $MavenCommand -BuildDirectory (Join-Path $PSScriptRoot 'target\neon')
} finally {
    $env:SPRING_PROFILES_ACTIVE = $taskPreviousProfiles
}
