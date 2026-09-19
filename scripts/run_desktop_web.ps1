param([switch]$SkipBuild, [int]$Port = 8765, [switch]$Yes, [switch]$Rules)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $taskRoot
try {
    # Same runtime rules as run_desktop_chat.ps1: JBR's older bundled MSVC can fail ONNX DLL
    # initialization, so the runner itself uses Temurin. Never replace system/JDK DLLs.
    $desktopJavaHome = $env:HJP_DESKTOP_JAVA_HOME
    if (-not $desktopJavaHome) {
        $desktopJavaHome = Get-ChildItem (Join-Path $env:ProgramFiles 'Eclipse Adoptium') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $desktopJavaHome) { throw 'Set HJP_DESKTOP_JAVA_HOME to a current Java 21 installation (MSVC 14.40 or newer).' }
    $desktopJava = Join-Path $desktopJavaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $desktopJava)) { throw "Java not found: $desktopJava" }
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $env:PYTHONIOENCODING = 'utf-8'
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    $OutputEncoding = [Console]::OutputEncoding
    if (-not $SkipBuild) {
        New-Item -ItemType Directory -Force -Path (Join-Path $taskRoot 'build') | Out-Null
        $ErrorActionPreference = 'Continue'
        # Never run this while a model session is live: overwriting the installed jars mid-run
        # produced a real ClassNotFoundException.
        & .\gradlew.bat :desktop:installDist --console=plain --no-daemon *> build/desktop-web-build.log
        $ErrorActionPreference = 'Stop'
        if ($LASTEXITCODE -ne 0) { throw 'Build failed. See build/desktop-web-build.log' }
    }
    $serveArgs = @('serve', '--port', "$Port")
    if ($Yes) { $serveArgs += '--yes' }
    if ($Rules) { $serveArgs += '--rules' }
    Write-Host "Starting http://127.0.0.1:$Port (Ctrl+C to stop). Loading the real model takes a minute."
    $ErrorActionPreference = 'Continue'
    & $desktopJava '-Xmx1536m' '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' `
        -cp 'desktop/build/install/desktop/lib/*' com.example.hjp.desktop.MainKt @serveArgs
    $serveExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($serveExit -ne 0) { throw 'Required model/runtime or server startup failed; no fallback was used.' }
} finally { Pop-Location }
