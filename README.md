# DeskPad

DeskPad turns your Android phone into a touchpad and keyboard for controlling Android external displays and desktop mode.

It is intended for non-Samsung Android users, Pixel users, XR glasses users, external monitor users, and anyone who wants a DeX-like external-display control setup without Samsung DeX. Compatibility depends on your device, Android build, display output path, and desktop or extended-display support.

## Features

- Cursor movement from the phone touchscreen
- Tap, double tap, and long-press right click
- Two-finger scrolling
- Keyboard input for focused fields on the external display
- Editing and navigation keys for common desktop-style workflows
- Shizuku-based virtual mouse and keyboard devices through `/dev/uinput`
- Accessibility-based navigation actions such as Back, Home, and Recents

## Requirements

| Requirement | Details |
|---|---|
| Android | Android 14+ (`minSdk 34`) |
| Architecture | `arm64-v8a` |
| Shizuku | Running through Wireless Debugging or root |
| Accessibility Service | DeskPad Controller must be enabled for navigation actions and display-targeted gestures |
| Display environment | A device and Android build that can use an external display or desktop/extended-display mode |

## Setup

1. Install and start [Shizuku](https://shizuku.rikka.app/). Wireless Debugging is enough on supported devices.
2. Build and install DeskPad with `./gradlew installDebug`, or install a release APK.
3. Open DeskPad and grant Shizuku permission when prompted.
4. Enable **DeskPad Controller** in Android Accessibility settings.
5. Connect your external display, XR glasses, or monitor and switch the device to an extended-display or desktop-mode environment when available.

## Basic gestures

- Move one finger on the touchpad area to move the cursor.
- Tap once for left click.
- Double tap for double click.
- Long press for right click.
- Drag with two fingers to scroll.
- Use the on-screen keyboard and editing keys for focused text fields.
- Use navigation controls for Back, Home, and Recents when Accessibility Service support is enabled.

## Privacy and permissions

DeskPad uses Shizuku to create virtual mouse and keyboard devices through `/dev/uinput`. It also uses Android Accessibility Service capabilities for navigation actions, display-targeted gestures, and detecting editable focus on external displays.

The Accessibility Service is configured with `canRetrieveWindowContent=true` because Android requires window-content access for reliable focus detection. DeskPad does not use Accessibility for screen reading, content collection, analytics, advertising, or profiling.

DeskPad does not upload, transmit, sell, or externally store accessibility data. The app currently declares no `INTERNET` or network-state permissions, and it does not include analytics.

## Known limitations

- Android 14+ is required.
- Only `arm64-v8a` devices are supported.
- Shizuku is required.
- Accessibility Service is required for navigation and focus support.
- External display behavior depends on the device, OEM changes, Android build, and display-output path.
- Not all apps accept injected input equally.
- Long-form typing is usually better with a physical keyboard.
- DeskPad is an advanced-user beta, not a polished Play Store release.

## Release APK / installation

Debug APKs can be built locally with:

```sh
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions also builds a debug APK on push, pull request, and manual workflow runs, then uploads it as the `DeskPad-debug-apk` artifact.

When installing outside the Play Store, Android may require you to allow installation from the browser, file manager, or GitHub client used to open the APK. Keep Shizuku running before launching DeskPad.

## Troubleshooting

- If DeskPad cannot connect, confirm that Shizuku is running and that DeskPad has Shizuku permission.
- If navigation buttons or focus detection do not work, confirm that **DeskPad Controller** is enabled in Android Accessibility settings.
- If input appears on the phone instead of the external display, reconnect the display and confirm that your device is in a desktop or extended-display mode.
- If a specific app ignores clicks, typing, or gestures, try another app to determine whether the limitation is app-specific.
- If Android revokes permissions after reinstalling, open DeskPad again and re-grant Shizuku and Accessibility access.

## Architecture

DeskPad keeps the original AR Touchpad architecture:

```text
app/
├── src/main/
│   ├── aidl/          IMouseService.aidl - Binder interface to the Shizuku service
│   ├── cpp/           uinput_jni.cpp - JNI bridge for /dev/uinput ioctl access
│   └── java/
│       └── com/tohyas/deskpad/
│           ├── MouseService.kt - Shizuku UserService running as shell uid
│           ├── ShizukuMouseController.kt - Binds and unbinds the Shizuku service
│           ├── TouchpadViewModel.kt - State, display detection, and gesture dispatch
│           ├── UinputNative.kt - Kotlin wrapper for the JNI library
│           ├── TouchpadAccessibilityService.kt - Global navigation and gesture support
│           └── ui/TouchpadScreen.kt - Compose touchpad and keyboard UI
```

The Shizuku UserService creates `/dev/uinput` virtual mouse and keyboard devices. Android then sees them as hardware-style input devices, which allows cursor and keyboard events to target the external-display environment. The Accessibility Service handles navigation actions and gesture dispatch where Android requires accessibility privileges.

The native CMake build compiles `libdeskpad.so` for `arm64-v8a`.

## Attribution

DeskPad is based on AR Touchpad by Paul Gratz. The original project is licensed under Apache License 2.0. This fork adds rebranding, cleanup, and distribution-oriented refinements.

## License

Copyright 2026 Paul Gratz

Modifications Copyright 2026 Tohya Sugano

Licensed under the [Apache License, Version 2.0](LICENSE).
