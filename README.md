# USB Camera Viewer

4-camera vehicle surround view application for Android head units.

## Features

- **Fixed 4-camera layout** (2x2 grid)
- Supports up to 2 USB cameras + 2 built-in cameras (front/rear)
- Blue backgrounds for unavailable cameras
- Fullscreen display (no title bar or navigation)
- Automatic camera detection and assignment
- Landscape orientation optimized for car head units

## Camera Layout

```
┌─────────────┬─────────────┐
│ Front Cam   │ USB Cam 1   │
│ (Built-in)  │ (External)  │
├─────────────┼─────────────┤
│ Rear Cam    │ USB Cam 2   │
│ (Built-in)  │ (External)  │
└─────────────┴─────────────┘
```

## Hardware Requirements

- Android 5.0+ (API 21+)
- MediaTek-based head unit with Camera HAL support
- USB 2.0 ports for external cameras
- UVC (USB Video Class) compatible cameras

## Tested On

- AC8227L / MT8127 based Android 8.1 head units
- Logitech 1080P Pro Stream webcam (046d:0894)

## Installation

1. Download the APK from releases
2. Install via ADB: `adb install app-debug.apk`
3. Or copy to device and install manually

## Building

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Known Limitations

- Some devices cannot open all 4 cameras simultaneously (ERROR_MAX_CAMERAS_IN_USE)
- Cameras that fail to open will show blue background
- MediaTek HAL bug: USB cameras may report as "BACK" facing instead of "EXTERNAL"
  - Workaround: Camera IDs >= 2 are treated as USB cameras

## License

MIT License - See LICENSE file for details
