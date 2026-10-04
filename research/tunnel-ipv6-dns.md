# IPv6 and DNS in the tunnel library

Research for [#17](https://github.com/bontaramsonta/poof-android/issues/17) (map: [#1](https://github.com/bontaramsonta/poof-android/issues/1); feeds [#18](https://github.com/bontaramsonta/poof-android/issues/18)).
Builds on `research/android-wireguard-library.md` (branch `research/android-wireguard-library`).
Facts only. The choice is made in #18. Inferences are marked **[inference]**.

Researched 2026-10-05. Frame: Exit is IPv4-only (client `10.66.0.2/32`, server `10.66.0.1`, NAT masquerade, no IPv6). Phone config today: one peer, `AllowedIPs = 0.0.0.0/0`. Target: Android 14–16.

## Sources

| Ref | Source |
|---|---|
| WGA | wireguard-android `e7b3a3c` — https://github.com/WireGuard/wireguard-android/tree/e7b3a3c118836e112620b1302a8ba1873ad4daac |
| WGA-GoBackend | `tunnel/src/main/java/com/wireguard/android/backend/GoBackend.java` in WGA, L293–345 |
| WGA-Interface | `tunnel/src/main/java/com/wireguard/config/Interface.java` in WGA |
| AOSP-VpnService | `frameworks/base` `core/java/android/net/VpnService.java`, main @ `1cdfff5` — https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/core/java/android/net/VpnService.java |
| AOSP-Vpn | `frameworks/base` `services/core/java/com/android/server/connectivity/Vpn.java`, same commit: `makeLinkProperties()` L1479–1535, `agentConnect()` L1592–1603, `setVpnForcedLocked()` L2075 |
| AOSP-Vpn-tags | Same `makeLinkProperties` family/unreachable logic present at tags `android-14.0.0_r1`, `android-15.0.0_r1`, `android-16.0.0_r1` (grep-verified) |
| NETD-NC | `system/netd` `server/NetworkController.cpp` `getNetworkForDnsLocked()` L210–258, main @ `e11b868`; same VPN-without-DNS branch present at the 14/15/16 tags |
| NETD-RC | `system/netd` `server/RouteController.cpp` (`modifyVpnFallthroughRule` L713–736, `modifyRejectNonSecureNetworkRule` L1073–1088) and `RouteController.h` rule priorities, main @ `e11b868` |
| CONN-DnsManager | `packages/modules/Connectivity` `service/src/com/android/server/connectivity/DnsManager.java` `sendDnsConfigurationForNetwork()` L360–410, main @ `2519a78` |
| CONN-CS | Same repo, `service/src/com/android/server/ConnectivityService.java` `handlePerNetworkPrivateDnsConfig()` |
| CONN-NMU | Same repo, `staticlibs/device/com/android/net/module/util/NetworkMonitorUtils.java` `isPrivateDnsValidationRequired()` L71–83 |
| CONN-NC | Same repo, `framework/src/android/net/NetworkCapabilities.java` `DEFAULT_CAPABILITIES` L800–809 |
| RESOLV | `packages/modules/DnsResolver` @ `4d70e9e`: `getaddrinfo.cpp` (L219–228, L1431–1440), `res_send.cpp` `res_private_dns_send()` L1353–1400 |
| LIBCORE | `libcore` `ojluni/src/main/java/java/net/Inet6AddressImpl.java` L129 (`AI_ADDRCONFIG`) |
| AND-VPN | https://developer.android.com/develop/connectivity/vpn |
| WG-SITE | https://www.wireguard.com/ — "Cryptokey Routing" section |
| POOF | `~/p/poof` @ `30bebf1`: `internal/provision/userdata.sh.tmpl`, `internal/session/phonetest.go`, `internal/session/session.go`, `README.md` |

Connectivity, DnsResolver and NetworkStack are Mainline modules updated through Google Play system updates, so on-device code can be newer than the platform tag. [AOSP layout]

## 1. What GoBackend does with `VpnService.Builder`

Exact order in `GoBackend.setStateInternal` for UP [WGA-GoBackend]:

1. `setSession(name)`.
2. `addDisallowedApplication` / `addAllowedApplication` from the config's app lists.
3. `addAddress` for every `Interface` address.
4. `addDnsServer` for every `Interface` DNS address; `addSearchDomain` for every search domain.
5. `addRoute` for **every AllowedIP of every peer**. Records `sawDefaultRoute` if any prefix has mask 0.
6. Kill-switch block:
   ```java
   // "Kill-switch" semantics
   if (!(sawDefaultRoute && config.getPeers().size() == 1)) {
       builder.allowFamily(OsConstants.AF_INET);
       builder.allowFamily(OsConstants.AF_INET6);
   }
   ```
7. `setMtu(mtu or 1280)`; `setMetered(false)` on API ≥ 29; `service.setUnderlyingNetworks(null)`; `setBlocking(true)`; `establish()`.
8. After `wgTurnOn`, `protect()` both the v4 and v6 WireGuard UDP sockets.

Facts that follow from the code:

- One peer with `0.0.0.0/0` sets `sawDefaultRoute`, so **`allowFamily` is never called**. The library deliberately leaves the default family blocking on. [WGA-GoBackend]
- Any other shape — two peers, or no `/0` route (split tunnel) — calls `allowFamily` for **both** families. Then nothing is blocked by default. [WGA-GoBackend]
- `::/0` also has mask 0, so `0.0.0.0/0, ::/0` on one peer still skips `allowFamily`. [WGA-GoBackend]
- No `excludeRoute`, no `allowBypass`, no explicit underlying network. [WGA-GoBackend]
- DNS: an entry in `DNS =` that parses as an IP becomes `addDnsServer`; anything else becomes a search domain. [WGA-Interface `parseDnsServers`]
- MTU default here is 1280 when unset; poof's CLI uses 1420. [WGA-GoBackend; POOF]

## 2. How Android decides a family is blocked

- Public contract: *"By default, if no address, route or DNS server of a specific family (IPv4 or IPv6) is added to this VPN, then all outgoing traffic of that family is blocked. If any address, route or DNS server is added, that family is allowed."* [AOSP-VpnService `allowFamily` Javadoc]
- `allowFamily` *"allows an address family to be unblocked even without adding an address, route or DNS server of that family. Traffic of that family will then typically fall-through to the underlying network."* [AOSP-VpnService]
- `addAddress`, `addRoute` and `addDnsServer` Javadocs each say the call *implicitly allows* that family. [AOSP-VpnService]
- Implementation: `Vpn.makeLinkProperties()` ORs `allowIPv6` over addresses, **unicast** routes and DNS servers. If it stays false it adds `::/0` as `RTN_UNREACHABLE` to the VPN's LinkProperties (same for IPv4 with `0.0.0.0/0`). `excludeRoute` creates `RTN_THROW` routes, which do not count. [AOSP-Vpn L1479–1535; AOSP-VpnService L696–697]
- Identical logic at the Android 14, 15 and 16 release tags. [AOSP-Vpn-tags]

### Applied to poof's current config (address `10.66.0.2/32`, one peer `0.0.0.0/0`, DNS v4 or none)

- IPv6 is not allowed by address, route or DNS → the VPN network gets an unreachable `::/0`. [AOSP-Vpn]
- So IPv6 from apps under the VPN does **not** fall through to Wi-Fi/cellular; it hits the unreachable route. **[inference]** An unreachable route makes `connect()`/`sendto()` fail fast with `ENETUNREACH`, so dual-stack clients fall back to IPv4 without a timeout.
- **Caveat — DNS family:** adding an IPv6 DNS server (e.g. `2606:4700:4700::1111`) flips `allowIPv6` to true with no v6 route. The unreachable route is then not installed, and v6 traffic falls through (§3). [AOSP-Vpn L1514–1521; AOSP-VpnService]

### Resolver side (no AAAA when v6 is blocked)

- `getaddrinfo` with `AI_ADDRCONFIG` only queries AAAA if `have_global_ipv6_connectivity()` finds a source address for `2000::` under the app's mark. [RESOLV getaddrinfo.cpp L219–228, L1431–1440]
- Java `InetAddress.getAllByName` sets `AI_ADDRCONFIG`. [LIBCORE L129]
- **[inference]** With the unreachable `::/0` that probe fails, so Java and `AI_ADDRCONFIG` lookups return IPv4 only. Apps that ask for `AF_INET6` explicitly or run their own resolver still get AAAA, then hit `ENETUNREACH`.

## 3. Where uncovered IPv6 goes when the family *is* allowed

- netd installs a per-VPN fallthrough rule: *"If a packet with a VPN's netId doesn't find a route in the VPN's routing table, it's allowed to go over the default network."* Priority 28000. [NETD-RC L713–736; RouteController.h]
- So with IPv6 allowed but no v6 route covering the destination, v6 leaves on the underlying network, outside the tunnel, with the phone's real v6 address. This is the "allowFamily … fall-through" the Javadoc describes. [NETD-RC; AOSP-VpnService]
- In GoBackend this happens for any config that is not "one peer + a /0 route" (§1), or when an IPv6 DNS server is pushed without a v6 route (§2). [WGA-GoBackend; AOSP-Vpn]

## 4. Adding `::/0` to AllowedIPs with an IPv4-only Exit

What the code does:

- GoBackend calls `addRoute(::, 0)` → IPv6 allowed, `::/0` into the TUN. `allowFamily` still skipped (one peer, `/0`). [WGA-GoBackend]
- wireguard-go on the phone encrypts v6 packets to the single peer (AllowedIPs covers them). [WG-SITE client semantics; WGA UAPI `allowed_ip`]
- The Exit's `wg0.conf` has `AllowedIPs = {{.ClientTunnelIP}}/32` (= `10.66.0.2/32`) for the phone. [POOF userdata.sh.tmpl]
- WireGuard drops a decrypted packet whose source IP is not in the sending peer's AllowedIPs. [WG-SITE "Cryptokey Routing"]
- The Exit has no v6 address, no `net.ipv6.conf.*.forwarding`, and its NAT table is `ip` family (IPv4 only). [POOF userdata.sh.tmpl]

Result:

- No IPv6 leaves the phone outside the tunnel. v6 is dropped at the Exit's WireGuard layer. [above facts]
- **[inference]** It is a *silent* drop: no ICMPv6 back to the phone. Apps that try v6 wait for a timeout. Happy Eyeballs clients recover in ~250–300 ms; non-HE clients can stall for the full connect timeout.
- **[inference]** Without a v6 address on the TUN, whether apps even try v6 depends on source-address selection. If Wi-Fi/cellular holds a global v6, the kernel may pick it as source, `AI_ADDRCONFIG` then sees v6 "connectivity", AAAA is queried, and packets enter the tunnel carrying the phone's real v6 address (encrypted, then dropped at the Exit). If the underlying network is v4-only, the probe fails and AAAA is skipped. Needs an on-device check.
- **[inference]** Adding a ULA v6 address (e.g. `fd00::2/128`) with `::/0` makes v6 look fully available. Every v6 attempt then black-holes silently at the Exit — the slowest failure mode for clients.
- Compare to today's config (§2): unreachable `::/0` on the phone, fast `ENETUNREACH`, no AAAA via `AI_ADDRCONFIG`. Both keep v6 inside; they differ in *how* v6 fails. [AOSP-Vpn; inference on timing]

## 5. DNS through the VPN

### Which network and servers a query uses

- If the VPN pushes DNS servers, queries use them on the VPN's netId. [NETD-NC L244–251]
- **If the VPN has no DNS servers, netd uses the default (underlying) network's DNS servers, through the default network** (comments cite b/29498052, b/27560555). That is a DNS leak around the tunnel. [NETD-NC L236–242, L248–255]
- `addDnsServer` Javadoc agrees: *"If none is set, the DNS servers of the default network will be used."* [AOSP-VpnService]
- DNS queries carry `protectedFromVpn` plus the chosen netId. They therefore follow the chosen network's table, not the VPN's UID rule. A VPN DNS server is reached through the VPN only if the VPN's routes cover it; otherwise, per the netd comment, *"possibly falling through to the default network if the VPN doesn't provide a route to them"*. [NETD-NC L211–213, L231–233, L244–246]
- With `0.0.0.0/0` routed, a v4 DNS server such as `1.1.1.1` goes through the tunnel. A v6 DNS server with no v6 route falls through to the underlying network in cleartext (§3). **[inference from NETD-NC + NETD-RC]**
- poof's CLI resolves through the tunnel at `1.1.1.1`. The phone-test wg-quick config used `DNS = 1.1.1.1` with `AllowedIPs = 0.0.0.0/0`. [POOF README.md L26, session.go L125, phonetest.go L71]

### Private DNS (Settings → Network → Private DNS)

- Private DNS applies to every network where `isPrivateDnsValidationRequired` is true: `INTERNET` and (`NOT_RESTRICTED` and `TRUSTED`, or VCN-managed, or OEM-paid). [CONN-NMU L71–83; CONN-CS `handlePerNetworkPrivateDnsConfig`]
- A `VpnService` network always gets `INTERNET`, and inherits `NOT_RESTRICTED` + `TRUSTED` from the default capabilities. So **Private DNS applies to the VPN network**. [AOSP-Vpn `agentConnect` L1592–1603; CONN-NC L800–809]
- **Off:** plain DNS to the VPN's servers (port 53). [CONN-DnsManager]
- **Automatic (opportunistic):** DoT targets are the VPN's own DNS servers (`tlsServers = servers`). If a server validates on port 853, queries use DoT. Otherwise they fall back to cleartext to the same servers. [CONN-DnsManager L386–390; RESOLV res_send.cpp `OPPORTUNISTIC` → `fallback = true`]
- **Strict (hostname):** DoT targets are the hostname's resolved IPs, filtered by `LinkProperties.isReachable` on the VPN's LinkProperties. The VPN's pushed DNS servers are not used for app queries. **No cleartext fallback.** Queries block up to ~4.2 s (42 × 100 ms) waiting for a validated server, then fail. [CONN-DnsManager L386–389, L495–500; RESOLV res_send.cpp `STRICT` → `fallback = false`, L1375–1400]
- DoH via DDR is also wired in when the `ddrEnabled` flag is on. [CONN-DnsManager `makeDohParamsParcel`; RESOLV `res_doh_send`]
- **[inference]** Strict and opportunistic DoT traffic is sent on the VPN netId. To a v4 provider IP it goes through the tunnel and leaves at the Exit. To a v6 provider IP with v6 blocked it is unreachable. So Private DNS does not leak *around* the tunnel. It does change *who* answers: in strict mode, the user's provider (e.g. `dns.google`), not poof's `1.1.1.1`.
- **[inference]** In strict mode the provider hostname must itself resolve over the VPN network. If that fails, all DNS on the phone fails while connected.

## 6. "Block connections without VPN" (reference only; always-on is out of scope on the map)

- It is the always-on VPN lockdown option. *"The system blocks any network traffic that doesn't use the VPN."* [AND-VPN "Blocked connections"]
- Lockdown blocks all UIDs except the VPN app and the lockdown allowlist. [AOSP-Vpn `setVpnForcedLocked`]
- netd enforces it with a `PROHIBIT` rule at priority 14000 for packets not marked `protectedFromVpn`. [NETD-RC L1073–1088; RouteController.h]
- **[inference]** 14000 sits before the VPN fallthrough rule (28000), so under lockdown uncovered destinations are prohibited instead of falling through. That is the system-level version of GoBackend's single-peer "kill-switch". poof v1 gets only GoBackend's per-family blocking, and only while the tunnel is up.
- VPNs targeting API ≥ 29 can check `isLockdownEnabled()`. [AOSP-VpnService]

## Summary table

| Config | v6 family on VPN | Uncovered v6 | AAAA via `AI_ADDRCONFIG` | DNS path |
|---|---|---|---|---|
| Today: `0.0.0.0/0`, one peer, DNS `1.1.1.1` | Blocked (unreachable `::/0`) | Fails fast `ENETUNREACH` | Not queried [inference] | `1.1.1.1` via tunnel |
| Same, **no DNS** | Blocked | Fails fast | Not queried [inference] | **Underlying network's DNS, outside tunnel** |
| Same, DNS includes a **v6** server | **Allowed** | **Falls through to underlying network** | Queried if underlying has v6 | v6 DNS **outside tunnel** |
| `0.0.0.0/0, ::/0`, one peer, no v6 address | Allowed, routed to TUN | Enters tunnel, dropped at Exit (silent) | Depends on underlying v6 [inference] | Per pushed DNS |
| Two peers or split routes | Allowed by GoBackend `allowFamily` | Falls through | Per underlying | Per pushed DNS |

## Open questions (not answered here)

- On-device check, Android 14–16: with `::/0` routed and no v6 TUN address on a v6-capable Wi-Fi, which source address do v6 packets carry, and are AAAA records queried?
- Whether `LinkProperties.isReachable` treats an `RTN_UNREACHABLE ::/0` as reachable when filtering strict-mode DoT IPs.
- Leak window during GoBackend reconfig or tunnel restart (fd closed then re-established). Not examined; always-on lockdown would cover it but is out of scope.
- The app's own control-plane HTTPS goes through the tunnel once it is up (GoBackend excludes no apps by default). Relevant to Disconnect ordering, not to this ticket.
