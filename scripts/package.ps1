# Windows installer (MSI) with jpackage (JDK 21). Requires WiX Toolset 3.x on PATH for --type msi.
# Produces installer\windows\out\WAN Download Manager-<version>.msi containing the app and the native host.
param([string]$Version = "0.1.0", [string]$Type = "msi", [string]$Issuer = "", [string]$ClientId = "")
$ErrorActionPreference = "Stop"
$root = Join-Path $PSScriptRoot ".."
Set-Location $root
mvn -B -DskipTests install
$input = Join-Path $root "installer\windows\input"
Remove-Item -Recurse -Force $input -ErrorAction SilentlyContinue
New-Item -ItemType Directory $input | Out-Null
Copy-Item desktop\target\wdm-desktop.jar $input
Copy-Item -Recurse desktop\target\lib (Join-Path $input "lib")
Copy-Item native-messaging\host\target\wdm-native-host-*.jar (Join-Path $input "lib\wdm-native-host.jar")
$javaOpts = @()
if ($Issuer) { $javaOpts += "--java-options", "-Dwan.identity.issuer=$Issuer" }
if ($ClientId) { $javaOpts += "--java-options", "-Dwan.identity.client-id=$ClientId" }
jpackage --type $Type --name "WAN Download Manager" --app-version $Version --vendor "WANBAHJAHKAS LTD" `
  --description "Download Everything. Manage Smarter." --input $input --main-jar wdm-desktop.jar `
  --main-class com.wan.downloadmanager.desktop.DesktopLauncher --dest installer\windows\out `
  --win-menu --win-menu-group "WAN" --win-shortcut --win-per-user-install --win-dir-chooser `
  --win-upgrade-uuid "6f1d6a3e-7b7e-4c1b-9b8e-1d2f3a4b5c6d" `
  --add-launcher "wdm-native-host=installer\windows\native-host.properties" `
  --resource-dir installer\windows\resources @javaOpts
Write-Host "Installer in installer\windows\out. After install run scripts\register-native-host.ps1 (or the installer's post-install step)."
