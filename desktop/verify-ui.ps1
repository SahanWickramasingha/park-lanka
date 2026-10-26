param(
    [string]$MavenRepository = "$env:USERPROFILE\.m2\repository",
    [string]$MavenCommand,
    [string]$AppImage,
    [string]$DesktopJar,
    [switch]$PrintToPdf
)
$ErrorActionPreference = 'Stop'
# Resolve caller-supplied paths before changing to the desktop module.
if ($AppImage) { $AppImage = (Resolve-Path -LiteralPath $AppImage).Path }
if ($DesktopJar) { $DesktopJar = (Resolve-Path -LiteralPath $DesktopJar).Path }
if($AppImage -and $DesktopJar){throw 'Choose AppImage or DesktopJar.'}
Push-Location $PSScriptRoot
try {
    if (-not $MavenCommand) {
        $taskMaven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
        if ($taskMaven) { $MavenCommand = $taskMaven.Source }
        else {
            $MavenCommand = Join-Path $env:USERPROFILE 'Downloads\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin\mvn.cmd'
            if (-not (Test-Path -LiteralPath $MavenCommand)) { throw 'Add Maven to PATH or pass -MavenCommand C:\path\to\mvn.cmd.' }
        }
    }
    & $MavenCommand "-Dmaven.repo.local=$MavenRepository" test-compile
    if ($LASTEXITCODE -ne 0) { throw 'Desktop test compilation failed.' }
    if ($AppImage) {
        [xml]$taskPom = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pom.xml')
        $taskJar = Join-Path $AppImage ("app\parking-desktop-" + $taskPom.project.version + '.jar')
        if (-not (Test-Path -LiteralPath $taskJar) -or -not (Test-Path -LiteralPath (Join-Path $AppImage 'app\lib'))) {
            throw 'AppImage must point to a Park Lanka app-image built with the current desktop version.'
        }
        # No target/classes: the application and every image must come from the packaged JAR.
        # Use the same classpath layout as the native launcher, with only the test harness added.
        $taskClasspath = "$PSScriptRoot\target\test-classes;$taskJar;$AppImage\app\lib\*"
        $taskJavaArgs = @('--add-modules', 'jdk.httpserver', '-Dpark.expectJarResources=true', '-cp', $taskClasspath)
        $taskPreview = Join-Path $PSScriptRoot 'target\ui-preview-packaged'
    } else {
        $taskFxModules = ((Get-ChildItem "$MavenRepository\org\openjfx" -Recurse -Filter '*21.0.2-win.jar').FullName -join ';')
        $taskJacksonJars = ((Get-ChildItem "$MavenRepository\com\fasterxml\jackson" -Recurse -Filter '*2.17.2.jar').FullName -join ';')
        $taskQrJars = ((Get-ChildItem "$MavenRepository\com\google\zxing\core" -Recurse -Filter 'core-3.5.3.jar').FullName -join ';')
        $taskApplication = if($DesktopJar){$DesktopJar}else{"$PSScriptRoot\target\classes"}
        $taskClasspath = "$taskApplication;$PSScriptRoot\target\test-classes;$taskJacksonJars;$taskQrJars"
        $taskJavaArgs = @('--module-path', $taskFxModules, '--add-modules', 'javafx.controls,jdk.httpserver', '--enable-native-access=javafx.graphics', '-cp', $taskClasspath)
        $taskPreview = Join-Path $PSScriptRoot 'target\ui-preview'
        if($DesktopJar){$taskJavaArgs += '-Dpark.expectJarResources=true';$taskPreview=Join-Path $PSScriptRoot 'target\ui-preview-jar'}
    }
    $taskJava = 'java'
    $taskCheckClass = 'lk.sahan.parking.desktop.DesktopUiSmoke'
    if ($PrintToPdf) {
        $taskCheckClass = 'lk.sahan.parking.desktop.PrintToPdfCheck'
        $taskPreview = Join-Path $PSScriptRoot 'target\print-pdf'
    }
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        $taskJava = Join-Path $env:JAVA_HOME 'bin\java.exe'
    }
    # Windows PowerShell wraps JVM warnings on stderr as ErrorRecords. Use the
    # process exit code for this native command, rather than treating a warning as failure.
    $ErrorActionPreference = 'Continue'
    & $taskJava @taskJavaArgs $taskCheckClass $taskPreview
    $taskUiExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($taskUiExit -ne 0) { throw 'Desktop UI check failed.' } 
} finally { Pop-Location }
