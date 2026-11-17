# Developer setup on Windows: build, then register the native host against the dev build for unpacked extensions.
# Load browser-extensions\dist\chrome (or edge) as an unpacked extension, copy its id, then:
#   .\scripts\install-dev.ps1 -ChromeId <id> [-EdgeId <id>]
param([string]$ChromeId = "", [string]$EdgeId = "")
$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
& (Join-Path $PSScriptRoot "build.ps1")
$cp = (Get-ChildItem "$root\native-messaging\host\target\wdm-native-host-*.jar").FullName
$jackson = (Get-ChildItem "$root\desktop\target\lib\jackson-*.jar" | ForEach-Object FullName) -join ";"
$cmd = Join-Path $root "native-messaging\host\target\wdm-native-host-dev.cmd"
"@echo off`r`njava -cp `"$cp;$jackson`" com.wan.downloadmanager.nativehost.NativeHost %*" | Set-Content -Encoding ASCII $cmd
& (Join-Path $PSScriptRoot "register-native-host.ps1") -HostExe $cmd -ChromeId $ChromeId -EdgeId $EdgeId
Write-Host "Run the app: java -jar desktop\target\wdm-desktop.jar"
