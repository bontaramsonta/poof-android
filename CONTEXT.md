# poof-android

The domain language is poof's — Session, Exit, Country, Dead-man's switch, Nuke, Proxy, Mode, Owner. See [poof's CONTEXT.md](https://github.com/bontaramsonta/poof/blob/main/CONTEXT.md). Terms specific to the Android client are added here as they resolve.

## Language

**Control plane**:
The always-on service that launches and terminates Exits on the phone's behalf, so the phone never holds cloud credentials. It is the only durable piece of poof-android; Sessions and Exits stay ephemeral.
_Avoid_: backend, server, API

## Differences from poof

- **Mode collapses.** The Android client has only system mode. "VPN" always means the whole phone.
- **One live phone Exit per Owner.** At most one phone-launched Exit per Owner exists at a time; the Control plane refuses that Owner a second launch. Other Owners' phone Exits and Exits from the Mac CLI do not count.
- **The token is the Owner.** On the phone, the Owner is never configured: the Control plane names it from the bearer token. The app has no Owner concept.
- **No Nuke.** The Dead-man's switch is the only backstop against an orphaned Exit.
- **A Session can outlive the app.** On the Mac the process *is* the Session. On the phone, a Session is the life of its stored Session record: it begins when the Control plane confirms the launch and ends when the record is deleted (Disconnect, revoke, failed launch, or finding the Exit gone). The tunnel is a state inside it.
