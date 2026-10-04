# poof-android

An Android client for [poof](https://github.com/bontaramsonta/poof): pick a country, a fresh WireGuard Exit appears there, your phone's traffic comes out of it, and on disconnect it's gone.

Status: **built, in phone testing**. The control plane is deployed in `ap-south-1`; the app connects and disconnects on a real phone. The full [e2e checklist](docs/testing/e2e-checklist.md) is not yet run. The spec is [docs/spec.md](docs/spec.md), charted on the [wayfinder map](https://github.com/bontaramsonta/poof-android/issues/1).

- `control-plane/`: Go Lambda, SAM template, `token.sh`. Deploy with `sam build && sam deploy`; create the token first with `./token.sh`.
- `android/`: the app. Set `poof.controlPlaneUrl` in `android/local.properties` to the stack's `FunctionUrl` output, then `./gradlew assembleRelease`.
