param(
    [Parameter(Mandatory=$true)][string]$Plan,
    [string]$Destination,
    [Parameter(Mandatory=$true)][ValidateSet('RESTORE_STOPPED_SERVER')][string]$Confirm
)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $taskJava = 'java'
    if ($env:JAVA_HOME) { $taskJava = Join-Path $env:JAVA_HOME 'bin\java.exe' }
    $taskArgs = @('-Dloader.main=lk.sahan.parking.maintenance.BackupRestore', '-cp', "$PSScriptRoot\target\parking-backend-0.2.0.jar", 'org.springframework.boot.loader.launch.PropertiesLauncher', '--plan', $Plan, '--confirm', $Confirm)
    if ($Destination) { $taskArgs += @('--destination', $Destination) }
    & $taskJava @taskArgs
    if ($LASTEXITCODE -ne 0) { throw 'Restore refused or failed. The original database was not overwritten.' }
} finally { Pop-Location }
