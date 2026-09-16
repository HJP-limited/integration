param([switch]$SkipBuild, [switch]$Regression, [ValidateSet('reported','tools')][string]$Suite='reported')
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $taskRoot
try {
    # JBR's older bundled MSVC can fail ONNX DLL initialization; never replace system/JDK DLLs.
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
    [Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    $OutputEncoding = [Console]::OutputEncoding
    if (-not $SkipBuild) {
        New-Item -ItemType Directory -Force -Path (Join-Path $taskRoot 'build') | Out-Null
        $ErrorActionPreference = 'Continue'
        & .\gradlew.bat :desktop:installDist --console=plain --no-daemon *> build/desktop-chat-build.log
        $ErrorActionPreference = 'Stop'
        if ($LASTEXITCODE -ne 0) { throw 'Build failed. See build/desktop-chat-build.log' }
    }
    $ErrorActionPreference = 'Continue'
    if ($Regression) {
        $caseFile = if ($Suite -eq 'tools') { 'eval/device_tool_transitions_2026-09-16.json' } else { 'eval/device_user_regressions_2026-09-16.json' }
        $logFile = if ($Suite -eq 'tools') { 'build/desktop-tool-transitions.log' } else { 'build/desktop-user-regression.log' }
        $cases = Get-Content $caseFile -Raw -Encoding UTF8 | ConvertFrom-Json
        $questions = ($cases | ForEach-Object { $_.question }) -join '|'
        Write-Host "Running $($cases.Count) actual-model turns. This can take several minutes. Log: $logFile"
        & $desktopJava '-Xmx1536m' '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp 'desktop/build/install/desktop/lib/*' com.example.hjp.desktop.MainKt turn --gemma $questions *> $logFile
    } else {
        & $desktopJava '-Xmx1536m' '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp 'desktop/build/install/desktop/lib/*' com.example.hjp.desktop.MainKt turn --gemma --interactive
    }
    $chatExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($chatExit -ne 0) { throw 'Required model/runtime or chat execution failed; no fallback was used.' }
    if ($Regression) {
        $pythonExe = if ($env:HJP_PYTHON) { $env:HJP_PYTHON } else { 'python' }
        & $pythonExe scripts/check_desktop_regression.py $logFile --suite $caseFile
        if ($LASTEXITCODE -ne 0) { throw "Regression failed. See $logFile" }
    }
} finally { Pop-Location }
