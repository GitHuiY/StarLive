param(
    [string[]]$Tasks = @(':probe:assembleDebug', ':usagefixture:assembleDebug', ':probe:lintDebug', ':usagefixture:lintDebug'),
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$workspaceRoot = Split-Path -Parent $repoRoot
$toolsRoot = Join-Path $workspaceRoot 'tools'
$jdkPath = Join-Path $toolsRoot 'jdk-17.0.21+9'
if (-not (Test-Path -LiteralPath $jdkPath -PathType Container)) { throw 'Workspace tools must contain the pinned JDK 17.0.21+9 directory.' }
$jdk = Get-Item -LiteralPath $jdkPath
$gradle = Join-Path $toolsRoot 'gradle-8.11.1/bin/gradle.bat'
$sdk = Join-Path $toolsRoot 'android-sdk'
foreach ($requiredPath in @($gradle, (Join-Path $sdk 'platforms/android-35/android.jar'), (Join-Path $sdk 'build-tools/35.0.0/aapt2.exe'))) {
    if (-not (Test-Path -LiteralPath $requiredPath)) { throw "Required workspace tool is missing: $requiredPath" }
}

# Process-local configuration; no system environment or proxy changes.
$env:JAVA_HOME = $jdk.FullName
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_USER_HOME = Join-Path $toolsRoot 'android-user-home'
$env:GRADLE_USER_HOME = Join-Path $toolsRoot 'gradle-user-home'
New-Item -ItemType Directory -Path $env:ANDROID_USER_HOME, $env:GRADLE_USER_HOME -Force | Out-Null
$project = Join-Path $repoRoot 'android'
Set-Content -LiteralPath (Join-Path $project 'local.properties') -Value ('sdk.dir=' + $sdk.Replace('\', '/').Replace(':', '\:')) -Encoding ascii

$proxyText = [Environment]::GetEnvironmentVariable('HTTPS_PROXY')
if (-not $proxyText) { $proxyText = [Environment]::GetEnvironmentVariable('HTTP_PROXY') }
if (-not $proxyText) {
    $systemProxy = Get-ItemProperty -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -ErrorAction SilentlyContinue
    if ($systemProxy -and $systemProxy.ProxyEnable -eq 1) {
        $proxyText = [string]$systemProxy.ProxyServer
        if ($proxyText -match '(?:^|;)https=([^;]+)') { $proxyText = $Matches[1] }
        elseif ($proxyText -match '(?:^|;)http=([^;]+)') { $proxyText = $Matches[1] }
        elseif ($proxyText.Contains('=')) { throw 'The active system proxy has no HTTP/HTTPS endpoint.' }
    }
}
$gradleArgs = @('--no-daemon', '--console=plain', '--stacktrace', '-Dorg.gradle.internal.http.connectionTimeout=20000', '-Dorg.gradle.internal.http.socketTimeout=30000')
if ($proxyText) {
    if ($proxyText -notmatch '^[a-z][a-z0-9+.-]*://') { $proxyText = 'http://' + $proxyText }
    $proxy = [Uri]$proxyText
    if ($proxy.Scheme -notin @('http', 'https')) { throw 'The current proxy needs HTTP support for this Gradle entry point.' }
    if ($proxy.UserInfo) { throw 'Authenticated proxies require separate local credential configuration; credentials are not logged.' }
    $proxyHost = $proxy.Host
    $proxyPort = $proxy.Port
    $gradleArgs += @("-Dhttp.proxyHost=$proxyHost", "-Dhttp.proxyPort=$proxyPort", "-Dhttps.proxyHost=$proxyHost", "-Dhttps.proxyPort=$proxyPort", '-Dhttp.nonProxyHosts=localhost')
}
if ($Offline) { $gradleArgs += '--offline' }
$gradleArgs += @('-p', $project)
$gradleArgs += $Tasks
& $gradle @gradleArgs
exit $LASTEXITCODE
