# Dual CVBS Camera Implementation - Left/Right Blind Spot Monitoring

**Date:** July 1, 2026
**Changes:** Modified for 2-camera CVBS inputs + periodic reverse camera display

---

## Layout Overview

### Screen Split
```
┌─────────────────────────────────┬─────────────────────────────────┐
│         LEFT HALF               │         RIGHT HALF              │
│                                 │                                 │
│  ┌──────┐  ┌────┐  ┌──────┐   │      ┌──────────────────┐      │
│  │ Left │  │CAR │  │Right │   │      │                  │      │
│  │CVBS1 │  │IMG │  │CVBS2 │   │      │  Reverse Camera  │      │
│  │Trap  │  │    │  │Trap  │   │      │  (Lower Priority)│      │
│  └──────┘  └────┘  └──────┘   │      │                  │      │
│                                 │      └──────────────────┘      │
│  30 FPS - HIGH PRIORITY         │      10 FPS - LOW PRIORITY     │
└─────────────────────────────────┴─────────────────────────────────┘
```

### Camera Assignments
- **Position 0 (Front):** ❌ REMOVED (not used)
- **Position 1 (Left):** CVBS 1 input - Left blind spot camera (trapezoid warping)
- **Position 2 (Reverse):** Reverse camera input - Right half display (flat rectangle)
- **Position 3 (Right):** CVBS 2 input - Right blind spot camera (trapezoid warping)

---

## Key Changes Made

### 1. SurroundViewActivity.kt - Camera Assignment

**Before:**
```kotlin
cameraAssignments[0] = frontCamera
cameraAssignments[1] = usbCameras.getOrNull(0)
cameraAssignments[2] = backCamera
cameraAssignments[3] = usbCameras.getOrNull(1)
```

**After:**
```kotlin
cameraAssignments[0] = null  // Front removed
cameraAssignments[1] = cvbs1CameraId   // CVBS 1 - Left
cameraAssignments[2] = reverseCameraId // Reverse camera
cameraAssignments[3] = cvbs2CameraId   // CVBS 2 - Right
```

**TODO:** Update camera IDs after testing on device. Check logcat for "All detected cameras" to find correct CVBS input IDs.

### 2. SurroundViewRenderer.kt - Rendering Changes

#### Removed Components
- ❌ `frontQuad` - Front camera trapezoid removed
- ❌ `rearQuad` - Rear camera trapezoid removed (replaced with reverse cam)

#### Added Components
- ✅ `reverseRect` - Simple flat rectangle for reverse camera display
- ✅ `frameCount` - Frame counter for priority-based rendering
- ✅ Split viewport rendering (left half / right half)

#### Frame Priority Implementation
```kotlin
// High priority: Left and Right (every frame = 30 FPS)
surfaceTextures[1]?.updateTexImage()  // Left CVBS 1
surfaceTextures[3]?.updateTexImage()  // Right CVBS 2

// Low priority: Reverse (every 3rd frame = ~10 FPS)
if (frameCount % 3 == 0) {
    surfaceTextures[2]?.updateTexImage()  // Reverse camera
}
```

#### Viewport Split
```kotlin
// LEFT HALF: Trapezoids + Vehicle
glViewport(0, 0, width / 2, height)
drawLeftTrapezoid()
drawRightTrapezoid()
drawVehicle()

// RIGHT HALF: Reverse Camera
glViewport(width / 2, 0, width / 2, height)
drawReverseCameraRectangle()
```

### 3. New Class: SimpleRect

Added simple rectangle rendering for reverse camera (no trapezoid warping):

```kotlin
class SimpleRect(private val width: Float, private val height: Float) {
    // Flat rectangle with no perspective distortion
    // Used for reverse camera display on right half of screen
}
```

---

## CVBS Input Detection

### Finding CVBS Camera IDs

1. Install and run the app on Android head unit
2. Check logcat for camera detection:
   ```bash
   adb logcat | grep "All detected cameras"
   ```
3. Output will show available camera IDs, e.g.:
   ```
   All detected cameras: [0, 1, 5]
   ```
4. Identify which IDs correspond to:
   - CVBS 1 (left blind spot input)
   - CVBS 2 (right blind spot input)
   - Reverse camera input

### Update Camera Assignments

Edit `SurroundViewActivity.kt` lines 80-82:
```kotlin
val cvbs1CameraId = allCameras.getOrNull(X)  // Replace X with actual CVBS 1 ID
val cvbs2CameraId = allCameras.getOrNull(Y)  // Replace Y with actual CVBS 2 ID
val reverseCameraId = allCameras.getOrNull(Z) // Replace Z with reverse cam ID
```

### Common CVBS Camera ID Patterns

**AC8227L/MT8127 Devices:**
- Built-in cameras: IDs 0-1
- CVBS inputs: Often IDs 5-7 or handled through avin_capture
- May appear as "BACK" facing cameras in Camera2 API

**Check with:**
```bash
adb shell dumpsys media.camera
```

---

## Hardware Requirements

### Minimum Requirements
- ✅ 2 concurrent cameras support (for left + right)
- ✅ CVBS video inputs (analog camera connections)
- ✅ Android 5.0+ (API 21+)
- ✅ OpenGL ES 2.0

### Camera Inputs
- **CVBS 1:** Left side camera (wide-angle recommended)
- **CVBS 2:** Right side camera (wide-angle recommended)
- **Reverse:** Standard backup camera (triggered by reverse gear)

### Optional
- 3 concurrent cameras support (for smooth reverse camera updates)
- If limited to 2 cameras, reverse will update at lower frame rate

---

## Rendering Details

### Left Half - Trapezoid Blind Spot View

**Trapezoid Warping:**
- **Purpose:** Perspective correction for wide-angle cameras
- **Parameters:** 10 subdivisions, ratio-based warping
- **Left Camera:** -90° rotation
- **Right Camera:** 90° rotation
- **Vehicle:** Center overlay showing car position

**Benefits:**
- Provides natural-looking perspective
- Wide field of view at outer edges
- Narrow at vehicle edges (matches actual view)

### Right Half - Reverse Camera

**Simple Rectangle:**
- **No warping:** Flat display of reverse camera
- **Lower FPS:** Updates every 3rd frame (~10 FPS)
- **Full screen:** Uses entire right half
- **180° rotation:** Corrects camera orientation

**Why Lower FPS:**
- Prioritizes blind spot cameras (left/right) for real-time monitoring
- Reverse camera less critical when driving forward
- Reduces GPU load and camera bandwidth

---

## Stock Reverse Trigger Integration

### How It Works

1. **Driving Forward:** App displays left + right cameras + periodic reverse view
2. **Reverse Gear Engaged:** Stock Android head unit detects reverse signal (12V+)
3. **Stock Takes Over:** Head unit auto-switches to full-screen reverse camera
4. **Your App Pauses:** Android lifecycle calls `onPause()`, cameras released
5. **Forward Again:** Stock returns control, app resumes with `onResume()`

### No Trigger Handling Needed

**Why:**
- Stock head unit firmware handles reverse detection
- Automatic full-screen reverse camera override
- App doesn't need to detect reverse signal
- Clean separation: stock handles reverse, app handles blind spots

**App Focus:**
- ✅ Left blind spot monitoring
- ✅ Right blind spot monitoring
- ✅ Periodic reverse camera preview
- ❌ Reverse trigger detection (stock handles it)

---

## Performance Characteristics

### Frame Rates
- **Left Camera (CVBS 1):** 30 FPS (continuous)
- **Right Camera (CVBS 2):** 30 FPS (continuous)
- **Reverse Camera:** ~10 FPS (every 3rd frame)

### GPU Load
- **Trapezoid Rendering:** 2 quads × 10 subdivisions = 20 triangles/frame
- **Vehicle Overlay:** 1 textured quad = 2 triangles
- **Reverse Rectangle:** 1 quad = 2 triangles
- **Total:** ~24 triangles/frame (very light load)

### Camera Bandwidth
- **Active Cameras:** 2 continuous (left + right) + 1 periodic (reverse)
- **Resolution:** Typically 720p per camera
- **Bandwidth:** ~60 Mbps continuous + ~20 Mbps periodic

---

## Testing Checklist

### Initial Setup
- [ ] Install APK on Android head unit
- [ ] Connect CVBS 1 camera (left side)
- [ ] Connect CVBS 2 camera (right side)
- [ ] Connect reverse camera to reverse input
- [ ] Check logcat for detected camera IDs
- [ ] Update camera ID assignments in code
- [ ] Rebuild and reinstall

### Verify Functionality
- [ ] Left trapezoid shows CVBS 1 feed
- [ ] Right trapezoid shows CVBS 2 feed
- [ ] Vehicle overlay visible in center
- [ ] Reverse camera displays on right half
- [ ] Left/right cameras update smoothly (30 FPS)
- [ ] Reverse camera updates periodically (~10 FPS)
- [ ] No lag or stuttering

### Reverse Trigger Test
- [ ] Put vehicle in reverse gear
- [ ] Stock head unit takes over
- [ ] Full-screen reverse camera shown
- [ ] App pauses (cameras released)
- [ ] Put vehicle in drive
- [ ] App resumes automatically
- [ ] Left + right cameras restart
- [ ] Reverse camera resumes periodic updates

---

## Troubleshooting

### CVBS Cameras Not Detected
**Check:**
1. Physical CVBS connections (yellow RCA or 4-pin aviation connectors)
2. Head unit supports CVBS inputs (check manual)
3. `adb shell ls /dev/video*` shows video devices
4. Camera permissions in AndroidManifest.xml

**Solution:**
- Verify CVBS inputs enabled in head unit factory settings
- Check for "AV-IN" or "External Camera" settings
- May need to enable in head unit MCU configuration

### Wrong Camera Displayed in Wrong Position
**Fix:**
- Swap camera ID assignments in `SurroundViewActivity.kt`
- Update cvbs1CameraId and cvbs2CameraId values

### Reverse Camera Not Showing
**Check:**
1. Camera ID assigned to position 2
2. Reverse camera connected to correct input
3. Logcat shows camera opening successfully

### Trapezoid Warping Too Aggressive
**Adjust:**
- Edit `leftRightRatio` in `SurroundViewRenderer.kt` line 125
- Increase ratio = less warping (1.0 = no warping)
- Decrease ratio = more warping (0.5 = strong perspective)

### Frame Rate Too Low on Reverse Camera
**Increase:**
- Change `frameCount % 3` to `frameCount % 2` for ~15 FPS
- Or `frameCount % 1` for 30 FPS (but increases load)

---

## Future Enhancements

### Possible Improvements
1. **Dynamic Priority:** Increase reverse FPS when vehicle slowing down
2. **Touch Controls:** Tap reverse camera to make it full-screen temporarily
3. **Recording:** Save camera feeds to SD card
4. **Parking Lines:** Overlay distance guidelines on reverse camera
5. **Alerts:** Visual/audio warnings for detected obstacles
6. **Night Mode:** Auto-adjust brightness based on ambient light

---

## Build Instructions

```bash
# Clean build
cd ~/360-Viewer
rm -rf build app/build

# Build APK
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug

# Output location
# app/build/outputs/apk/debug/app-debug.apk

# Install to device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Run app
adb shell am start -n com.usbcamera.viewer/.SurroundViewActivity

# Monitor logs
adb logcat | grep -E "SurroundView|Camera"
```

---

## Code Summary

### Files Modified
1. **SurroundViewActivity.kt**
   - Changed camera assignments (removed front, added CVBS inputs)
   - Added logging for camera ID detection

2. **SurroundViewRenderer.kt**
   - Removed frontQuad and rearQuad
   - Added reverseRect (SimpleRect)
   - Implemented frame-based priority rendering
   - Split viewport into left/right halves
   - Added SimpleRect class for flat rectangle rendering

### Lines Changed
- **SurroundViewActivity.kt:** ~40 lines modified
- **SurroundViewRenderer.kt:** ~120 lines modified/added
- **Total:** ~160 lines changed

---

**Implementation Complete**
**Status:** Ready for device testing
**Next Step:** Install on head unit, verify CVBS camera IDs, update assignments
