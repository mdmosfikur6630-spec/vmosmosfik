# VmosMosfik - Virtual Device Controller

Multi-device ADB controller with screen mirroring and sync mode.

## Features

- Connect to multiple virtual devices via SSH tunnel relay
- Live screen capture from each device
- Touch/tap control with coordinate mapping
- Hardware key events (HOME, BACK, RECENT, VOLUME, POWER)
- **Sync Mode**: Mirror all actions from master device to selected slave devices
- APK installation on multiple devices simultaneously

## How to Use

1. Open the app and tap **+** to add a device
2. Enter SSH connection details (host, port, username, key)
3. The device screen appears — tap directly to interact
4. Add more devices with the same flow
5. Toggle **Sync** (top-right icon) to enable mirroring
6. Tap ⭐ on a device to set it as master
7. All taps and key events on the master are mirrored to selected devices

## Building

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`
