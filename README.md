# AppWall

[![CI](https://github.com/gizawNahom/appwall/actions/workflows/ci.yml/badge.svg)](https://github.com/gizawNahom/appwall/actions/workflows/ci.yml)
![Core coverage](https://raw.githubusercontent.com/gizawNahom/appwall/badges/core-coverage.svg)
![Core mutation score](https://raw.githubusercontent.com/gizawNahom/appwall/badges/core-mutation.svg)

An open-source firewall for Android that cuts off internet access for the apps you choose. No root needed, no server, no tracking.

<p>
  <img src="screenshots/home.png" height="420" alt="Home screen: the firewall is on, with blocked and allowed apps">
  <img src="screenshots/log.png" height="420" alt="Blocked traffic log: tries per hour and per app">
  <img src="screenshots/system-app-warning.png" height="420" alt="Warning before blocking a part of Android">
</p>

## Why you can trust it

- **AppWall can't use the internet itself.** It doesn't request Android's internet permission, so it couldn't send anything anywhere even if it wanted to.
- **Your traffic never passes through it.** Apps you allow use your Wi-Fi or mobile data as usual. AppWall only sees the apps you block, and it drops everything they send.
- **No third-party code.** The app is built only on the Android framework: no ad or analytics SDKs, and no libraries at all.
- **Everything stays on your phone.** Your rules and the blocked traffic log are stored on the device and nowhere else.
- **Free software.** Licensed under the GPL v3, so anyone can read, build and check the code.

## What it does

- **Block any app with one tap**, or select several and block them at once. Undo is right there if you change your mind.
- **See what blocked apps tried to reach.** The blocked traffic log shows which domains or addresses each app tried, when, and how often.
- **Decide about new apps as they arrive.** When you install an app that can use the internet, AppWall asks whether to block it. You can also have new apps allowed or blocked automatically.
- **Warns before you break your phone.** Blocking a part of Android can quietly break other features, so AppWall asks first.
- **Keeps running.** It turns back on after a reboot or an update, and can guide you through setting up Android's Always-on VPN.
- **Shows its state clearly.** A notification shows when the firewall is on and how many apps it's blocking, and AppWall tells you if it had to stop.

## How it works

AppWall uses Android's VPN feature, but it is **not a VPN in the usual sense**.

When the firewall is on, AppWall creates a local tunnel on your phone and tells Android to send **only the blocked apps** into it. The tunnel leads nowhere: AppWall never forwards anything from it, so blocked apps get no network. Every other app is left untouched.

For the log, AppWall reads (but never forwards) what blocked apps send into the tunnel. Blocked apps are given a DNS server inside the tunnel that never answers, so their lookups land there too, and the log can show the domain names they tried to reach. You can turn logging off, and the log is kept for 7 days by default.

## Limitations

- **Only one VPN at a time.** Android allows a single active VPN app, so AppWall can't run alongside a regular VPN or another firewall such as NetGuard. AppWall tells you when that's the problem.
- **Don't turn on "Block connections without VPN"** for AppWall in Android's settings. It can cut off the internet for every app.
- **The VPN key icon may not appear** in the status bar on some phones (e.g. Samsung). Use AppWall's notification to see whether the firewall is on.
- **Android only.** iOS doesn't let an app block other apps' network access.

## Install

Requires Android 8.0 or newer.

Download the APK from the [latest release](https://github.com/gizawNahom/appwall/releases/latest), open it on your phone and allow installing from that source when Android asks.

To check the download, compare its SHA-256 with the `.sha256` file next to it on the release:

```bash
sha256sum -c AppWall-v0.1.0.apk.sha256
```

Every release is signed with the same key. Its certificate's SHA-256 fingerprint is:

```
8a81372c2da18c7bde3290d27754f6296dceb339fdd7355dbffe51c7d88dac76
```

You can check it with `apksigner verify --print-certs AppWall-v0.1.0.apk` from the Android SDK's build-tools.

### Build from source

You need JDK 17 and the Android SDK (platform 35, build-tools 35.0.0). Android Studio isn't required.

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. To install it on a phone with USB debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Contributing

AppWall is written in Kotlin and split into two modules:

- **`:core`**: the decisions and calculations, as pure functions over immutable data, with no Android code. This is where the logic lives and where it's tested.
- **`:app`**: a thin Android shell that runs the VPN service, screens, storage and notifications, and passes what it sees to `:core`. Its packages are grouped by role (`firewall`, `storage`, `apps`) and by screen (`home`, `log`, `settings`), with shared view pieces in `ui`.

Run the tests with:

```bash
./gradlew test
```

CI also checks how good the tests are, and fails if either number drops:

- **Coverage** with JaCoCo: `./gradlew :core:jacocoTestReport` (every line and branch of the core is covered, and the build fails otherwise). The `:app` shell is mostly Android glue that only runs on a phone, so its JVM coverage is low ([![App coverage](https://raw.githubusercontent.com/gizawNahom/appwall/badges/app-coverage.svg)](https://github.com/gizawNahom/appwall/actions/workflows/ci.yml)); the logic is in `:core`.
- **Mutation testing** of `:core` with PIT: `./gradlew :core:pitest` makes small changes to the code and checks that the tests catch them; every one must be caught. Report in `core/build/reports/pitest/`.

Ground rules for changes:

- **A blocked app never gets network.** This comes before every other concern.
- **No dependencies.** Plain Android framework only. Tests may use JUnit 4 and sqlite-jdbc, and JaCoCo and PIT measure them; none of these ship in the app.
- **SQL must run on SQLite 3.18**, the version on Android 8.

## License

AppWall is free software, licensed under the [GNU General Public License v3.0 or later](LICENSE).

Copyright (C) 2026 gizawNahom

The bundled [Geist and Geist Mono](https://github.com/vercel/geist-font) typefaces are licensed under the SIL Open Font License 1.1 (see [third_party/Geist-OFL.txt](third_party/Geist-OFL.txt)).
