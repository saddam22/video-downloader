# Native messaging host registration

The installer writes one manifest per browser and registers it (per user, no admin rights):

| Browser | Registry key (HKCU) pointing to the manifest file |
|---|---|
| Chrome | `Software\Google\Chrome\NativeMessagingHosts\com.wan.download_manager` |
| Edge | `Software\Microsoft\Edge\NativeMessagingHosts\com.wan.download_manager` |
| Firefox | `Software\Mozilla\NativeMessagingHosts\com.wan.download_manager` |

`{{HOST_PATH}}` is the absolute path of `wdm-native-host.exe` (jpackage launcher of the host jar).
Extension ids are assigned by the stores at publication; until then use the unpacked ids and add them
to `%LOCALAPPDATA%\WAN\DownloadManager\allowed-origins.properties` (see `scripts/install-dev.ps1`).
The host refuses any caller not listed both here and in `allowed-origins.properties`.
