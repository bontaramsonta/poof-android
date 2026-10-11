# Block IPv6 in the phone tunnel instead of carrying it

The Exit is IPv4-only, so the phone tunnel carries IPv4 and blocks IPv6. The tunnel config is exactly one peer with `AllowedIPs = 0.0.0.0/0` and no IPv6 address, route or DNS server. With that shape, wireguard-android's GoBackend skips `VpnService.Builder.allowFamily`, and Android blocks the v6 family instead of letting it bypass the VPN. Connections to IPv6 addresses fail at once with "No route to host", so dual-stack apps fall back to IPv4 through the Exit with no timeout. The config looks like it simply forgot IPv6, so a later change could add it by accident. Adding any one of those IPv6 pieces, a second peer or a split route makes GoBackend allow both families, and IPv6 then leaves through the underlying network. That is a leak of the phone's real address. Decided in #17 and #18; the rule is spec §6.2.

**Considered Options**

- Route `::/0` into the tunnel without IPv6 on the Exit (rejected: same blackhole result, but it relies on the Exit dropping traffic rather than Android, and it is one edit away from the leaking shape).
- Carry IPv6 through the Exit (deferred: needs one-time VPC IPv6 setup in each region, an IPv6 address at launch, NAT66 on the Exit and `::/0` on the phone; the map records the path). It would make IPv6-only sites reachable. It would not remove the public IPv4 charge, because most destinations still need IPv4 egress.
- An IPv6-only Exit to avoid the $0.005/hr IPv4 charge (rejected: IPv4-only destinations become unreachable without a NAT gateway, which costs about 9× more).

**Consequences**

- IPv6-only destinations are unreachable while connected.
- The blocking depends on GoBackend's condition `sawDefaultRoute && peers.size == 1`. When upgrading the library past 1.0.20260102, check that condition still holds and re-run the IPv6 check in `docs/testing/e2e-checklist.md`.
- Verified 2026-10-11 on an emulator with working IPv6 egress. Without the VPN, an IPv6 probe reached the internet from the host's address. While connecting and while connected, IPv6 probes failed with "No route to host". IPv4 left from the Exit's address. `tun0` had only `10.66.0.2/32` plus a local-only (link-local) IPv6 address.
