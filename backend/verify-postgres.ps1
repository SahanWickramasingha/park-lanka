param(
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\18\bin',
    [string]$MavenCommand,
    [string]$Tests,
    [switch]$TestsOnly
)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
$taskStarted = $false
$taskSavedEnv = @{}
try {
    if (-not $MavenCommand) {
        $taskMaven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
        if ($taskMaven) { $MavenCommand = $taskMaven.Source }
        else { $MavenCommand = Join-Path $env:USERPROFILE 'Downloads\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin\mvn.cmd' }
    }
    $taskId = [Guid]::NewGuid().ToString('N')
    $taskRoot = Join-Path $PSScriptRoot "target\pg-verification-$taskId"
    $taskData = Join-Path $taskRoot 'data'
    New-Item -ItemType Directory -Path $taskRoot | Out-Null
    $taskPassword = [Guid]::NewGuid().ToString('N')
    $taskPasswordFile = Join-Path $taskRoot 'init-password'
    [IO.File]::WriteAllText($taskPasswordFile, $taskPassword)
    & "$PostgresBin\initdb.exe" -D $taskData -U parking_test --auth=scram-sha-256 --pwfile=$taskPasswordFile --encoding=UTF8 --locale=C *> (Join-Path $taskRoot 'init.log')
    if ($LASTEXITCODE -ne 0) { throw 'Isolated PostgreSQL initdb failed; inspect target/pg-verification-*/init.log.' }
    Remove-Item -LiteralPath $taskPasswordFile
    $taskListener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $taskListener.Start(); $taskPort = $taskListener.LocalEndpoint.Port; $taskListener.Stop()
    # Wait only for pg_ctl, not its long-running server descendants or inherited shell pipes.
    $taskControlArgs = "-D `"$taskData`" -l `"$(Join-Path $taskRoot 'server.log')`" -o `"-h 127.0.0.1 -p $taskPort`" -w start"
    $taskControl = Start-Process -FilePath "$PostgresBin\pg_ctl.exe" -ArgumentList $taskControlArgs -WindowStyle Hidden -PassThru
    if (-not $taskControl.WaitForExit(60000) -or $taskControl.ExitCode -ne 0) { throw 'Isolated PostgreSQL startup failed.' }
    $taskStarted = $true
    Write-Host "Temporary PostgreSQL cluster ready on loopback port $taskPort."
    foreach ($taskName in @('PGPASSWORD','PARKING_TEST_POSTGRES_URL','PARKING_TEST_POSTGRES_USER','PARKING_TEST_POSTGRES_PASSWORD','PARKING_TEST_POSTGRES_BIN')) {
        $taskSavedEnv[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process')
    }
    $env:PGPASSWORD = $taskPassword
    $taskDatabase = "parking_pos_test_$taskId"
    & "$PostgresBin\createdb.exe" --no-password -h 127.0.0.1 -p $taskPort -U parking_test $taskDatabase
    if ($LASTEXITCODE -ne 0) { throw 'Isolated database creation failed.' }
    $env:PARKING_TEST_POSTGRES_URL = "jdbc:postgresql://127.0.0.1:$taskPort/$taskDatabase"
    $env:PARKING_TEST_POSTGRES_USER = 'parking_test'
    $env:PARKING_TEST_POSTGRES_PASSWORD = $taskPassword
    $env:PARKING_TEST_POSTGRES_BIN = $PostgresBin
    # Test-only runs leave the backend JAR untouched when an existing server has it open.
    $taskMavenArgs = @('verify')
    if ($TestsOnly) { $taskMavenArgs = @('test') }
    if ($Tests) { $taskMavenArgs += "-Dtest=$Tests" }
    # Windows PowerShell represents native stderr warnings as ErrorRecords.
    # The native process exit code determines build/test success.
    $ErrorActionPreference = 'Continue'
    & $MavenCommand @taskMavenArgs
    $taskTestExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($taskTestExit -ne 0) { throw "PostgreSQL verification failed (Maven exit $taskTestExit)." }
} finally {
    if ($taskData -and (Test-Path -LiteralPath (Join-Path $taskData 'postmaster.pid'))) { & "$PostgresBin\pg_ctl.exe" -D $taskData -m fast -w stop }
    foreach ($taskName in $taskSavedEnv.Keys) { [Environment]::SetEnvironmentVariable($taskName, $taskSavedEnv[$taskName], 'Process') }
    Pop-Location
}

Write-Host 'PostgreSQL verification passed; the temporary cluster has stopped.'
