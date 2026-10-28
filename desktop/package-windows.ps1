param(
    [ValidateSet('app-image', 'exe')][string]$Type = 'exe',
    [string]$ServerUrl = 'https://park-lanka.onrender.com',
    [string]$MavenCommand,
    [string]$OutputDirectory,
    [string]$JavaHome,
    [string]$WixDirectory
)
$ErrorActionPreference = 'Stop'
# Match private configuration/data filenames in both ZIP entries and Windows paths.
$taskProhibitedPath = '(?i)(^|[\\/])(\.env[^\\/]*|[^\\/]*\.env(?:\.[^\\/]*)?|backups|logs)([\\/]|$)|\.(db|log|sql|backup|bak|key|pem|p12|pfx|jks)$'
$taskUpgradeUuid = '6ea48f96-8068-4e93-b18b-38971a86e6e8'
# Resolve explicit paths relative to the caller, before entering the desktop module.
foreach ($taskParameter in @('OutputDirectory', 'JavaHome', 'WixDirectory')) {
    $taskValue = Get-Variable -Name $taskParameter -ValueOnly
    if ($taskValue) { Set-Variable -Name $taskParameter -Value ([IO.Path]::GetFullPath($taskValue)) }
}
if ($MavenCommand) { $MavenCommand = (Get-Command $MavenCommand -ErrorAction Stop).Source }
$taskOriginalPath = $env:PATH
$taskOriginalJavaHome = $env:JAVA_HOME
Push-Location $PSScriptRoot
try {
    if ([Environment]::OSVersion.Platform -ne 'Win32NT') { throw 'Build Windows packages on Windows.' }
    $taskJavaCandidates = if ($JavaHome) { @($JavaHome) } else {
        @($env:JAVA_HOME, (Join-Path $env:ProgramFiles 'Java\jdk-17')) +
            @(Get-ChildItem (Join-Path $env:ProgramFiles 'Java') -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | ForEach-Object FullName) +
            @((Get-Command jpackage.exe -ErrorAction SilentlyContinue | ForEach-Object { Split-Path (Split-Path $_.Source -Parent) -Parent }))
    }
    $taskJdk = $null
    foreach ($taskCandidate in ($taskJavaCandidates | Where-Object { $_ } | Select-Object -Unique)) {
        $taskReleaseFile = Join-Path $taskCandidate 'release'
        if (-not (Test-Path -LiteralPath $taskReleaseFile)) { continue }
        $taskRelease = Get-Content -LiteralPath $taskReleaseFile -Raw
        $taskMissingTools = @('java.exe', 'javac.exe', 'jlink.exe', 'jpackage.exe') | Where-Object {
            -not (Test-Path -LiteralPath (Join-Path $taskCandidate "bin\$_"))
        }
        if ($taskRelease -match '(?m)^JAVA_VERSION="17\.' -and $taskRelease -match '(?m)^OS_ARCH="(x86_64|amd64)"' -and -not $taskMissingTools) {
            $taskJdk = [IO.Path]::GetFullPath($taskCandidate); break
        }
    }
    if (-not $taskJdk) { throw 'Windows x64 JDK 17 with java, javac, jlink and jpackage was not found. Pass -JavaHome C:\path\to\jdk-17.' }
    $env:JAVA_HOME = $taskJdk
    $taskPackager = Join-Path $taskJdk 'bin\jpackage.exe'
    $taskUri = $null
    if (-not [Uri]::TryCreate($ServerUrl, [UriKind]::Absolute, [ref]$taskUri) -or
        $taskUri.Scheme -notin @('http', 'https') -or -not $taskUri.Host -or $taskUri.UserInfo -or
        $taskUri.Query -or $taskUri.Fragment -or $taskUri.AbsolutePath -ne '/' -or $ServerUrl -match '\s') {
        throw 'ServerUrl must be an http(s) origin without credentials, query, fragment or path.'
    }
    if (-not $MavenCommand) {
        $taskMaven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
        if ($taskMaven) { $MavenCommand = $taskMaven.Source }
        else {
            $MavenCommand = Join-Path $env:USERPROFILE 'Downloads\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin\mvn.cmd'
            if (-not (Test-Path -LiteralPath $MavenCommand)) { throw 'Add Maven to PATH or pass -MavenCommand C:\path\to\mvn.cmd.' }
        }
    }
    if (-not (Test-Path -LiteralPath $MavenCommand -PathType Leaf)) { throw "Maven executable not found: $MavenCommand" }
    $taskWixBin = $null
    if ($Type -eq 'exe') {
        $taskWixCandidates = if ($WixDirectory) { @($WixDirectory) } else {
            @($(if ($env:WIX) { Join-Path $env:WIX 'bin' }), $env:WIX,
                (Join-Path ${env:ProgramFiles(x86)} 'WiX Toolset v3.14\bin')) +
                @(Get-ChildItem ${env:ProgramFiles(x86)} -Directory -Filter 'WiX Toolset v3*' -ErrorAction SilentlyContinue | ForEach-Object { Join-Path $_.FullName 'bin' }) +
                @((Get-Command candle.exe -ErrorAction SilentlyContinue | ForEach-Object { Split-Path $_.Source -Parent }))
        }
        foreach ($taskCandidate in ($taskWixCandidates | Where-Object { $_ } | Select-Object -Unique)) {
            if ((Test-Path -LiteralPath (Join-Path $taskCandidate 'candle.exe')) -and (Test-Path -LiteralPath (Join-Path $taskCandidate 'light.exe'))) {
                $taskWixVersion = [Diagnostics.FileVersionInfo]::GetVersionInfo((Join-Path $taskCandidate 'candle.exe'))
                if ($taskWixVersion.FileMajorPart -eq 3) { $taskWixBin = $taskCandidate; break }
            }
        }
        if (-not $taskWixBin) { throw 'WiX Toolset 3.x candle.exe and light.exe were not found. Pass -WixDirectory C:\path\to\WiX\bin.' }
    }
    # All child processes (Maven, jpackage, jlink and WiX) inherit the resolved tools.
    $env:PATH = (@((Join-Path $taskJdk 'bin'), (Split-Path $MavenCommand -Parent), $taskWixBin) | Where-Object { $_ }) -join ';'
    $env:PATH += ";$taskOriginalPath"
    Write-Host "JDK: $taskJdk"
    Write-Host "Maven: $MavenCommand"
    if ($taskWixBin) { Write-Host "WiX: $taskWixBin" }
    [xml]$taskPom = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pom.xml')
    $taskVersion = [string]$taskPom.project.version
    if ($taskVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'Desktop version must be a numeric Windows version, e.g. 0.2.0.' }
    $taskVersionParts = $taskVersion.Split('.')
    if ([long]$taskVersionParts[0] -gt 255 -or [long]$taskVersionParts[1] -gt 255 -or [long]$taskVersionParts[2] -gt 65535) {
        throw 'Windows version limits are 255.255.65535.'
    }
    # Fail before packaging if the supplied artwork or any required ICO frame is missing.
    $taskIcon = Join-Path $PSScriptRoot 'branding\park-lanka.ico'
    $taskIconBytes = [IO.File]::ReadAllBytes($taskIcon)
    if ($taskIconBytes.Length -lt 6 -or [BitConverter]::ToUInt16($taskIconBytes, 0) -ne 0 -or
        [BitConverter]::ToUInt16($taskIconBytes, 2) -ne 1) { throw 'Invalid Park Lanka ICO header.' }
    $taskFrameCount = [BitConverter]::ToUInt16($taskIconBytes, 4)
    if ($taskFrameCount -ne 7 -or $taskIconBytes.Length -lt 6 + 16 * $taskFrameCount) {
        throw 'Park Lanka ICO must contain exactly seven resolutions.'
    }
    $taskIconSizes = @(for ($taskFrame = 0; $taskFrame -lt $taskFrameCount; $taskFrame++) {
        $taskOffset = 6 + 16 * $taskFrame
        $taskWidth = [int]$taskIconBytes[$taskOffset]
        $taskHeight = [int]$taskIconBytes[$taskOffset + 1]
        if ($taskWidth -eq 0) { $taskWidth = 256 }
        if ($taskHeight -eq 0) { $taskHeight = 256 }
        if ($taskWidth -ne $taskHeight) { throw 'Park Lanka ICO frames must be square.' }
        $taskWidth
    })
    if (($taskIconSizes | Sort-Object) -join ',' -ne '16,24,32,48,64,128,256') {
        throw 'Park Lanka ICO requires 16, 24, 32, 48, 64, 128 and 256 pixel frames.'
    }
    # Every build gets a fresh directory. Never clean/move an installation or user data.
    if (-not $OutputDirectory) { $OutputDirectory = Join-Path $PSScriptRoot ('target\windows\build-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)) }
    $taskOutput = [IO.Path]::GetFullPath($OutputDirectory)
    if (Test-Path -LiteralPath $taskOutput) { throw "Output already exists; select a new directory: $taskOutput" }
    New-Item -ItemType Directory -Path $taskOutput | Out-Null

    $taskInput = Join-Path $taskOutput 'input'
    $taskLib = Join-Path $taskInput 'lib'
    New-Item -ItemType Directory -Path $taskLib | Out-Null
    # Fresh classes/resources without cleaning previous packages or user data.
    $taskBuild = Join-Path $taskOutput 'build'
    & $MavenCommand --batch-mode --no-transfer-progress -Pwindows-package "-Dparking.build.directory=$taskBuild" "-Dparking.package.libs=$taskLib" package
    if ($LASTEXITCODE -ne 0) { throw "Desktop compilation/packaging failed (Maven exit $LASTEXITCODE). No installer was created; fix the Maven error above." }
    $taskJarName = "parking-desktop-$taskVersion.jar"
    # Allowlist only the desktop JAR and runtime dependency JARs. No directory copies.
    $taskJar = Join-Path $taskBuild $taskJarName
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $taskArchive = [IO.Compression.ZipFile]::OpenRead($taskJar)
    try {
        $taskRequiredArtwork = @('park-lanka-logo.png', 'park-lanka-icon-1024.png') +
            @($taskIconSizes | ForEach-Object { "park-lanka-icon-$_.png" })
        foreach ($taskArtwork in $taskRequiredArtwork) {
            if (-not $taskArchive.GetEntry("lk/sahan/parking/desktop/branding/$taskArtwork")) {
                throw "Desktop JAR is missing branding resource: $taskArtwork"
            }
        }
        foreach ($taskResource in @('theme.css', 'DesktopLauncher.class', 'DesktopConfiguration.class', 'LoginStartup.class', 'ParkingPrintDocument.class', 'ThermalDocuments.class')) {
            if (-not $taskArchive.GetEntry("lk/sahan/parking/desktop/$taskResource")) { throw "Desktop JAR missing required resource: $taskResource" }
        }
        if (@($taskArchive.Entries | Where-Object { $_.FullName -match $taskProhibitedPath }).Count) {
            throw 'Desktop JAR contains prohibited configuration, database, backup or log entries.'
        }
    } finally { $taskArchive.Dispose() }
    $taskNativeLibraries = @()
    foreach ($taskDependency in (Get-ChildItem -LiteralPath $taskLib -File)) {
        if ($taskDependency.Extension -ne '.jar') { throw "Unexpected non-JAR runtime dependency: $($taskDependency.Name)" }
        $taskDependencyZip = [IO.Compression.ZipFile]::OpenRead($taskDependency.FullName)
        try {
            if (@($taskDependencyZip.Entries | Where-Object { $_.FullName -match $taskProhibitedPath }).Count) {
                throw "Runtime dependency contains prohibited configuration/data files: $($taskDependency.Name)"
            }
            $taskNativeLibraries += @($taskDependencyZip.Entries | Where-Object { $_.Name -like '*.dll' } | ForEach-Object Name)
        }
        finally { $taskDependencyZip.Dispose() }
    }
    foreach ($taskNative in @('glass.dll', 'prism_d3d.dll', 'javafx_font.dll', 'javafx_iio.dll')) {
        if ($taskNative -notin $taskNativeLibraries) { throw "Missing JavaFX Windows native library: $taskNative" }
    }
    Copy-Item -LiteralPath $taskJar -Destination $taskInput
    $taskImageDestination = Join-Path $taskOutput 'image'
    $taskImageArgs = @('--type', 'app-image', '--name', 'Park Lanka', '--app-version', $taskVersion,
        '--vendor', 'Park Lanka', '--description', 'Park Lanka - Smart Parking. Simple Control.',
        '--input', $taskInput, '--main-jar', $taskJarName,
        '--main-class', 'lk.sahan.parking.desktop.DesktopLauncher', '--icon', $taskIcon,
        '--dest', $taskImageDestination, '--add-modules', 'java.desktop,java.net.http,java.naming,java.sql,java.xml,jdk.crypto.ec,jdk.crypto.mscapi,jdk.unsupported',
        '--java-options', "-Dparking.server.defaultUrl=$ServerUrl")
    & $taskPackager @taskImageArgs
    if ($LASTEXITCODE -ne 0) { throw 'jpackage app-image failed.' }
    $taskImage = Join-Path $taskImageDestination 'Park Lanka'
    foreach ($taskRequired in @('Park Lanka.exe', 'runtime\bin\server\jvm.dll', 'runtime\lib\security\cacerts', 'app\Park Lanka.cfg', "app\$taskJarName")) {
        if (-not (Test-Path -LiteralPath (Join-Path $taskImage $taskRequired))) { throw "App-image is incomplete: $taskRequired" }
    }
    if (@(Get-ChildItem -LiteralPath $taskImage -Recurse -File | Where-Object { $_.FullName.Substring($taskImage.Length) -match $taskProhibitedPath }).Count) {
        throw 'App-image contains prohibited configuration, database, backup or log files.'
    }
    Write-Host "App-image ready: $taskImage\Park Lanka.exe"
    $taskLauncherConfig = Get-Content -LiteralPath (Join-Path $taskImage 'app\Park Lanka.cfg')
    if (@($taskLauncherConfig | Where-Object { $_ -eq "java-options=-Dparking.server.defaultUrl=$ServerUrl" }).Count -ne 1) {
        throw 'App-image launcher does not contain the requested public API default.'
    }

    $taskInstaller = $null
    if ($Type -eq 'exe') {
        # Preserve this existing upgrade UUID and per-user install identity across branding releases.
        $taskInstallerArgs = @('--type', 'exe', '--name', 'Park Lanka', '--app-version', $taskVersion,
            '--vendor', 'Park Lanka', '--description', 'Park Lanka - Smart Parking. Simple Control.',
            '--app-image', $taskImage, '--icon', $taskIcon, '--dest', (Join-Path $taskOutput 'installer'),
            '--win-per-user-install', '--win-menu', '--win-menu-group', 'Park Lanka', '--win-shortcut',
            '--install-dir', 'Park Lanka', '--win-upgrade-uuid', $taskUpgradeUuid)
        & $taskPackager @taskInstallerArgs
        if ($LASTEXITCODE -ne 0) { throw 'jpackage EXE installer failed; the app-image is still available.' }
        $taskGeneratedInstaller = Join-Path $taskOutput "installer\Park Lanka-$taskVersion.exe"
        if (-not (Test-Path -LiteralPath $taskGeneratedInstaller -PathType Leaf)) { throw "jpackage did not produce the expected installer: $taskGeneratedInstaller" }
        # Rename only the output file: keep Park Lanka's embedded product/upgrade identity.
        Rename-Item -LiteralPath $taskGeneratedInstaller -NewName 'Setup.exe'
        $taskInstaller = Join-Path $taskOutput 'installer\Setup.exe'
        Write-Host "Installer ready: $taskInstaller"
        Write-Host 'Distribute Setup.exe alone; it includes the application, dependencies and Java runtime.'
        $taskInstallerFolder = (Split-Path $taskInstaller -Parent).Replace("'", "''")
        Write-Host "Open output folder: Invoke-Item -LiteralPath '$taskInstallerFolder'"
    }
    $taskManifest = [ordered]@{
        version = $taskVersion; javaHome = $taskJdk; maven = $MavenCommand; wix = $taskWixBin
        appImage = $taskImage; installer = $taskInstaller
        upgradeUuid = $taskUpgradeUuid
        serverDefaultUrl = $ServerUrl; builtAt = [DateTimeOffset]::Now.ToString('o')
        desktopJarSha256 = (Get-FileHash -LiteralPath $taskJar -Algorithm SHA256).Hash
        installerSha256 = $(if ($taskInstaller) { (Get-FileHash -LiteralPath $taskInstaller -Algorithm SHA256).Hash } else { $null })
    }
    $taskManifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskOutput 'package-manifest.json') -Encoding UTF8
} finally {
    $env:PATH = $taskOriginalPath
    $env:JAVA_HOME = $taskOriginalJavaHome
    Pop-Location
}
