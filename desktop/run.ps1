param([string]$MavenCommand)
$ErrorActionPreference = 'Stop'

# The script's directory determines the module, regardless of the terminal's current directory.
Push-Location $PSScriptRoot
try {
    if (-not $MavenCommand) {
        $taskMaven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
        if ($taskMaven) { $MavenCommand = $taskMaven.Source }
        else {
            $taskKnownMaven = Join-Path $env:USERPROFILE 'Downloads\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin\mvn.cmd'
            if (Test-Path -LiteralPath $taskKnownMaven) { $MavenCommand = $taskKnownMaven }
            else { throw 'Maven was not found. Add Maven to PATH or pass -MavenCommand C:\path\to\mvn.cmd.' }
        }
    }
    Write-Host "Building and launching Park Lanka from $PSScriptRoot"
    Write-Host 'Use the NEW Park Lanka window. The login footer and terminal show its build stamp.'
    Write-Host 'Existing windows keep their old classes; this script does not close them or touch the database.'
    # A packaged app under target/windows keeps its bundled runtime files locked
    # while running. Build updated classes/resources without deleting that app-image.
    & $MavenCommand package javafx:run
    if ($LASTEXITCODE -ne 0) { throw "Desktop build or launch failed (exit $LASTEXITCODE)." }
} finally { Pop-Location }
