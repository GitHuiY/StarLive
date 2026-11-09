param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArguments)

$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$adb = Join-Path $workspaceRoot 'tools/platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw 'Workspace platform-tools/adb.exe is missing.' }
# Applies only if ADB needs to start a server; an existing server is preserved.
$env:ADB_USB_LEGACY = '1'
& $adb @AdbArguments
exit $LASTEXITCODE
