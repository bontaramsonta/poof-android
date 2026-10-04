# WireGuard app integration points

Research for [#15](https://github.com/bontaramsonta/poof-android/issues/15). Feeds the decision in [#16](https://github.com/bontaramsonta/poof-android/issues/16). Facts only, no recommendation.

**Source pinned:** [`WireGuard/wireguard-android@e7b3a3c`](https://github.com/WireGuard/wireguard-android/tree/e7b3a3c118836e112620b1302a8ba1873ad4daac) (master, 2026-03-17). `gradle.properties` there gives version `1.0.20260315` (code 519), package `com.wireguard.android`. This matches the newest APK on `download.wireguard.com/android-client/` and the version shown on the Play Store listing (checked 2026-10-04).

Paths below are relative to that commit. Short links:

- `ui/src/main/AndroidManifest.xml` — [link](https://github.com/WireGuard/wireguard-android/blob/e7b3a3c118836e112620b1302a8ba1873ad4daac/ui/src/main/AndroidManifest.xml)
- `ui/.../model/TunnelManager.kt` — [link](https://github.com/WireGuard/wireguard-android/blob/e7b3a3c118836e112620b1302a8ba1873ad4daac/ui/src/main/java/com/wireguard/android/model/TunnelManager.kt)
- `ui/.../util/TunnelImporter.kt` — [link](https://github.com/WireGuard/wireguard-android/blob/e7b3a3c118836e112620b1302a8ba1873ad4daac/ui/src/main/java/com/wireguard/android/util/TunnelImporter.kt)
- `ui/.../fragment/TunnelListFragment.kt` — [link](https://github.com/WireGuard/wireguard-android/blob/e7b3a3c118836e112620b1302a8ba1873ad4daac/ui/src/main/java/com/wireguard/android/fragment/TunnelListFragment.kt)
- `ui/.../util/UserKnobs.kt` — [link](https://github.com/WireGuard/wireguard-android/blob/e7b3a3c118836e112620b1302a8ba1873ad4daac/ui/src/main/java/com/wireguard/android/util/UserKnobs.kt)
- `tunnel/.../backend/GoBackend.java` — [link](https://github.com/WireGuard/wireguard-android/blob/e7b3a3c118836e112620b1302a8ba1873ad4daac/tunnel/src/main/java/com/wireguard/android/backend/GoBackend.java)

## Summary

| Need | Supported? | Mechanism |
|---|---|---|
| Hand a config to the app via intent (`ACTION_VIEW` / `ACTION_SEND`) | **No** | No such intent filter; no activity reads intent data. |
| Create or replace a tunnel without UI | **No** | Import exists only behind the in-app "+" sheet. |
| Bring a named tunnel up or down | **Yes**, with conditions | Broadcast `SET_TUNNEL_UP` / `SET_TUNNEL_DOWN`, extra `tunnel=<name>`. |
| Read tunnel state, handshake time or bytes | **No** | Nothing sent out: no broadcast, no exported provider, no result. |
| Delete a tunnel | **No** | Only in the UI. |
| Play vs direct-APK differences in these APIs | **None** | Same manifest except `REQUEST_INSTALL_PACKAGES`. F-Droid has not shipped the app since 2023. |

## 1. Importing a config from another app

### What the manifest exposes

`ui/src/main/AndroidManifest.xml` has these exported entry points:

- `MainActivity`: `MAIN`/`LAUNCHER` and `QS_TILE_PREFERENCES`.
- `TvMainActivity`: `LEANBACK_LAUNCHER`.
- `BootShutdownReceiver`, `Updater$AppUpdatedReceiver`.
- `TunnelManager$IntentReceiver`: the remote-control receiver (section 2).
- `QuickTileService`: bound only by the system.

There is **no** `ACTION_VIEW`, `ACTION_SEND` or `ACTION_SEND_MULTIPLE` filter. There is no `mimeType`, `scheme` or `pathPattern` for `.conf` / `.zip`. `TunnelCreatorActivity` is not exported. The only `ContentProvider` (`LogViewerActivity$ExportedLogContentProvider`) is `exported="false"` and serves logs.

`MainActivity.kt` never reads `intent.data`. The one intent extra any activity reads is `selected_tunnel` in `BaseActivity.onCreate`. It selects an **existing** tunnel by name and opens its detail screen. A grep of every `.kt`/`.java` file under `ui/src/main` and `tunnel/src/main` for `getStringExtra`, `intent.data`, `ACTION_VIEW` and `ACTION_SEND` turns up only that extra, the `tunnel` extra in `TunnelManager`, the updater, and outgoing `ACTION_VIEW` links (donate, website, a Play Store link to a file manager on TV).

### The in-app import paths

`AddTunnelsSheet.kt` is the bottom sheet opened by the "+" FAB in `TunnelListFragment`. It offers three paths:

1. **Create from scratch.** Opens `TunnelCreatorActivity`, an editor form.
2. **Import from file or archive.** Calls `ActivityResultContracts.GetContent()` with `"*/*"`, which opens the system picker.
   - If the picked file is an image, it is scanned as a QR code. That leads to `ConfigNamingDialogFragment`: the user types a name and taps "Create tunnel".
   - Otherwise `TunnelImporter.importTunnel(contentResolver, uri)` runs. The tunnel name is the file's `DISPLAY_NAME` minus `.conf`. **No naming dialog.** The tunnel is created at once and a snackbar shows "Imported “name”".
   - A `.zip` imports every `*.conf` entry inside it.
   - Any other extension fails with `bad_extension_error`.
3. **Scan from QR code.** Uses the camera (zxing `ScanContract`), then the same naming dialog. This path is hidden when the device has no camera.

### Constraints on an imported tunnel

- **Name.** It MUST match `[a-zA-Z0-9_=+.-]{1,15}` (`tunnel/.../backend/Tunnel.java`, `NAME_PATTERN`).
- **No replacing.** `TunnelManager.create` throws "already exists" if the name is taken. `FileConfigStore.create` also fails if `<name>.conf` exists. An import cannot overwrite a tunnel.
- **Storage.** Configs live in the app's private `filesDir` as `<name>.conf` (`FileConfigStore.kt`). Another app cannot reach them.

### Tap count: file import handed over from another app

Assumption: poof has written `<name>.conf` somewhere the system picker can show it (e.g. Downloads).

| Step | Taps |
|---|---|
| Open WireGuard. Another app can start the exported launcher activity. | 0–1 |
| Tap "+" FAB | 1 |
| Tap "Import from file or archive" | 1 |
| Find and tap the file in the system picker | ≥1 (usually 1–3 more for navigation) |
| Tunnel is created. No naming dialog for `.conf`. | 0 |
| **Import subtotal** | **≥3, typically 3–5** |
| Toggle the tunnel on | 1 |
| First time only: system VPN consent dialog "OK" | 1 |

An import from an image of a QR code adds a name field and a "Create tunnel" tap.

**Creating or replacing a tunnel without UI is not possible** at this commit.

## 2. Remote control

### Receiver declaration

From `AndroidManifest.xml`:

```xml
<permission android:name="${applicationId}.permission.CONTROL_TUNNELS"
    android:protectionLevel="dangerous" ... />

<receiver android:name=".model.TunnelManager$IntentReceiver"
    android:exported="true"
    android:permission="${applicationId}.permission.CONTROL_TUNNELS">
  <intent-filter>
    <action android:name="com.wireguard.android.action.REFRESH_TUNNEL_STATES" />
    <action android:name="com.wireguard.android.action.SET_TUNNEL_UP" />
    <action android:name="com.wireguard.android.action.SET_TUNNEL_DOWN" />
  </intent-filter>
</receiver>
```

`applicationId` is `com.wireguard.android` (`gradle.properties`), so the permission is `com.wireguard.android.permission.CONTROL_TUNNELS`. Its label is "control WireGuard tunnels". Its description reads "...enabling and disabling tunnels at will, potentially misdirecting Internet traffic" (`ui/src/main/res/values/strings.xml`).

### Permission

The permission is **`dangerous`**. A caller MUST declare it with `<uses-permission>` and MUST obtain a runtime grant, which shows the user a dialog. This is the standard Android rule for dangerous permissions. The receiver is protected by this permission, so a sender without it is not delivered.

### Setting: "Allow remote control apps"

- Key: `allow_remote_control_intents` (`preferences.xml`, `UserKnobs.kt`).
- **Default: off.** Summary when off: "External apps may not toggle tunnels (recommended)".
- The user MUST turn it on in WireGuard's Settings.

### How the receiver behaves

`TunnelManager.IntentReceiver.onReceive`:

1. `REFRESH_TUNNEL_STATES` re-reads which tunnels the backend has running and updates WireGuard's own UI model. It is processed **even when the setting is off**. It returns nothing to the caller.
2. With the setting off, `SET_TUNNEL_UP` and `SET_TUNNEL_DOWN` are **silently ignored**.
3. With the setting on, the tunnel is addressed **by name**, via the string extra `"tunnel"` (`intent.getStringExtra("tunnel")`). An unknown name is ignored silently.
4. A failure, e.g. VPN not authorized, shows a **Toast inside WireGuard**. Nothing is sent back to the caller.

### What `SET_TUNNEL_UP` needs to succeed

These conditions are in the userspace `GoBackend` (`tunnel/.../GoBackend.java`, `setStateInternal`):

- **VPN consent.** `VpnService.prepare(context)` MUST return null, meaning WireGuard already holds VPN consent. Otherwise the call throws `VPN_NOT_AUTHORIZED`. The broadcast path cannot show the consent dialog. The UI toggle and the Quick Settings tile can (`BaseFragment.setTunnelState`, `TunnelToggleActivity`).
- **No other VPN.** Android grants consent to one VPN app at a time. If another app, e.g. poof's own `VpnService`, becomes the active VPN, `prepare()` for WireGuard is non-null again.
- **One tunnel at a time.** `GoBackend` runs a single tunnel. `SET_TUNNEL_UP` for tunnel B first brings tunnel A down. "Multiple tunnels" is a `WgQuickBackend`-only (root/kernel) setting and is hidden on the Go backend (`SettingsActivity.kt`).

### Package visibility (platform rule, not WireGuard code)

On Android 11+ (target SDK 30+), explicit interaction with another app is filtered by package visibility. A caller would declare `<queries><package android:name="com.wireguard.android"/></queries>`. See [developer.android.com/training/package-visibility](https://developer.android.com/training/package-visibility).

## 3. Reading state back

**Not exposed.** At this commit:

- **No outgoing broadcasts.** Nothing in `ui/src/main` or `tunnel/src/main` calls `sendBroadcast`.
- **No result.** The receiver uses no `setResult*` / ordered-broadcast result.
- **No exported provider, service or AIDL** for tunnel data.

Handshake time and rx/tx bytes come from `GoBackend.getStatistics()`, which parses `wgGetConfig`. Only WireGuard's own `TunnelDetailFragment` reads them, polling about once a second (`delay(1000)`). The Quick Settings tile shows only the last-used tunnel's name and on/off.

What another app can learn from Android itself is outside WireGuard's code. For example, `ConnectivityManager` reports that a network with `TRANSPORT_VPN` exists. It does not report which WireGuard tunnel is up, nor handshake or byte counts.

## 4. Deleting tunnels from another app

**No remote delete.** There is no action, extra or provider for it.

In the UI, `TunnelListFragment` action mode works like this:

1. Long-press a tunnel. Optionally tap more tunnels or "select all".
2. Tap the delete icon.

There is no confirmation dialog. `TunnelManager.delete` brings the tunnel down first if it is up. So deletion takes ≥2 taps per batch, all in WireGuard's UI.

Because names must be unique and import cannot overwrite (section 1), a per-Session tunnel left behind blocks a later import under the same name.

## 5. Always-on VPN, kill switch, and what the user sees

### Always-on

- `GoBackend.VpnService.onStartCommand` checks how the service was started. If it was started by the system rather than by WireGuard (`intent == null` or a foreign component), it calls the always-on callback.
- `Application.determineBackend` sets that callback to `tunnelManager.restoreState(true)`.
- `restoreState` brings up the tunnels in `UserKnobs.runningTunnels` (key `enabled_configs`). `setTunnelState` → `saveState()` rewrites that set after every toggle, including toggles from a remote-control broadcast.
- Always-on therefore restores **whichever tunnel was last left up**. It does not restore a tunnel named by another app.
- The always-on and lockdown ("Block connections without VPN") switches live in Android's Settings → VPN, not in WireGuard. `GoBackend.isAlwaysOn()` / `isLockdownEnabled()` exist but nothing exports them.

### Bringing a tunnel down

- `setStateInternal(DOWN)` calls `wgTurnOff` and then `VpnService.stopSelf()`.
- With lockdown on, Android blocks non-VPN traffic while no tunnel is up. This is platform behaviour.

### Kill-switch semantics inside a tunnel (`GoBackend.setStateInternal`)

- If the config has **exactly one peer** whose AllowedIPs include a `/0` route, `allowFamily()` is not called. The tunnel then blocks address families it does not route.
- Otherwise both `AF_INET` and `AF_INET6` are allowed to bypass.
- Also set: `setBlocking(true)`, `setMetered(false)` on Q+, `setUnderlyingNetworks(null)`, MTU defaulting to 1280.
- `setSession(tunnel.getName())` makes the **tunnel name** the session label in Android's VPN UI.

### What the user sees

- `GoBackend` does not start a foreground service and posts **no notification of its own**.
- What the user sees comes from Android: the VPN key icon and the system's VPN status/disconnect entry, labelled with the app and session name.
- WireGuard's Quick Settings tile is optional.
- With "Restore on boot", tunnels come back at boot. That setting is also `WgQuickBackend`-only. On the Go backend, Android's always-on is the boot path.

## 6. Play Store vs direct APK vs F-Droid

### Build types

`ui/build.gradle.kts` defines `release`, `debug` (suffix `.debug`), and `googleplay`. `googleplay` is `initWith(release)`.

The only `googleplay` source set is `ui/src/googleplay/AndroidManifest.xml`. All it does is remove `REQUEST_INSTALL_PACKAGES`. So the Play build and the direct-APK build expose **identical** intents, permission and receiver.

The difference is the self-updater (`updater/Updater.kt` `monitorForUpdates`):

- It is skipped when the installer is Google Play.
- It runs in the direct-APK build, downloading from `download.wireguard.com`.

### Distribution channels

The official install page ([wireguard.com/install](https://www.wireguard.com/install/)) lists only the Play Store and the direct APK.

F-Droid **no longer ships the app**:

- `f-droid.org/packages/com.wireguard.android` returns 404.
- fdroiddata first disabled the versions that have the in-app updater, in commit [`ed96ce5b`](https://gitlab.com/fdroid/fdroiddata/-/commit/ed96ce5be2bf38308aa2a1bafcdaab77f9e6fe48).
- It then removed the metadata in commit [`17bfc2e9`](https://gitlab.com/fdroid/fdroiddata/-/commit/17bfc2e9f29e984730967c845b3ec01ada72c985) "Remove WireGuard entirely, rather than shipping only crusty versions" (2023-10-24).

### Managed configuration (MDM)

`app_restrictions.xml` defines one restriction, `disable_config_export` (bool), read by `AdminKnobs.kt`. It hides the zip exporter. It offers no way to push configs.

## Not verified

- Behaviour on a device was not tested. Everything above comes from reading source.
- Exact picker tap counts depend on the OEM picker and where the file is saved.
- The app's README at this commit does not document the remote-control intents. The setting text and manifest are the only first-party description.
