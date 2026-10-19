# AppWall

A simple, open-source firewall for Android that lets you cut off internet access for individual apps. It needs **no root**.

## Features

- Block or allow internet access per app with a single tap
- Search through all installed apps that use the internet
- **Block an app** picker: tick several apps and block them in one go, filtered by Apps, Recent (installed in the last week) or System
- **The Wall**: every blocked app is shown as an icon at the top of the screen, with a badge counting today's blocked attempts; tap one to unblock it, long-press to see its log
- Background system services are hidden by default to keep the list short ("Show system apps" reveals them)
- Master on/off switch
- **Blocked traffic log**: grouped by app, showing which domains or addresses each blocked app tried to reach, when, and how often (kept on the device for 7 days)
- **New app alerts**: when an app that uses the internet is installed, AppWall asks whether to block or allow it (or, if you prefer, allows or blocks new apps automatically). Only while the firewall is on.
- **Starts by itself** after a reboot or an AppWall update, if it was on before; optional guided setup for Android's Always-on VPN
- **Settings**: start after reboot, new-app behavior, logging on/off and how long to keep the log
- An ongoing notification shows when the firewall is on and how many apps it is blocking
- Rules are stored on the device; nothing is sent anywhere
- Follows your phone's light or dark setting

## How it works

AppWall uses Android's VPN API, but it is **not a VPN in the usual sense**: no traffic leaves your phone, and there is no server.

When the firewall is on, AppWall creates a local tunnel and tells Android to route **only the apps you blocked** into it. AppWall never reads or forwards anything from that tunnel, so those apps' traffic goes nowhere and they get no network. Every other app keeps using your normal Wi-Fi or mobile data, untouched and unaffected by AppWall.

For the blocked traffic log, AppWall reads (but never forwards) what blocked apps send into the tunnel. Blocked apps are given a DNS server inside the tunnel that never answers, so their lookups land there too and the log can show the domain names they tried to reach.

## Limitations

- **Android only.** iOS doesn't allow an app to block other apps' network access.
- **Only one VPN at a time.** Android allows a single active VPN (and a single Always-on VPN), so AppWall can't run alongside a regular VPN app or another firewall such as NetGuard. AppWall tells you when that's the problem.
- **Don't enable "Block connections without VPN"** for AppWall in system settings. It may cut off internet for all apps.
- **The VPN key icon may not appear** in the status bar on some phones (e.g. Samsung). Use AppWall's own notification to see whether the firewall is on.

## Requirements

- Android 8.0 (API 26) or newer

## Building from source

You need JDK 17 and the Android SDK (platform 35, build-tools 35.0.0). Android Studio isn't required.

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

To install it on a phone connected with USB debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Project layout

| File | Purpose |
|---|---|
| `FirewallVpnService.kt` | Routes blocked apps into a dead-end tunnel; shows the status notification |
| `MainActivity.kt` | Main screen: master switch, search, app list |
| `AppListAdapter.kt` | One row per app with its block toggle |
| `InstalledApps.kt` | Finds installed apps that request internet access |
| `Rules.kt` | Saves the on/off state and the list of blocked apps |
| `TunnelReader.kt` | Reads blocked apps' packets from the tunnel and logs them |
| `PacketParser.kt` | Extracts addresses, ports and DNS names from raw IP packets |
| `BlockLog.kt` | On-device database of blocked connection attempts |
| `LogActivity.kt` | The blocked traffic screen |
| `SettingsActivity.kt` | The settings screen |
| `PickerActivity.kt` | The "Block an app" picker |
| `Charts.kt` | Sparkline and meter views used on the home screen |
| `NewAppNotifier.kt` | Asks about, allows or blocks newly installed apps |
| `StartupReceiver.kt` | Turns the firewall back on after a reboot or an app update |

All source files are in `app/src/main/java/dev/appwall/`.

## License

AppWall is free software, licensed under the [GNU General Public License v3.0 or later](LICENSE).

Copyright (C) 2026 gizawNahom

The bundled [Geist and Geist Mono](https://github.com/vercel/geist-font) typefaces are licensed under the SIL Open Font License 1.1 (see [third_party/Geist-OFL.txt](third_party/Geist-OFL.txt)).
