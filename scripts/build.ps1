# Builds everything: backend, native host, desktop (Maven) and the browser extensions (npm).
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
mvn -B -DskipTests install
npm --prefix browser-extensions ci
npm --prefix browser-extensions run build
Write-Host "Built: desktop\target\wdm-desktop.jar (+ lib\), native-messaging\host\target, browser-extensions\dist\{chrome,edge,firefox}"
