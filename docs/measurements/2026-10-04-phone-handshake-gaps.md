# Phone handshake gaps vs the Dead-man's switch (2026-10-04)

Read this if an Exit dies while the phone still thinks it is connected.

## Question

Does a connected Android phone go quiet long enough to trip the Exit's Dead-man's switch? The switch fires once the newest handshake is more than 300 s old. It is checked every minute, so it fires 300–420 s after the last handshake.

## Setup

| Parameter | Value |
|---|---|
| Client | Official WireGuard Android app (`com.wireguard.android`): the same tunnel library poof-android uses |
| Phone | Owner's phone (model and Android version not recorded) |
| Client config | Address `10.66.0.2/32`, DNS `1.1.1.1`, AllowedIPs `0.0.0.0/0`, **PersistentKeepalive 25 s** (poof's own client value) |
| Exit | `t4g.nano`, Amazon Linux 2023, Japan (`ap-northeast-1`), instance `i-0413f755a14cbcf04` |
| Exit mode | poof phone-test mode: Dead-man's switch replaced by a 120 min hard stop; handshake age written to the serial console every 30 s |
| Sampling | The Mac polls the console every ~31 s. Gaps below are derived from when the age reset, since sampled ages understate by up to ~30 s |
| Network | Home Wi-Fi; cellular during Run 3 |

Tooling: poof branch [`phone-test-mode`](https://github.com/bontaramsonta/poof/tree/phone-test-mode) (1464bd6, plus the swap fix 30bebf1).

## Results

| Run | Conditions | Duration | Gaps between handshakes | Worst |
|---|---|---|---|---|
| 1 | Battery, screen locked, untouched | ~10 min (23:06–23:17 IST) | ~130, ~130, ~122, **~198 s** | **~198 s** |
| 2 | Charger, screen locked | ~7 min (23:17–23:24 IST) | ~121, ~126, ~123 s | ~126 s |
| 3 | Screen on, Wi-Fi off → cellular → Wi-Fi on | ~3 min (23:24–23:27 IST) | ~123, ~120 s | ~123 s |

- **Normal connected cadence is ~120–126 s.** WireGuard rekeys after 120 s, and the next keepalive carries the handshake.
- **On battery, a sleeping phone came in late:** one gap was ~198 s, ~75 s over normal. This is the CPU deep-sleep effect from the [background-limits research](https://github.com/bontaramsonta/poof-android/issues/4). On the charger it disappeared.
- **A Wi-Fi ↔ cellular switch added no visible gap.**
- **No gap came near 300 s.** Worst observed headroom: ~100 s before the threshold, ~220 s before the latest firing.

Raw log: [poof-android#12](https://github.com/bontaramsonta/poof-android/issues/12).

## Decision

The owner kept the existing threshold (5 min of handshake silence) after seeing these numbers.

## Limits: suspect these first if the switch trips

- **Short runs.** Battery-only was observed for ~10 min. Deep sleep deepens over time (Doze maintenance windows grow), so a phone locked for an hour or more may produce longer gaps than ~198 s.
- **One phone, one network.** OEM battery managers (Samsung, Xiaomi, …) can be more aggressive.
- **Battery Saver** was not tested.
- **The WireGuard app, not poof-android.** Our app's foreground service and stats polling may keep the CPU awake differently.

## Reproduce

```
cd ~/p/poof && git checkout phone-test-mode
POOF_PHONE_TEST=1 go run ./cmd/poof up japan   # needs `brew install qrencode` for the QR
```

1. Scan the QR in the WireGuard app and switch the tunnel on once the rows start.
2. Run each condition; the age resets show each handshake.
3. Ctrl-C terminates the Exit. This branch still creates a security group per launch; clean up with `go run ./cmd/poof nuke`.
