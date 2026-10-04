# poof-android

The domain language is poof's — Session, Exit, Country, Dead-man's switch, Nuke, Proxy, Mode. See [poof's CONTEXT.md](https://github.com/bontaramsonta/poof/blob/main/CONTEXT.md). Terms specific to the Android client are added here as they resolve.

## Language

**Control plane**:
The always-on service that launches and terminates Exits on the phone's behalf, so the phone never holds cloud credentials. It is the only durable piece of poof-android; Sessions and Exits stay ephemeral.
_Avoid_: backend, server, API

## Differences from poof

- **Mode collapses.** The Android client has only system mode. "VPN" always means the whole phone.
- **One live phone Exit.** At most one phone-launched Exit exists at a time; the Control plane refuses a second launch. Exits from the Mac CLI do not count.
- **No Nuke.** The Dead-man's switch is the only backstop against an orphaned Exit.
- **A Session can outlive the app.** On the Mac the process *is* the Session. On the phone, the Session lasts until Disconnect or its Exit dies; it survives the app being killed or the phone rebooting.
