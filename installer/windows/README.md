# Windows installer

`scripts/package.ps1` builds a per-user MSI with `jpackage` (bundled Java runtime, Start-menu and desktop
shortcuts, upgrade UUID for in-place upgrades, standard uninstall via Apps & features).

- Requirements: JDK 21 (jpackage), WiX Toolset 3.14 on PATH, Windows 10/11 x64.
- Two launchers: `WAN Download Manager.exe` (desktop) and `wdm-native-host.exe` (native messaging host).
- After installation `scripts/register-native-host.ps1` writes the browser manifests and HKCU registry
  keys (Chrome, Edge, Firefox). Uninstall: `scripts/register-native-host.ps1 -Remove`.
- Code signing: not configured — needs the company's code-signing certificate (`signtool sign` on the MSI
  and both EXEs). Unsigned installers trigger SmartScreen warnings.
- `resources/` may hold WiX overrides and the `.ico` icon (`WAN Download Manager.ico`) — not yet provided.
