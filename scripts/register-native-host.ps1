# Registers (or removes with -Remove) the native messaging host for Chrome, Edge and Firefox for the current user.
param([string]$HostExe = "$env:LOCALAPPDATA\WAN Download Manager\wdm-native-host.exe",
      [string]$ChromeId = "", [string]$EdgeId = "", [switch]$Remove)
$ErrorActionPreference = "Stop"
$dir = Join-Path $env:LOCALAPPDATA "WAN\DownloadManager\native-host"
$keys = @{ "chrome" = "HKCU:\Software\Google\Chrome\NativeMessagingHosts\com.wan.download_manager";
           "edge" = "HKCU:\Software\Microsoft\Edge\NativeMessagingHosts\com.wan.download_manager";
           "firefox" = "HKCU:\Software\Mozilla\NativeMessagingHosts\com.wan.download_manager" }
if ($Remove) { $keys.Values | ForEach-Object { Remove-Item $_ -ErrorAction SilentlyContinue }; Remove-Item -Recurse $dir -ErrorAction SilentlyContinue; exit }
New-Item -ItemType Directory -Force $dir | Out-Null
$origins = @(); if ($ChromeId) { $origins += "chrome-extension://$ChromeId/" }; if ($EdgeId) { $origins += "chrome-extension://$EdgeId/" }
$chromium = @{ name = "com.wan.download_manager"; description = "WAN Download Manager"; path = $HostExe; type = "stdio"; allowed_origins = $origins }
$firefox = @{ name = "com.wan.download_manager"; description = "WAN Download Manager"; path = $HostExe; type = "stdio"; allowed_extensions = @("download-manager@wan.example") }
$chromium | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $dir "chromium.json")
$firefox | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $dir "firefox.json")
foreach ($b in "chrome", "edge") { New-Item -Force $keys[$b] | Out-Null; Set-ItemProperty $keys[$b] "(default)" (Join-Path $dir "chromium.json") }
New-Item -Force $keys["firefox"] | Out-Null; Set-ItemProperty $keys["firefox"] "(default)" (Join-Path $dir "firefox.json")
# The host also checks its own allow-list (defence in depth).
$allow = Join-Path $env:LOCALAPPDATA "WAN\DownloadManager\allowed-origins.properties"
"chrome=$ChromeId`nedge=$EdgeId`n" | Set-Content -Encoding ASCII $allow
Write-Host "Native host registered for Chrome, Edge and Firefox."
