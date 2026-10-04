# Research: Android background limits vs the Dead-man's switch

Ticket: [#4](https://github.com/bontaramsonta/poof-android/issues/4) · Map: [#1](https://github.com/bontaramsonta/poof-android/issues/1)

**Question.** Will an Android `VpnService` Session keep the Exit alive while connected, and let the Dead-man's switch kill it reliably once disconnected?

This file reports facts, each with its source. It makes no decision. Claims marked **(inference)** are reasoned from the cited sources but not stated by them. Claims marked **(unverified)** need a device test.

AOSP references are to `frameworks/base` at `refs/heads/main`, read on 2026-10-04.

---

## 1. What the Exit actually checks

Source: `~/p/poof` — `internal/provision/userdata.sh.tmpl`, `internal/session/session.go`, `internal/tunnel/device.go`, `docs/adr/0003-deadmans-switch-teardown.md`.

- Threshold: `IdleShutdownMin = 5` (`session.go:21`). The script computes `threshold = 300` s.
- Check: a systemd timer runs `poof-deadman.sh` with `OnBootSec=1min`, `OnUnitActiveSec=1min`. The script reads `wg show wg0 latest-handshakes`, takes the newest timestamp, and runs `shutdown -h now` when `now - newest > 300`. Shutdown behaviour is *terminate* (`aws.go:92`).
- If no handshake has ever happened, the script uses boot time instead. A fresh Exit therefore has ~5 min after boot to receive its first handshake.
- The signal is the **handshake** timestamp, not any packet. Keepalives and data only count insofar as they cause handshakes.
- The CLI client sets `persistent_keepalive_interval=25` (`device.go:19`). Its comment says the keepalive "forces the ~2min re-handshake cadence even when idle".
- systemd `AccuracySec=` "Defaults to 1min". The timer may fire anywhere in a window up to 1 min after its nominal time. [systemd.timer(5) source](https://github.com/systemd/systemd/blob/main/man/systemd.timer.xml)
- **(inference)** So the switch fires between 300 s and roughly 420 s after the last handshake: the 300 s threshold, plus the 1 min run interval, plus up to 1 min of timer slack.

## 2. WireGuard handshake timing

Sources: [wireguard-go `device/constants.go`, `send.go`, `receive.go`, `timers.go`](https://github.com/WireGuard/wireguard-go/tree/master/device); kernel [`drivers/net/wireguard/timers.c`, `receive.c`](https://git.zx2c4.com/wireguard-linux/tree/drivers/net/wireguard); [wireguard.com/quickstart](https://www.wireguard.com/quickstart/).

- Constants: `RekeyAfterTime = 120s`, `RejectAfterTime = 180s`, `KeepaliveTimeout = 10s`, `RekeyTimeout = 5s`, `RekeyAttemptTime = 90s`.
- Rekey on send: `keepKeyFreshSending` sends a new handshake initiation only if `keypair.isInitiator && time.Since(keypair.created) > RekeyAfterTime`. It runs after every data send, and a keepalive counts as a send. The phone always initiates the first handshake, so it is the initiator.
- Rekey on receive: `keepKeyFreshReceiving` makes the initiator rekey once the key is older than `RejectAfterTime - KeepaliveTimeout - RekeyTimeout` = 165 s.
- Hard expiry: `SendStagedPackets` refuses to use a keypair older than `RejectAfterTime` (180 s) and starts a handshake instead. Either side does this, so the Exit can also initiate when it has data to send.
- Persistent keepalive: the timer is reset to the keepalive interval on every authenticated packet, sent or received (`timersAnyAuthenticatedPacketTraversal`). A keepalive is sent only after 25 s of silence.
- Server timestamp: the kernel sets `walltime_last_handshake` in `wg_timers_handshake_complete` (`timers.c:200`). On the responder (the Exit), that runs when the first data or keepalive packet arrives under the new keypair (`receive.c:345-347`), not when the initiation arrives.
- Roaming: on every authenticated data packet the kernel updates the peer endpoint (`wg_socket_set_peer_endpoint`, `receive.c:343`).
- WireGuard docs: "WireGuard tries to be as silent as possible when not being used; it is not a chatty protocol." Also: "A sensible interval that works with a wide variety of firewalls is 25 seconds." Persistent keepalive is off by default.
- **(inference)** While the phone's process actually runs, the Exit's handshake age cycles from 0 up to about 120–145 s: the rekey after 120 s, plus up to 25 s waiting for the next keepalive. That leaves roughly 155 s of margin before the 300 s threshold. Any gap longer than about 2.5 min in the phone's sending risks tripping the switch while the user still thinks they are connected.
- **(inference)** Without persistent keepalive, an idle tunnel sends nothing, so it never rekeys. The keepalive is therefore load-bearing for the Dead-man's switch, as poof's CLI already assumes.

## 3. Is a VpnService process "foreground" in Doze and App Standby?

### Doze restrictions (docs)

[Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby) lists what Doze does:

- "Suspends network access"
- "Ignores wake locks"
- "Defers standard AlarmManager alarms"
- `setAndAllowWhileIdle()` and `setExactAndAllowWhileIdle()` fire "no more than once per nine minutes, per app"
- No Wi-Fi scans, no sync adapters, no JobScheduler or WorkManager
- Maintenance windows become less frequent over time

On App Standby, the same page says an app is not idle while it "has a process currently in the foreground, either as an activity or foreground service".

### The system binds the VPN app at BOUND_FOREGROUND_SERVICE (AOSP)

- `Vpn.java` `establish()` binds the app's `VpnService` with `Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE` (`Vpn.java:~1776`).
- `Context.BIND_FOREGROUND_SERVICE` is hidden, system-only API. Its javadoc: "For only the case where the binding is coming from the system, set the process state to BOUND_FOREGROUND_SERVICE … this is saying that the process shouldn't participate in the normal power reduction modes (removing network access etc)."
- `OomAdjuster.java:2987-2988` turns that flag into `clientProcState = PROCESS_STATE_BOUND_FOREGROUND_SERVICE`.

### Network in Doze and Battery Saver (AOSP)

- `NetworkPolicyManager.isProcStateAllowedWhileIdleOrPowerSaveMode` returns true when `procState <= FOREGROUND_THRESHOLD_STATE`.
- `FOREGROUND_THRESHOLD_STATE = PROCESS_STATE_BOUND_FOREGROUND_SERVICE` (`NetworkPolicyManager.java:188`).
- **(inference)** So once `establish()` succeeds, the Doze and Battery Saver firewall does not block the VPN app's own UID, and its protected UDP socket keeps network access.

### Wake locks in Doze (AOSP)

- `PowerManagerService.setWakeLockDisabledStateLocked` disables an app's partial wake lock while idle only when `procState > PROCESS_STATE_BOUND_FOREGROUND_SERVICE` (and the app is not allowlisted).
- **(inference)** So the VPN app *may* hold a partial wake lock through Doze. The documented "ignores wake locks" rule does not apply to it.
- Low Power Standby (a separate mode, mostly on TV devices) is stricter: it disables partial wake locks above `PROCESS_STATE_BOUND_TOP`.

### Doze timing (AOSP defaults, `DeviceIdleController.java`)

- Light idle starts 4 min after the device goes inactive. Its idle windows begin at 5 min and grow to a maximum of 15 min.
- Deep idle starts after 30 min of inactivity. Its idle windows begin at 60 min and grow to a maximum of 6 h.
- OEMs and Google can override these through `device_config`.

## 4. The real risk while connected: CPU suspend, not Doze

- `PARTIAL_WAKE_LOCK`: "Ensures that the CPU is running; the screen and keyboard backlight will be allowed to go off." Without a wake lock the CPU may suspend. ([`PowerManager.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/PowerManager.java))
- wireguard-go timers, including persistent keepalive, use Go runtime timers. On linux/arm64 the Go runtime's `nanotime1` reads `CLOCK_MONOTONIC` ([`runtime/sys_linux_arm64.s`](https://github.com/golang/go/blob/master/src/runtime/sys_linux_arm64.s)).
- `CLOCK_MONOTONIC`: "This clock does not count time that the system is suspended." ([clock_gettime(2)](https://man7.org/linux/man-pages/man2/clock_gettime.2.html))
- **(inference)** A Go timer cannot wake a suspended phone, and its clock does not advance during suspend. While the phone is suspended, the tunnel sends no keepalives and starts no rekeys. Meanwhile the Exit measures handshake age in wall-clock time.
- The reference app, [wireguard-android `GoBackend.java`](https://github.com/WireGuard/wireguard-android/blob/master/tunnel/src/main/java/com/wireguard/android/backend/GoBackend.java), holds no wake lock, sets no alarms, and does not call `startForeground`. It relies on the system binding from §3.
- **(inference)** So "connected" does not guarantee "handshaking". If the phone sits screen-off with no other wake sources for more than about 2.5–5 min, the Exit can self-destruct mid-Session. Packets arriving from the Exit, or other apps' alarms and FCM traffic, wake the device in practice. How often that happens depends on the device and its network chipset. **(unverified)**
- Exact alarms cannot fill a sub-5-minute gap in Doze:
  - The `AlarmManager.setExactAndAllowWhileIdle` javadoc says that in low-power idle modes the dispatch interval "may be significantly longer, such as 15 minutes".
  - It also requires `SCHEDULE_EXACT_ALARM` (targetSdk 31+) "unless the app is exempt from battery restrictions".
- Mechanisms that *can* keep the CPU running, per the AOSP code above: a partial wake lock held by the VPN process (allowed at BOUND_FOREGROUND_SERVICE), or the battery-optimization allowlist. Both have a battery cost the docs warn about. [Wake locks overview](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock): "Creating and holding wake locks can have a dramatic impact on the device's battery life."

## 5. Wi-Fi ↔ cellular handoff

- wireguard-android calls `setUnderlyingNetworks(null)` and `protect()`s wireguard-go's v4 and v6 UDP sockets without binding them to a `Network`. Per the javadoc, `null` "signifies that the VPN uses whatever is the system's default network".
- **(inference)** After a handoff, the next packet leaves from the new network's source address.
- The Exit updates the peer endpoint on any authenticated packet (`receive.c:343`), so a single keepalive or data packet from the new address restores the path.
- If the phone hears nothing back for `KeepaliveTimeout + RekeyTimeout` (15 s), wireguard-go's `expiredNewHandshake` "clear[s] the endpoint address src address" and re-initiates the handshake.
- **(inference)** A handoff gap of seconds is far below the ~155 s margin from §2. Handoff alone should not trip the switch. A long period with no network at all (tunnel, airplane mode, dead zone) longer than about 2.5–5 min will trip it, and that happens while the app still shows "connected".

## 6. Disconnect paths: does silence always follow?

| Path | What the system does (source) | Effect on the Exit |
|---|---|---|
| User turns VPN off in Settings, or another VPN app calls `prepare()` | `Vpn.prepareInternal` tears down the interface (`agentDisconnect`, `jniReset`), then sends `LAST_CALL_TRANSACTION` to the app's binder, unbinds, and denies `protect` for the old UID. `VpnService` maps that transaction to `onRevoke()`. | `onRevoke` docs: the interface "is already deactivated … The application should close the file descriptor and shut down gracefully. The default implementation … is calling `stopSelf()`." **(inference)** If the app keeps the wireguard-go device running after `onRevoke`, its already-open protected socket may keep sending keepalives, which keeps the Exit alive. **(unverified)** wireguard-android avoids this by calling `wgTurnOff` in `onDestroy`. |
| App process dies (crash, OOM kill, force-stop) | Class doc: "The network is restored automatically when the file descriptor is closed. It also covers the cases when a VPN application is crashed or killed by the system." `Vpn`'s `interfaceRemoved` observer unbinds and cleans up. | The kernel closes the UDP socket with the process. No more packets, so the switch fires after the §1 window. |
| User taps Stop in the Task Manager (Android 13+) | "Your entire app stops." "The system doesn't send your app any callbacks." ([handle user stopping](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)) | Same as process death. **(inference)** The app gets no chance to call TerminateInstances, so the Dead-man's switch is the only teardown. |
| App swiped from Recents | `android:stopWithTask`: "If set to true, this service with be automatically stopped when the user remove a task rooted in an activity owned by the application. The default is false." (`attrs_manifest.xml`) | By default the VPN keeps running, and the Exit stays alive, which is correct. Vendor task-killers that kill on swipe are outside AOSP. **(unverified)** |
| Phone powered off, or no network | — | No packets. The switch fires. |
| Always-on VPN (out of scope per #1) | `VpnService` doc: the system may start the VPN in the background via `startService()`, and the app "must promote itself to the foreground … or the system will shut down the app". | Would relaunch Sessions on its own. Already excluded on the map. |

- After the last handshake, the switch fires 300–~420 s later (§1).
- The last handshake was 0–~145 s before the disconnect (§2).
- **(inference)** So the Exit terminates roughly 2.5–7 min after the phone falls silent. The one way to never trip it is a stale wireguard-go device that keeps sending after the app believes it disconnected.

## 7. Foreground-service type and notification (Android 14+)

- [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types): "Beginning with Android 14 (API level 34), you must declare an appropriate service type for each foreground service", together with the matching `FOREGROUND_SERVICE_<TYPE>` permission.
- The `systemExempted` type lists "VPN apps (configured using Settings > Network & Internet > VPN)" among its allowed callers. Declaring the type without meeting a criterion throws `ForegroundServiceTypeNotAllowedException`.
- Calling `startForeground` is not what keeps a VPN alive. The system binding (§3) already gives `BOUND_FOREGROUND_SERVICE`, and wireguard-android never calls `startForeground`. **(inference)** An app-owned FGS notification would be for the app's own UI, such as Country, IP, uptime and bytes. If it is used on targetSdk 34+, it needs a type, and `systemExempted` is the documented one for VPN apps.
- The `VpnService` class doc says "A system-managed notification is shown during the lifetime of a VPN connection", along with a system dialog that has a disconnect button.
- The manifest MUST declare the service with `android:permission="android.permission.BIND_VPN_SERVICE"` and an intent filter for `android.net.VpnService`. `Vpn.establish()` throws a `SecurityException` otherwise.

## 8. Answers to the ticket's two closing questions (facts only)

**Will the phone ever trip the switch while connected?**

Yes, it can:

- Doze's network and wake-lock restrictions do not apply to an established VPN app (AOSP, §3).
- But wireguard-go's keepalive does not run while the CPU is suspended (§4), and wireguard-android holds no wake lock.
- Any stretch of about 155–300 s or more with no outgoing WireGuard packet risks it. That covers suspend with no wake sources, or no network.
- How often a real phone suspends that long with a VPN up is device-dependent and needs measuring. **(unverified)**

**Will it always trip once disconnected?**

Yes, on every path where the WireGuard UDP socket closes:

- revoke followed by device teardown
- process death
- Task Manager Stop
- power-off

It trips 2.5–7 min after the last packet. The exception is an app bug that leaves the Go device running after `onRevoke` or a UI disconnect.

## Open questions for a prototype

1. On a real Android 14+ phone with screen off, how long are suspend gaps with the VPN up, measured as handshake age on the Exit?
2. After `onRevoke`, does a still-running wireguard-go device keep sending on its protected socket?
3. Does an inbound packet from the Exit (for example, server-side keepalive) wake the phone often enough to rekey?
