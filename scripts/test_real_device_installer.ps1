# Device-independent regression tests for Windows PowerShell native output handling.
$ErrorActionPreference = 'Stop'
$tokens = $null
$parseErrors = $null
$source = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'install_real_device_debug.ps1') -Raw -Encoding UTF8
$ast = [System.Management.Automation.Language.Parser]::ParseInput($source, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw ($parseErrors -join '; ') }
$functionAst = $ast.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-AdbChecked'
}, $true)
if (-not $functionAst) { throw 'Missing checked native command helper' }
. ([scriptblock]::Create($functionAst.Extent.Text))

# Substitute an OS command, without contacting or modifying any Android device.
$adb = Join-Path $env:SystemRoot 'System32/cmd.exe'
$output = @(Invoke-AdbChecked /d /c 'echo native-success 1>&2')
if (($output -join '').Trim() -ne 'native-success') { throw 'Successful stderr was not preserved' }
$rejected = $false
try { Invoke-AdbChecked /d /c 'exit 7' } catch { $rejected = $true }
if (-not $rejected) { throw 'A nonzero exit code must fail' }
$emptyOutput = (@(& $adb /d /c 'exit 0') -join '').Trim()
if ($emptyOutput -ne '') { throw 'Absent native output must become an empty string' }
Write-Output 'PASS: script syntax, successful stderr, failing exit code, empty native output'
