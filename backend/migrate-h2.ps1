param(
    [Parameter(Mandatory=$true)][string]$SourceBackup,
    [switch]$Apply,
    [ValidateSet('IMPORT_STOPPED_BACKUP')][string]$Confirm
)
$ErrorActionPreference = 'Stop'
$taskSource = (Resolve-Path -LiteralPath $SourceBackup).Path
if ($Apply -and $Confirm -ne 'IMPORT_STOPPED_BACKUP') { throw 'Apply requires -Confirm IMPORT_STOPPED_BACKUP after approval and a stopped-source backup.' }
Push-Location $PSScriptRoot
try {
    $taskJava = 'java'
    if ($env:JAVA_HOME) { $taskJava = Join-Path $env:JAVA_HOME 'bin\java.exe' }
    $taskArgs = @('-Dloader.main=lk.sahan.parking.maintenance.H2ToPostgres', '-cp', "$PSScriptRoot\target\parking-backend-0.2.0.jar", 'org.springframework.boot.loader.launch.PropertiesLauncher', '--source-backup', $taskSource)
    if ($Apply) { $taskArgs += @('--apply', '--confirm', $Confirm) }
    & $taskJava @taskArgs
    if ($LASTEXITCODE -ne 0) { throw 'Migration refused or failed. Business data was not imported. Review ARCHITECTURE.md.' }
} finally { Pop-Location }
