param(
    [string[]]$Tasks = @(':plan-core:test', ':app:testDebugUnitTest', ':app:assembleDebug', ':app:lintDebug'),
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
# Reuse the verified workspace-only toolchain and current proxy configuration.
& (Join-Path $PSScriptRoot 'build-probe.ps1') -Tasks $Tasks -Offline:$Offline
exit $LASTEXITCODE
