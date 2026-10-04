# Native Kotlin vs Expo without EAS

Research for [#14](https://github.com/bontaramsonta/poof-android/issues/14) (map: [#1](https://github.com/bontaramsonta/poof-android/issues/1)).
Facts only. The choice is made in [#8](https://github.com/bontaramsonta/poof-android/issues/8).

Researched 2026-10-04. Builds on
[`research/android-wireguard-library.md`](https://github.com/bontaramsonta/poof-android/blob/research/android-wireguard-library/research/android-wireguard-library.md)
(tunnel library internals, Expo module API, EAS facts). That material is not repeated here.

Frame: macOS on Apple Silicon, editing in Zed / VS Code, Android Studio now
allowed, **no EAS**. App: token setup, Country picker, connecting/connected
status (bytes, uptime, Exit IP), Disconnect. Core: `VpnService` +
`com.wireguard.android:tunnel` (GoBackend), HTTPS to the control plane with a
bearer token, Keystore-backed storage. Sideloaded APK.

Claims marked **[inference]** are reasoning from the cited sources, not stated by them.
Nothing was installed or built on this machine; no build times were measured.

## Sources

| Ref | Source |
|---|---|
| AND-TOOLS | https://developer.android.com/tools |
| AND-SDKM | https://developer.android.com/tools/sdkmanager |
| AND-CLI | https://developer.android.com/tools/agents/android-cli and `/commands/create` |
| AND-CMDLINE | https://developer.android.com/build/building-cmdline |
| AND-AGP | https://developer.android.com/build/releases/gradle-plugin (AGP 9.4.0, Sept 2026) |
| AND-AGPABOUT | https://developer.android.com/build/releases/about-agp |
| AND-BIK | https://developer.android.com/build/migrate-to-built-in-kotlin |
| AND-NDK | https://developer.android.com/studio/projects/install-ndk ; https://developer.android.com/ndk/downloads |
| AND-STUDIO | https://developer.android.com/studio/install (system requirements) |
| AND-DEVICE | https://developer.android.com/studio/run/device ; https://developer.android.com/tools/adb |
| AND-PREVIEW | https://developer.android.com/develop/ui/compose/tooling/previews |
| AND-SHOT | https://developer.android.com/studio/preview/compose-screenshot-testing |
| AND-LIVE | https://developer.android.com/develop/ui/compose/tooling/iterative-development |
| AND-BOM | https://developer.android.com/develop/ui/compose/bom |
| AND-STATE | https://developer.android.com/develop/ui/compose/state |
| AND-VPN | https://developer.android.com/develop/connectivity/vpn |
| AOSP-VPN | `core/java/android/net/VpnService.java` `onRevoke()` (aosp-mirror/platform_frameworks_base, main) |
| AND-FGS | https://developer.android.com/develop/background-work/services/fgs/service-types |
| AND-KS | https://developer.android.com/privacy-and-security/keystore |
| AND-SEC | https://developer.android.com/jetpack/androidx/releases/security |
| AND-SIGN | https://developer.android.com/studio/publish/app-signing |
| AND-VERIFY | https://developer.android.com/developer-verification ; https://developer.android.com/developer-verification/guides/faq |
| WGA-GoBackend | wireguard-android `e7b3a3c`, `tunnel/.../backend/GoBackend.java` |
| KLSP | https://github.com/Kotlin/kotlin-lsp (README, releases via GitHub API) |
| ZED-KT | https://zed.dev/docs/languages/kotlin |
| BREW | formulae.brew.sh casks `android-commandlinetools`, `android-platform-tools`, `android-studio`, `temurin@17` |
| MVN | Maven Central / Google Maven `maven-metadata.xml` (OkHttp, Ktor, Compose BOM, AGP), fetched 2026-10-04 |
| EXPO-LOCAL | https://docs.expo.dev/guides/local-app-development/ |
| EXPO-PROD | https://docs.expo.dev/guides/local-app-production/ |
| EXPO-ENV | https://docs.expo.dev/workflow/android-studio-emulator/ |
| EXPO-CNG | https://docs.expo.dev/workflow/continuous-native-generation/ |
| EXPO-FAQ | https://docs.expo.dev/faq/ ("EAS" optional) |
| EXPO-EASLOCAL | https://docs.expo.dev/build-reference/local-builds/ |
| EXPO-VER | https://docs.expo.dev/versions/latest/ |
| EXPO-UPG | https://docs.expo.dev/workflow/upgrading-expo-sdk-walkthrough/ |
| EXPO-MOD | https://docs.expo.dev/modules/module-api/ |
| EXPO-TPL | expo/expo branch `sdk-57`, `templates/expo-template-bare-minimum/android/*` |
| EXPO-GP | expo/expo `packages/expo-modules-autolinking/android/expo-gradle-plugin/README.md` |
| RN-0.86 | facebook/react-native branch `0.86-stable`: `packages/react-native/gradle/libs.versions.toml`, `gradle-plugin/.../NdkConfiguratorUtils.kt` |
| RN-ENV | https://reactnative.dev/docs/set-up-your-environment?os=macos&platform=android |
| GHA | actions/runner-images `images/ubuntu/Ubuntu2404-Readme.md` (updated 2026-10-02) |
| GH-SECRETS | https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets |
| LOCAL | Read-only inspection of this Mac (`~/Library/Android/sdk`, `java_home -V`, `du`) |

## 1. Tooling footprint on macOS (Apple Silicon)

### What a native Kotlin + Compose build needs

| Piece | Needed? | How it gets there |
|---|---|---|
| JDK 17+ | Yes. AGP 9.4.0 minimum and default JDK is **17**. [AND-AGP] | `brew install --cask temurin@17` (arm64 build available). [BREW] Android Studio also bundles a JBR. |
| Android SDK: `platforms;android-36`, `build-tools;36.0.0`, `platform-tools` | Yes. AGP 9.4.0 needs SDK Build Tools **36.0.0**, supports up to API 37. [AND-AGP] | `sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"`, then `sdkmanager --licenses`. [AND-SDKM] |
| `cmdline-tools` (sdkmanager, avdmanager, lint, apkanalyzer, retrace) | Only to manage the SDK without Studio. [AND-TOOLS] | `brew install --cask android-commandlinetools` (version 15859902, needs Java; SDK root `$HOMEBREW_PREFIX/share/android-commandlinetools`). [BREW] Manual install MUST use layout `android_sdk/cmdline-tools/latest/{bin,lib,…}`. [AND-SDKM] |
| Gradle | No global install. The project's `./gradlew` wrapper downloads the pinned Gradle (AGP 9.4.0 needs Gradle **9.6.0**). [AND-AGP] | — |
| Kotlin Gradle plugin | No. AGP 9 has **built-in Kotlin**; `org.jetbrains.kotlin.android` MUST be removed. [AND-BIK] | Compose compiler plugin version = Kotlin version (Kotlin 2.0+). [AND-BOM] |
| NDK / CMake | **No.** The tunnel AAR ships prebuilt `.so`. [prior research, MVN] | — |
| `adb` | Yes, to install on a phone. | Part of `platform-tools`, or `brew install --cask android-platform-tools` (37.0.1). [BREW] |
| Emulator + system image | Optional. A physical phone is enough. | `sdkmanager "emulator" "system-images;…;arm64-v8a"` + `avdmanager`. [AND-TOOLS] |
| Android Studio IDE | **Not required to build, sign, install.** "You do not need Android Studio to sign your app." [AND-CMDLINE] | `brew install --cask android-studio` (2026.2.1.8 "Rabbit 1", self-updating). [BREW] |

New since the last ticket: Google ships an **Android CLI** (`android`) for
non-Studio and agent workflows: `android create` (default template is an empty
Compose activity), `android sdk install|list|remove|update`, `android emulator
create|start|stop`, `android run`, `android skills` (e.g. `agp-9-upgrade`).
Download from developer.android.com, then `android update`. [AND-CLI, AND-AGPABOUT]
It can replace hand-writing the initial Gradle project and most `sdkmanager` use.

### Command-only build/install/debug loop

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk, debug-signed
./gradlew installDebug         # build + install on the connected device/emulator
adb install path/to/app.apk    # or adb -d install …
```
[AND-CMDLINE]

- Wireless install without a cable: Android 11+, `adb pair ip:port` once with the
  pairing code, then `adb connect ip:port`. No Studio needed. adb ≥ 37 on
  Android 17 reconnects automatically on a trusted network. [AND-DEVICE]
- Logs: `adb logcat`. Step debugging: Kotlin LSP's README lists debugging work
  for JVM projects, but no source documents attaching to an Android process
  from Zed/VS Code. **[inference]** Breakpoint debugging on device is
  realistically Android Studio only; without it you debug with logcat.

### Editor support (Zed / VS Code)

- JetBrains **Kotlin LSP** is **Alpha**. Build systems: "Gradle, Maven,
  experimental Android Gradle Plugin support". Partially closed-source. Install
  via VS Code Marketplace or `brew install JetBrains/utils/kotlin-lsp`. [KLSP]
- Android project import landed in v262.4739.0 (2026-04-27); v263.4702.0
  (2026-09-13) fixed a `ClassCastException` on Android Gradle import. Latest
  v263.6379.0 (2026-10-03). [KLSP releases]
- Zed uses Kotlin LSP **by default** for Kotlin; the community
  `kotlin-language-server` is the alternative. [ZED-KT]
- **[inference]** Expect completion/navigation to work but rough edges with
  AGP-generated sources (R class, BuildConfig, Compose compiler output) given
  the "experimental" label. Not tested here.

### Compose without Android Studio

- `@Preview` rendering uses Layoutlib **inside Android Studio**. [AND-PREVIEW]
- Live Edit (push composable edits to a running device) requires Android Studio
  Giraffe+, API 30+ device, AGP 8.1+. Studio-only. [AND-LIVE]
- Outside Studio: Compose **Preview Screenshot Testing** (`com.android.compose.screenshot`,
  experimental alpha; standalone plugin deprecated in favour of AGP test
  suites) renders `@Preview`s host-side with no device:
  `./gradlew updateDebugScreenshotTest` / `validateDebugScreenshotTest`; HTML
  report at `app/build/reports/screenshotTest/preview/debug/index.html`. [AND-SHOT]
- **[inference]** Without Studio, the UI loop is edit → `./gradlew installDebug`
  → look at the phone. With Studio: previews + Live Edit.

### Disk and upkeep

- Android Studio minimums on macOS: 8 GB free (Studio), 16 GB (Studio +
  Emulator); recommended 32 GB SSD free; each extra AVD up to 6 GB. RAM 8 GB
  min, 16 GB with emulator, 32 GB recommended. [AND-STUDIO]
- NDK r30 macOS download alone is 1.07 GB (relevant to Expo, below). [AND-NDK]
- **This Mac already has an Android SDK** at the Studio default path
  `~/Library/Android/sdk` (9.3 GB): `platforms` 34/35/36, `build-tools`
  34.0.0/35.0.0, `platform-tools` (adb 36.0.2), `emulator` 36.3.10, two
  `android-35` arm64 Play Store system images (6.9 GB of the 9.3), licenses
  already accepted. **No `cmdline-tools`, no NDK.** Also `~/.android/avd` 9.4 GB
  (two Pixel 9 AVDs) and `~/.gradle` 5.9 GB. Android Studio.app is not in
  `/Applications`. JDKs present: OpenJDK 25-ea, GraalVM 24, JBR 21. [LOCAL]
- **[inference]** For native, the missing pieces are `build-tools;36.0.0`
  (AGP can fetch it since licenses are accepted) and optionally
  `cmdline-tools`. JDK 21 (JBR) already satisfies "JDK 17 minimum". The AVDs
  and system images (~16 GB) are deletable if a physical phone is used.
- Upkeep (native): JDK cask updates; `sdkmanager --update` (or `android sdk
  update`) for platform/build-tools when bumping `compileSdk`; Gradle via the
  wrapper per project; Studio self-updates if installed.

## 2. Native VPN path (no JS bridge)

What the app writes, concretely. GoBackend facts are in the prior research.

| Concern | Native Kotlin shape | Source |
|---|---|---|
| Consent | `VpnService.prepare(context)`; if non-null, launch it with `registerForActivityResult(StartActivityForResult)` from the Activity. GoBackend throws `VPN_NOT_AUTHORIZED` otherwise. | AND-VPN; WGA-GoBackend |
| Tunnel up/down | `GoBackend(context).setState(tunnel, UP/DOWN, config)` with `Config.Builder`/`Interface.Builder`/`Peer.Builder` built from the `POST /exits` response. | prior research |
| VpnService class | Owned by the library: `GoBackend$VpnService` (public static, extends `android.net.VpnService`). It overrides `onCreate`, `onDestroy`, `onStartCommand` only — no `startForeground`, no `onRevoke`. | WGA-GoBackend |
| `onRevoke` | AOSP default `onRevoke()` calls `stopSelf()`. GoBackend's `onDestroy` then calls `wgTurnOff` and `tunnel.onStateChange(DOWN)`. **[inference]** So the app's `Tunnel.onStateChange(DOWN)` is the revoke signal; the app then calls `DELETE /exits/{region}/{id}`. Not tested on a device. | AOSP-VPN; WGA-GoBackend |
| Foreground service | Android 8+ needs `startForeground()`; a non-dismissible notification while active. Type `systemExempted` + permission `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`; VPN apps are eligible, no runtime prerequisites. **[inference]** Since the library owns the `VpnService`, the app adds its **own** `Service` (e.g. `SessionService`) declared `foregroundServiceType="systemExempted"`, which holds the `GoBackend`, the stats poll, and the notification. | AND-VPN; AND-FGS |
| Stats into UI | `backend.getStatistics(tunnel)` polled (e.g. every 1 s) in a coroutine in the service → `MutableStateFlow<Status>`; UI collects with `collectAsStateWithLifecycle()` (`androidx.lifecycle:lifecycle-runtime-compose`). Same flow updates the notification text. | prior research; AND-STATE |
| Token + Session keys | `androidx.security:security-crypto` (`EncryptedSharedPreferences`) is **deprecated** (1.1.0-beta01, 2025-06-04) "in favour of existing platform APIs and direct use of Android Keystore". Pattern: AES-GCM key in `AndroidKeyStore` (`KeyGenParameterSpec`, `PURPOSE_ENCRYPT or PURPOSE_DECRYPT`, `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`), encrypt values, store ciphertext + IV in DataStore/SharedPreferences. **[inference]** The WireGuard private key must reach GoBackend as raw bytes (`Key`), so it is stored wrapped by the Keystore AES key, not as a non-exportable Keystore key. | AND-SEC; AND-KS |
| HTTP | OkHttp 5.5.0 or Ktor client 3.6.0 (OkHttp engine); kotlinx-serialization for JSON. Bearer header via an OkHttp `Interceptor` or Ktor `defaultRequest`. | MVN |

**[inference]** Rough native file list for v1:
`settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`,
`gradle/libs.versions.toml`, `AndroidManifest.xml` (permissions
`INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`,
`POST_NOTIFICATIONS`; the `SessionService`), `MainActivity.kt`,
`SessionService.kt`, `ControlPlaneClient.kt`, `SecureStore.kt`,
`SessionRepository.kt` (state machine + `StateFlow`), and 3–4 screen
composables. The Expo route needs the same Kotlin for the service, stats,
Keystore and consent (prior research: the module "must cover foreground
service, `onRevoke`, and stats polling"), plus a JS bridge on top.

## 3. Compose effort and dev loop

- Screens: token entry (`TextField` + save), Country list (`LazyColumn`),
  connecting (spinner + elapsed), connected (Country, Exit IP, uptime, rx/tx,
  Disconnect). **[inference]** Material 3 components cover all of these; no
  navigation library is strictly needed (a `when(state)` over the session
  state renders the right screen).
- Default project: `android create --name=poof --application-id=… --output=…`
  gives an empty Compose activity project. [AND-CLI]
- Versions today: Compose BOM **2026.09.00**; AGP 9.4.0 stable (9.5.0-alpha08
  newest); Gradle 9.6.0 for AGP 9.4. [AND-BOM; MVN; AND-AGP]
- Build/install times: **not measured**; no primary source gives figures.
- Dev loop: see §1 — with Studio, previews + Live Edit; without, install-and-look,
  plus optional host-side screenshot tests.

## 4. Expo without EAS

### What a local Expo Android build needs

- Same Android SDK + JDK. Expo's own setup page says: Android Studio,
  `brew install --cask zulu@17`, `ANDROID_HOME=$HOME/Library/Android/sdk`,
  SDK Platform 36. React Native recommends JDK 17: "You may encounter problems
  using higher JDK versions." [EXPO-ENV; RN-ENV]
- Plus Node + npm.
- **Plus the NDK and CMake.** RN's Gradle plugin enables prefab and points
  `externalNativeBuild.cmake` at RN's default app `CMakeLists.txt`, so every
  Expo/RN app compiles C++. [RN-0.86 `NdkConfiguratorUtils.kt`]
  Expo SDK 57 / RN 0.86 pins **NDK 27.1.12297006**, AGP 8.12.0, Kotlin 2.1.20,
  minSdk 24, compile/target 36, build-tools 36.0.0; template Gradle 9.3.1.
  [RN-0.86; EXPO-TPL; EXPO-GP] AGP auto-installs the pinned NDK and CMake on
  first build if licenses are accepted. [AND-NDK]
- Template builds all four ABIs (`reactNativeArchitectures=armeabi-v7a,arm64-v8a,x86,x86_64`),
  `newArchEnabled=true`, `hermesEnabled=true`. [EXPO-TPL `gradle.properties`]
- Commands:
  - `npx expo prebuild` generates `android/` (gitignored in new projects);
    `--clean` regenerates from scratch and is "the safest way". CNG "is entirely
    independent of EAS". [EXPO-CNG]
  - `npx expo run:android` (dev build, installs, starts Metro);
    `--variant release` for a release build, "not signed" for stores. [EXPO-LOCAL]
  - Signed release: `keytool …`, set `MYAPP_UPLOAD_*` in `android/gradle.properties`,
    edit `android/app/build.gradle` `signingConfigs`, then `./gradlew app:bundleRelease`
    (or `assembleRelease` for an APK). [EXPO-PROD]
- `eas build --local` is **not** EAS-free: it needs `eas login` or
  `EXPO_TOKEN` and checks the project exists on EAS servers. Plain
  `expo prebuild` + Gradle needs no account. [EXPO-EASLOCAL]
- Signing gotcha: the SDK 57 template's `release` build type uses
  `signingConfig signingConfigs.debug` (the checked-in `debug.keystore`,
  password `android`) with the comment "In production, you need to generate
  your own keystore file". [EXPO-TPL `app/build.gradle`] **[inference]** Because
  `android/` is regenerated by `prebuild --clean`, a real release signing config
  must be injected by a config plugin (`withAppBuildGradle`) or applied after
  prebuild in the build script — or `android/` gets committed (leaving CNG).

### How much Expo saves once the SDK is installed

**[inference]** from the above:
- Toolchain: Expo needs **everything native needs, plus** Node, the NDK
  (~1 GB+ download) and CMake, and a C++ compile of RN on first build. It saves
  no install.
- Native Kotlin: the VPN service, consent flow, stats polling, Keystore and
  foreground notification are Kotlin in both routes (a local Expo module).
  Expo adds the module surface (`AsyncFunction`, `Events`/`sendEvent`,
  `OnActivityResult` for consent) and a config plugin for the manifest. [EXPO-MOD]
- UI: Expo replaces ~4 Compose screens with ~4 React screens; JS edits get
  Fast Refresh against an installed dev build. Any native change needs a local
  rebuild (`npx expo run:android`) — no EAS quota now, just local Gradle time.
- HTTP + storage could move to JS (`fetch`, `expo-secure-store`), but the
  foreground service needs the token and Session keys while JS is not running
  (prior research: Session outlives the process), so they stay native.

### Expo in GitHub Actions without EAS

- Nothing in Expo's docs ties `prebuild` + Gradle to EAS. [EXPO-CNG; EXPO-FAQ]
- `ubuntu-24.04` runner ships: Android cmdline-tools 12.0, build-tools 34–37,
  platforms up to android-37.x, platform-tools 37.0.1, CMake 3.31.5 / 4.1.2,
  NDK **27.3.13750724** (default), 28.2, 29.0; JDK 8/11/**17 (default)**/21/25;
  Node 22; Gradle 9.8.0. `ANDROID_HOME=/usr/local/lib/android/sdk`. [GHA]
- **[inference]** RN 0.86 pins NDK 27.1.12297006, which the image does **not**
  have, so each CI build downloads it (~1 GB) unless cached or the app
  overrides `ndkVersion` to 27.3. `ubuntu-latest` moves to 26.04 in Nov 2026
  (announcement on the readme), so pin `ubuntu-24.04`. [GHA]

## 5. CI and release (sideloaded APK)

Both options sign the same way; only the pre-Gradle steps differ.

**[inference]** Workflow shape (from the cited docs):

```text
native:  checkout → setup-java 17 → decode keystore secret → ./gradlew assembleRelease
expo:    checkout → setup-java 17 → setup-node 22 → npm ci
         → npx expo prebuild --platform android --clean
         → (inject signingConfig) → decode keystore → (cd android && ./gradlew assembleRelease)
both:    apksigner verify → upload artifact / GitHub Release
```

- Signing config without secrets in the repo: `keystore.properties` loaded by
  Gradle, not committed. [AND-SIGN] In CI, store the `.jks` as a Base64
  secret (`base64 -i key.jks` on macOS) and `base64 --decode` it in the job;
  passwords as separate secrets. Secret size limit **48 KB** (a keystore is a
  few KB). [GH-SECRETS]
- Where the key lives: one copy in GitHub secrets, one offline backup.
  Outside Play App Signing, "If you lose or misplace your key, you will not be
  able to publish updates"; a differently-signed update needs a different
  package name. [AND-SIGN] For a sideloaded app that means uninstall/reinstall.
- No `adb` in CI for sideload; the phone installs the APK from the release URL
  or via `adb install` locally.
- **Developer verification** (affects both options equally): from
  2026-09-30 in Brazil, Indonesia, Singapore, Thailand, then globally from
  2027, certified devices require apps to come from verified developers who
  register package name + signing key. A free **limited distribution**
  account covers up to 20 devices, no ID, no fee. **ADB installs are exempt.**
  [AND-VERIFY] **[inference]** Installing poof-android by `adb install` stays
  unaffected; installing by tapping a downloaded APK may need the limited
  account once enforcement reaches the owner's region.

## 6. Long-term upkeep

| | Native Kotlin + Compose | Expo (no EAS) |
|---|---|---|
| Moving parts | AGP, Gradle wrapper, Kotlin (built into AGP 9), Compose BOM, compileSdk/build-tools, tunnel AAR | All of the left (owned by RN/Expo templates) **plus** Expo SDK, React Native, React, Hermes, NDK pin, npm deps, Expo module API, config plugins |
| Cadence | AGP 9.0–9.4 are listed, 9.4.0 dated Sept 2026; **[inference]** that is roughly quarterly minors. AGP 10 Variant-API changes already flagged in 9.4 notes. Compose BOM is date-versioned (`2026.09.00`). No published fixed cadence was found. [AND-AGP; AND-AGPABOUT; AND-BOM] | Expo aims to follow RN's 6 releases/year, i.e. ~3 Expo SDKs/year; SDK 57 = RN 0.86, React 19.2.3; supported versions listed from SDK 54. [EXPO-VER; EXPO-UPG] |
| Upgrade tooling | AGP Upgrade Assistant (Studio) or `android skills add agp-9-upgrade`. [AND-AGPABOUT] | `npm install expo@^N`, `npx expo install --fix`, `npx expo-doctor`, delete `android/` and re-prebuild; "upgrade one SDK at a time". [EXPO-UPG] |
| Architecture churn | None comparable. | New Architecture mandatory from SDK 55 (prior research). Expo SDK 57 template already `newArchEnabled=true`. [EXPO-TPL] |
| Version skew | App chooses AGP 9.x + built-in Kotlin. | RN 0.86 still on AGP 8.12 + Kotlin 2.1.20 with the separate KGP. [RN-0.86] **[inference]** Expo apps lag the native toolchain by design and inherit RN's upgrade timing. |

**[inference]** Upkeep is not zero for native: every AGP major (9 → 10)
brings build-script migrations. But it has a strict subset of Expo's moving
parts for this app, since Expo still needs the Gradle/AGP layer underneath.

## Side-by-side summary

| Fact | Native Kotlin + Compose | Expo local / CI, no EAS |
|---|---|---|
| JDK + Android SDK | Yes (JDK 17+) | Yes (JDK 17 recommended, higher may break) |
| NDK + CMake | No | Yes (RN compiles C++; NDK 27.1 pinned) |
| Node toolchain | No | Yes |
| Android Studio required | No (cmdline-tools / Android CLI + Gradle) | No per se, but Expo's setup page assumes it |
| VPN/stats/Keystore code | Kotlin | Same Kotlin in a local module + JS bridge + config plugin |
| UI hot loop | Studio: Previews + Live Edit; editor-only: rebuild/install | Fast Refresh for JS; native edits rebuild |
| Release signing | `signingConfigs` + `keystore.properties` | Template signs release with debug key; inject via config plugin or post-prebuild step |
| GHA runner fit | SDK, JDK 17, build-tools 36 preinstalled | Same + Node 22; NDK 27.1 not preinstalled |
| Major-version churn | AGP/Gradle/Compose | Those + Expo SDK ×3/yr + RN |

## Open questions (not answered here)

- Real build/install times for a small Compose app vs an Expo prebuild on this Mac.
- How well Kotlin LSP (alpha, experimental AGP import) handles a Compose project in Zed day to day.
- Whether GoBackend's `onDestroy` path reliably fires on revoke when the service is still bound by the system.
- Whether the `tunnel` AAR needs core library desugaring at minSdk 24 (carried over).
