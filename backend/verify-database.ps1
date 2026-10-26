# Read-only verification. Loads the same local settings as the backend, without Spring startup/seeding.
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $taskJava = 'java'
    if ($env:JAVA_HOME) { $taskJava = Join-Path $env:JAVA_HOME 'bin\java.exe' }
    & $taskJava '-Dloader.main=lk.sahan.parking.maintenance.DatabaseCheck' -cp "$PSScriptRoot\target\parking-backend-0.2.0.jar" org.springframework.boot.loader.launch.PropertiesLauncher
    if ($LASTEXITCODE -ne 0) { throw 'Database verification failed. Enter configuration locally; do not share passwords in chat.' }
} finally { Pop-Location }
