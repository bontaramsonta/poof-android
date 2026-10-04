# Android WireGuard library options

Research for [#2](https://github.com/bontaramsonta/poof-android/issues/2) (map: [#1](https://github.com/bontaramsonta/poof-android/issues/1)).
Facts only. The choice is made in a later ticket.

Researched 2026-10-04. Frame: system mode only via `VpnService`, sideloaded APK,
one tunnel to one Exit. Constraint added mid-ticket: the owner MUST NOT need a
local Android Studio / Android SDK / NDK install.

## Sources

Pinned so every claim below can be re-checked.

| Ref | Source |
|---|---|
| WGA | wireguard-android, commit `e7b3a3c` (2026-03-17), GitHub mirror of git.zx2c4.com — https://github.com/WireGuard/wireguard-android/tree/e7b3a3c118836e112620b1302a8ba1873ad4daac |
| WGA-GoBackend | `tunnel/src/main/java/com/wireguard/android/backend/GoBackend.java` in WGA |
| WGA-libwg | `tunnel/tools/libwg-go/` (`api-android.go`, `Makefile`, `go.mod`, `goruntime-boottime-over-monotonic.diff`) in WGA |
| MVN | Maven Central `com.wireguard.android:tunnel` — https://repo1.maven.org/maven2/com/wireguard/android/tunnel/ (metadata + `1.0.20260102` AAR/POM, downloaded and inspected) |
| WGO | wireguard-go, commit `ecfc5a8` (2026-05-20) — https://git.zx2c4.com/wireguard-go |
| GOMOBILE | golang.org/x/mobile — https://github.com/golang/mobile (`cmd/gomobile/doc.go`, `cmd/gomobile/bind_androidapp.go`, `cmd/gobind/doc.go`) |
| GO-24595 | https://github.com/golang/go/issues/24595 and `src/runtime/sys_linux_arm64.s` on Go master |
| AND-VPN | https://developer.android.com/develop/connectivity/vpn |
| AND-FGS | https://developer.android.com/develop/background-work/services/fgs/service-types |
| POOF | `~/p/poof` — `internal/tunnel/{device.go,system_darwin.go}`, `go.mod`, ADR-0003, ADR-0004 |
| EXPO-* | docs.expo.dev pages linked inline below; https://expo.dev/pricing |
| GHA | https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md |

## How poof uses wireguard-go today (baseline)

- poof pins `golang.zx2c4.com/wireguard v0.0.0-20260522210424-ecfc5a8d5446` and Go 1.25.3. [POOF `go.mod`]
- It configures the engine with a UAPI string: `private_key`, `public_key`, `endpoint`, `allowed_ip=0.0.0.0/0`, `persistent_keepalive_interval=25`. MTU 1420. [POOF `device.go`]
- Status (handshake time, rx/tx) comes from parsing `IpcGet()` output: `last_handshake_time_sec/nsec`, `rx_bytes`, `tx_bytes`. [POOF `device.go` `parseStatus`]
- Keepalive is load-bearing: it drives the ~2 min re-handshake cadence that the Exit's dead-man's switch watches. [ADR-0003]
- macOS system mode creates a utun via `tun.CreateTUN` + `conn.NewDefaultBind()`. Android cannot do this; the TUN fd comes from `VpnService.Builder.establish()`. [POOF `system_darwin.go`; AND-VPN]

## Option A — official `com.wireguard.android:tunnel` (GoBackend)

### What it is

- An AAR with Java API (`Backend`, `GoBackend`, `WgQuickBackend`, `Config`, `Interface`, `Peer`, `Statistics`, `Key`, `KeyPair`) and prebuilt native libs. [WGA, MVN]
- `GoBackend` = wireguard-go in userspace, no root. `WgQuickBackend` = kernel module via `wg-quick` and a root shell (`RootShell`, `ToolsInstaller`). The kernel backend needs root and a WireGuard-enabled kernel, so it does not apply to an unrooted phone. [WGA README; WGA `WgQuickBackend.java`]

### Bringing a tunnel up/down from a runtime config

- Config can be built in code, no file needed: `Interface.Builder` (`setKeyPair`, `addAddress`, `addDnsServer`, `setMtu`, `excludeApplication`…), `Peer.Builder` (`setPublicKey`/`parsePublicKey`, `addAllowedIp`, `setEndpoint`/`parseEndpoint`, `setPersistentKeepalive`), `Config.Builder` (`setInterface`, `addPeer`). `Config.parse(...)` also accepts wg-quick text. [WGA `config/*.java`]
- Up/down is one call: `backend.setState(tunnel, Tunnel.State.UP | DOWN, config)`. `Tunnel` is an app-implemented interface (`getName()`, `onStateChange(State)`); name must match `[a-zA-Z0-9_=+.-]{1,15}`. [WGA `Backend.java`, `Tunnel.java`]
- Before UP, the app MUST have VPN consent: `GoBackend` throws `BackendException(VPN_NOT_AUTHORIZED)` if `VpnService.prepare(context) != null`. The app launches the consent intent itself. [WGA-GoBackend; AND-VPN]
- Endpoint hostnames are pre-resolved with up to 10 retries, 1 s apart. [WGA-GoBackend `DNS_RESOLUTION_RETRIES`]
- `GoBackend` runs at most one tunnel; bringing up a second takes the first down. [WGA-GoBackend `setState`]
- UAPI emitted by the library: interface gives `private_key` (+ optional `listen_port`); peer gives `public_key`, `allowed_ip`, resolved `endpoint`, `persistent_keepalive_interval`, `preshared_key`. Same keys poof's `buildIPC` writes today. [WGA `Interface.java`, `Peer.java`; POOF `device.go`]

### How it gets the TUN fd

- The library ships its own `VpnService` subclass, `GoBackend$VpnService`, declared in the AAR manifest with `BIND_VPN_SERVICE` and the `android.net.VpnService` intent filter. Manifest merge adds it to the app. [WGA `tunnel/src/main/AndroidManifest.xml`; MVN AAR manifest]
- `GoBackend` starts that service with `context.startService(new Intent(context, VpnService.class))`, waits up to 2 s for `onCreate`, then builds: `addAddress`, `addDnsServer`, `addRoute` per AllowedIP, `setMtu` (default **1280** if none set), `setMetered(false)` on API 29+, `setUnderlyingNetworks(null)`, `setBlocking(true)`, then `establish()`. [WGA-GoBackend lines ~250–337]
- The fd is handed to Go with `wgTurnOn(name, tun.detachFd(), uapiString)`. Go wraps it with `tun.CreateUnmonitoredTUNFromFD(fd)` and `device.NewDevice(tun, conn.NewStdNetBind(), logger)`. [WGA-GoBackend; WGA-libwg `api-android.go`; WGO `tun/tun_linux.go`]
- After up, Java calls `service.protect()` on the v4 and v6 UDP socket fds obtained via `wgGetSocketV4/6` (wireguard-go's `conn.PeekLookAtSocketFd`, Android-only file `conn/boundif_android.go`). This keeps the encrypted carrier out of the tunnel — the same loop poof avoids on macOS with a host route. [WGA-GoBackend; WGO; AND-VPN "Call VpnService.protect() … avoid a circular connection"; ADR-0004]
- "Kill-switch semantics": if the config has exactly one peer with a `/0` AllowedIP, `GoBackend` does **not** call `allowFamily(AF_INET/AF_INET6)`; otherwise it allows both. Relevant to the open IPv6-leak item on the map; the Android-side effect was not verified here. [WGA-GoBackend lines ~320–328]
- Limitation by construction: the `VpnService` class is the library's. An app cannot substitute its own subclass under `GoBackend`. `GoBackend$VpnService` does not call `startForeground()` and does not override `onRevoke()`. [WGA-GoBackend — grep finds neither]

### Stats for the live status line

- `backend.getStatistics(tunnel)` returns `Statistics`; `peer(key)` gives `PeerStats(rxBytes, txBytes, latestHandshakeEpochMillis)`; also `totalRx()`, `totalTx()`, `isStale()` (>900 ms old). [WGA `Statistics.java`]
- It is a poll, not a push: each call does `wgGetConfig` → `IpcGet()` and parses the same keys poof's `parseStatus` parses. No callback or event for stats exists. Tunnel state changes do arrive via `Tunnel.onStateChange`. [WGA-GoBackend `getStatistics`]
- The official app also polls (`TunnelManager.getTunnelStatistics`). [WGA `ui/.../TunnelManager.kt`]

### Roaming across Wi-Fi / cellular

- `wgTurnOn` calls `device.DisableSomeRoamingForBrokenMobileSemantics()` after `IpcSet`. [WGA-libwg]
- Effect in wireguard-go: for peers that already have a configured endpoint, `disableRoaming=true`, so `SetEndpointFromPacket` no longer updates the peer endpoint from inbound packets. The client keeps sending to the configured Exit address. [WGO `device/mobilequirks.go`, `device/peer.go` `SetEndpointFromPacket`, `device/uapi.go` `handlePostConfig`]
- Client-side network change: the bind (`StdNetBind`) uses unbound UDP sockets; the library registers no `ConnectivityManager.NetworkCallback` and does no rebind. `setUnderlyingNetworks(null)` tells Android the VPN follows the system default network. The Exit learns the phone's new source address from the next authenticated packet (standard WireGuard server-side roaming). On-device behavior across a Wi-Fi→LTE switch was not tested here. [WGA-GoBackend; grep of WGA for `NetworkCallback` finds none]
- Sleep: the Go toolchain used by WGA is patched to make the runtime's `nanotime` use `CLOCK_BOOTTIME` instead of `CLOCK_MONOTONIC`, so timers account for suspend. Upstream Go still uses `CLOCK_MONOTONIC` (`sys_linux_arm64.s`), and the upstream proposal golang/go#24595 was withdrawn in 2019. [WGA-libwg `Makefile`, `.diff`; GO-24595]

### Persistent keepalive

- Supported: `Peer.Builder.setPersistentKeepalive(int)` → `persistent_keepalive_interval=` in UAPI. [WGA `Peer.java`]

### Min SDK, license, maintenance, size

- minSdk **24** (AAR manifest `uses-sdk minSdkVersion=24`; `build.gradle.kts` `minSdk = 24`). compileSdk 36, Java 17. [MVN; WGA `tunnel/build.gradle.kts`]
- License: **Apache-2.0** (POM + SPDX headers). Runtime deps: `androidx.annotation`, `androidx.collection` only. [MVN POM; WGA]
- README says the library uses Java 8 APIs and asks embedders to enable core library desugaring. The published AAR's metadata has `coreLibraryDesugaringEnabled=false`. Which one binds at minSdk 24 was not checked. [WGA README; MVN `aar-metadata.properties`]
- Maven Central versions: latest **1.0.20260102**. Repo is at `1.0.20260315` (tag exists) but that version is **not** on Central. Earlier: 1.0.20251231, 1.0.20250531, 1.0.20230706 … 1.0.20210211 — long gaps between 2023-07 and 2025-05. [MVN `maven-metadata.xml`; WGA tags]
- Repo activity: commits through 2026-03-17 (AGP 9.1, minSdk alignment, version bumps). [WGA `git log`]
- Bundled wireguard-go: `v0.0.0-20250521234502-f333402bd9cb` (2025-05-22), built with Go **1.24.3** (patched). poof's Mac client pins a newer wireguard-go (`ecfc5a8`, 2026-05). [WGA-libwg `go.mod`, `Makefile`; MVN `.so` strings; POOF]
- AAR size **5.6 MB** compressed. Native libs per ABI (uncompressed): `libwg-go.so` arm64-v8a 3.37 MB, armeabi-v7a 3.17 MB, x86 3.20 MB, x86_64 3.61 MB; plus `libwg.so` + `libwg-quick.so` (~50–125 KB per ABI, only used by the kernel backend); `classes.jar` 75 KB. All four ABIs ≈ 13.8 MB uncompressed. `libwg-go.so` gzips to ~1.3–1.45 MB per ABI. [MVN AAR, measured]
- APK impact depends on packaging: a universal APK carries all four ABIs; restricting to `arm64-v8a` (abiFilters) leaves ~3.5 MB of native code. Whether `.so` are stored compressed in the APK depends on the AGP `useLegacyPackaging` setting (not verified for an Expo build).

### Local tooling needed

- Consuming the Maven AAR needs Gradle + Android SDK only. **No NDK, no Go**: the `.so` files are prebuilt in the AAR. [MVN]
- Building the library from source (e.g. to bump wireguard-go) needs the NDK + CMake; its Makefile downloads Go 1.24.3, verifies its hash, and patches it. [WGA `tools/CMakeLists.txt`, `libwg-go/Makefile`]

## Option B — bind wireguard-go ourselves via gomobile

### What gomobile gives

- `gomobile bind -target android` produces an AAR with Java stubs and `.so` for arm, arm64, 386, amd64 (subset selectable, e.g. `-target=android/arm64`). Needs `javac`, Android SDK, and NDK (`ANDROID_HOME`, `ANDROID_NDK_HOME`). Default/minimum `-androidapi` is **16**. [GOMOBILE `doc.go`, `bind_androidapp.go` `minAndroidAPI = 16`]
- Exported API is limited to: signed ints/floats, string, bool, `[]byte`, functions/interfaces/structs over those. Panics crossing the boundary kill the process. [GOMOBILE `cmd/gobind/doc.go`]
- License: BSD-3-Clause (golang.org/x/mobile). wireguard-go: MIT. [GOMOBILE; WGO `LICENSE`]
- golang/mobile last commit 2026-09-08. [GitHub API]

### What we would have to write

Everything `libwg-go` + `GoBackend` already do, re-done over a gomobile-shaped API:

- A Go wrapper package exporting e.g. `TurnOn(tunFd int, uapi string) (int, error)`, `TurnOff(h int)`, `GetConfig(h int) (string, error)`, `SocketV4/V6(h int) int`. Internals mirror WGA `api-android.go`: `tun.CreateUnmonitoredTUNFromFD`, `device.NewDevice(..., conn.NewStdNetBind(), ...)`, `IpcSet`, `DisableSomeRoamingForBrokenMobileSemantics()`, `Up()`, `PeekLookAtSocketFd4/6`. All of these are public wireguard-go API. [WGO]
- Our own `VpnService` subclass: `prepare()` flow, `Builder` (addresses, routes, DNS, MTU), `establish()`, `detachFd()` into Go, `protect()` both sockets, `startForeground()`, `onRevoke()`. [AND-VPN]
- Stats: poll `IpcGet()` and parse — poof's `parseStatus` could be reused in Go and exported as a struct with int64 fields (supported types). [POOF `device.go`; GOMOBILE]
- Sleep timers: the CLOCK_BOOTTIME runtime patch is not in upstream Go; gomobile uses whatever `go` is on PATH, so matching WGA behavior means building with a patched toolchain. [GO-24595; WGA-libwg]
- Upside: we choose the wireguard-go version (could match poof's pin) and own the `VpnService` (foreground notification, revoke handling).

### Local tooling needed

- `gomobile bind` needs the NDK. It can run in CI instead of locally: GitHub-hosted `ubuntu-24.04` runners ship Android SDK, NDK 27.3 (default, `ANDROID_NDK_HOME` set), 28.2, 29.0, and Go 1.24/1.25/1.26. The AAR is then a build artifact the app consumes. [GHA]
- EAS Android images also ship an NDK (27.1.12297006) but no Go toolchain is listed; Go could be installed via an npm `eas-build-pre-install` hook (apt), at the cost of build time. [EXPO infrastructure]

## Option C — React Native + Expo

### Can Expo host a VpnService + WireGuard tunnel?

- Expo Go cannot load custom native code; a **development build** (your own Expo Go) or a release build is required. [EXPO dev builds: https://docs.expo.dev/develop/development-builds/introduction/]
- A **local Expo module** (`npx create-expo-module@latest --local` → `modules/<name>/` with `android/`, `expo-module.config.json`) holds Kotlin that can depend on `com.wireguard.android:tunnel` in its own `build.gradle`. [EXPO modules: https://docs.expo.dev/modules/get-started/]
- Module API: `AsyncFunction` (Promise, runs off the JS thread), `Events("…")` + `sendEvent(name, payload)`, `OnCreate`/`OnDestroy`, Android `Context` via `appContext.reactContext`. [EXPO: https://docs.expo.dev/modules/module-api/]
- New Architecture: mandatory from SDK 55 (RN 0.83); Expo Modules API modules support it with no extra work. Old-bridge modules (`ReactContextBaseJavaModule`) run via the interop layer, "not perfect". [EXPO: https://docs.expo.dev/guides/new-architecture/]
- Manifest/Gradle changes the module cannot carry itself go in a **config plugin** (`withAndroidManifest`, etc.), applied at `npx expo prebuild` (CNG). Library-AAR manifests (e.g. `GoBackend$VpnService`) merge automatically. A foreground service for a VPN uses type `systemExempted` with `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`; VPN apps are listed as eligible. [EXPO: https://docs.expo.dev/config-plugins/introduction/; AND-FGS]
- `prepare()` returns a consent Activity intent; the module needs the current Activity to launch it. [AND-VPN]

### Existing RN / Expo WireGuard libraries (npm + GitHub, 2026-10-04)

| Package | Latest / date | Arch | WireGuard backend | Stats exposed | Notes |
|---|---|---|---|---|---|
| `react-native-wireguard-vpn` (usama7365) | 1.0.22, 2026-03-25 | Old bridge (`ReactContextBaseJavaModule`) → interop layer | `com.wireguard.android:tunnel:1.0.20211029` (GoBackend) | No — `getStatus` returns state only; emits `vpnStateChanged` | MIT, 13 stars, ~650 dl/month, ships `app.plugin.js` config plugin; android `minSdkVersion 21` (below the tunnel AAR's 24) |
| `expo-wireguard` (norenz92) | 0.1.0, 2025-05-11 | Expo module | **None on Android** — `ExpoWireguardModule.kt` is the create-expo-module template (`hello`, `setValueAsync`); iOS ships a wg-go xcframework | No | No license field, 0 stars |
| `react-native-wg` (tlodge) | 0.1.5, 2022-05-15 | — | — | — | Unmaintained since 2022 |
| `react-native-wireguard` | unpublished/empty, 2019 | — | — | — | Dead |

[npm registry, npm downloads API, GitHub API, repo sources]

None exposes handshake age or rx/tx. None was found on the Expo Modules API with a working Android tunnel.

### JS getting live stats

- No native stats push exists in any option (Option A `getStatistics` is a poll; Option B would poll `IpcGet`). [WGA-GoBackend]
- Two shapes the Expo Modules API supports: (1) JS calls an `AsyncFunction("getStats")` on a timer; (2) Kotlin polls `getStatistics` on its own timer and `sendEvent("onStats", {rx, tx, lastHandshakeMs})`, with JS subscribing. State changes come from `Tunnel.onStateChange` → `sendEvent`. [EXPO module-api; WGA]
- If the JS context is gone (app backgrounded/killed), only the native side and the foreground notification can show stats. [inference from AND-VPN "non-dismissible notification… network stats"]

### Building without local Android tooling (EAS Build)

- APK for sideloading: `eas.json` profile with `"android": { "buildType": "apk" }`, run `eas build -p android --profile preview`; install from the build URL or `adb install`. [EXPO: https://docs.expo.dev/build-reference/apk/]
- Compilation happens on EAS servers; no local native toolchain needed. [EXPO dev builds intro]
- Android worker image (latest): NDK 27.1.12297006, JDK 17, Android SDK, Node 22. 4 vCPU/16 GB (medium), 8 vCPU/32 GB (large, paid). System deps can be added via npm hooks. [EXPO: https://docs.expo.dev/build-reference/infrastructure/]
- Local Expo modules under `modules/` are part of the app's native project, so they compile in the same Gradle build on EAS. The docs do not single this out; inferred from the module being autoimported and prebuild generating `android/`. [EXPO get-started]
- Free plan: **15 Android builds/month**, 1 concurrency, **45-min timeout**, low-priority queue. Starter: $19/month + usage ($45 build credit), 2-hour timeout, high priority, large workers. [EXPO pricing]
- Build times: Expo publishes no fixed figure; free-tier queue wait is variable ("low priority"). Not measured here.
- Dev loop: JS-only changes reload against an installed dev build (`npx expo start`). **Any native change** (Kotlin module, new native dep, app config) needs a new dev build — on EAS that is one cloud build each, counting against the 15/month. [EXPO dev builds intro]

## Local-tooling matrix (against the new constraint)

| Option | Local Android Studio / SDK / NDK? | Where native compile happens |
|---|---|---|
| A. Native Android app + `tunnel` AAR | SDK + Gradle needed **if built locally**; no NDK. Can instead build entirely in CI (GHA image has SDK). | Gradle; `.so` prebuilt in AAR |
| B. Native app + gomobile AAR | NDK + Go needed to run `gomobile bind` — **or** run it in CI (GHA has NDK + Go). | CI for the AAR, then Gradle |
| C1. Expo + local module wrapping `tunnel` AAR | None locally; EAS builds. | EAS Gradle build |
| C2. Expo + local module + gomobile AAR | None locally if the AAR is built in CI (GHA) and vendored/published; or install Go on EAS via hook. | CI + EAS |
| C3. Expo + `react-native-wireguard-vpn` | None locally; EAS builds. | EAS; library pins tunnel 1.0.20211029, no stats |

Testing on a device without local SDK still needs `adb` (platform-tools) or the APK-URL install path; `adb` is not the Android SDK/Studio but is part of it. [EXPO apk page]

## Open questions (not answered here)

- On-device Wi-Fi↔cellular behavior with `DisableSomeRoamingForBrokenMobileSemantics` and no network callback.
- Exact effect of omitting `allowFamily` in the one-peer `/0` case on IPv6 (map's IPv6-leak item).
- Whether core library desugaring is required for the `tunnel` AAR at minSdk 24.
- Expo SDK's default `minSdkVersion` vs the AAR's 24.
- Real EAS free-tier queue + build durations for this app.
