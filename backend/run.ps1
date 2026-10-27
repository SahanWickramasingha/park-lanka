param([string]$MavenCommand, [string]$BuildDirectory)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $taskPort = 8080
    if ($env:PORT -and -not [int]::TryParse($env:PORT, [ref]$taskPort)) { throw 'PORT must be a valid port number.' }
    $taskConnection = New-Object System.Net.Sockets.TcpClient
    try {
        $taskProbe = $taskConnection.ConnectAsync('127.0.0.1', $taskPort)
        try { $null = $taskProbe.Wait(500) } catch { }
        if ($taskConnection.Connected) { throw "Port $taskPort is already in use. Stop only the previous Parking POS backend in its terminal, or select the intended free PORT, before building/restarting. No process was stopped." }
    } finally { $taskConnection.Dispose() }
    if (-not $MavenCommand) {
        $taskMaven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
        if ($taskMaven) { $MavenCommand = $taskMaven.Source }
        else {
            $taskKnownMaven = Join-Path $env:USERPROFILE 'Downloads\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin\mvn.cmd'
            if (Test-Path -LiteralPath $taskKnownMaven) { $MavenCommand = $taskKnownMaven }
            else { throw 'Maven was not found. Add it to PATH or pass -MavenCommand C:\path\to\mvn.cmd.' }
        }
    }
    $taskJava = 'java'
    if ($env:JAVA_HOME) { $taskJava = Join-Path $env:JAVA_HOME 'bin\java.exe' }
    Write-Host 'Building Parking POS backend 0.2.0 from the correct backend directory.'
    Write-Host 'Existing databases require the verified backup/adoption procedure in UPGRADE.md. Stop only the prior project backend before starting this one.'
    $taskBuildArgs = @('-DskipTests')
    $taskJarDirectory = Join-Path $PSScriptRoot 'target'
    if ($BuildDirectory) {
        $taskJarDirectory = [IO.Path]::GetFullPath($BuildDirectory)
        $taskBuildArgs += "-Dparking.build.directory=$taskJarDirectory"
    }
    & $MavenCommand @taskBuildArgs package
    if ($LASTEXITCODE -ne 0) { throw 'Backend packaging failed.' }
    & $taskJava -jar (Join-Path $taskJarDirectory 'parking-backend-0.2.0.jar')
    if ($LASTEXITCODE -ne 0) { throw 'Backend startup failed; inspect the error above and UPGRADE.md.' }
} finally { Pop-Location }
