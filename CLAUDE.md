# poof-android

Planning lives in the wayfinder map: https://github.com/bontaramsonta/poof-android/issues/1. Domain terms: `CONTEXT.md`.

## Android app (`android/`)

Native Kotlin + Jetpack Compose. minSdk 26, targetSdk 36, application ID `dev.bontaramsonta.poof`.

Build from the terminal with Android Studio's bundled JDK:

```
cd android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

## Android CLI

Google's `android` CLI is installed (Homebrew, `android/tap`). Use it for SDK, devices and emulators, and docs lookup; see the `android-cli` skill.

`android studio …` commands query the running Android Studio. They need Studio open on `android/`. Run `android studio check` first. Prefer `analyze-file`, `find-usages`, `find-declaration` and `render-compose-preview` over guessing.
