# Camera Configurations for 360 Viewer

## System Overview

**Device**: Android Head Unit (MT8127/AC8227L compatible)
**Android Camera API**: Camera2
**Maximum Concurrent Cameras**: 2 (hardware limitation)
**Total Detected Cameras**: 3 normal camera devices

## Available Cameras

### Camera ID: 0
- **Type**: Built-in Back Camera
- **Facing**: LENS_FACING_BACK
- **Status**: Cannot open when other cameras active
- **Error**: "Too many cameras already open" (ERROR_MAX_CAMERAS_IN_USE)
- **Notes**: System allows only 2 cameras open simultaneously

### Camera ID: 1
- **Type**: Built-in Front Camera
- **Facing**: LENS_FACING_FRONT
- **Resolution**: 1264×712
- **Status**: ✅ Working
- **Position Assignment**: Front (position 0)
- **Preview Size**: 1264×712 pixels

### Camera ID: 5
- **Type**: USB Camera (External)
- **Facing**: LENS_FACING_BACK (reported as back-facing)
- **Resolution**: 1280×720
- **Status**: ✅ Working
- **Position Assignment**: Left (position 1)
- **Preview Size**: 1280×720 pixels

### Camera ID: 2-4
- **Status**: Not detected on this device
- **Notes**: IDs reserved for additional USB cameras when connected

## Current Application Configuration

### SurroundViewActivity Camera Assignments

```kotlin
// From logs: Camera assignments: [1, 5, 0, null]

Position 0 (Front):  Camera ID 1 (Built-in Front)  ✅ ACTIVE
Position 1 (Left):   Camera ID 5 (USB Camera)      ✅ ACTIVE
Position 2 (Rear):   Camera ID 0 (Built-in Back)   ❌ ERROR (max cameras)
Position 3 (Right):  null (No camera assigned)     ❌ NOT ASSIGNED
```

## Possible Camera Configurations

### Configuration A: 2-Camera Setup (Current - Working)
```
✅ Position 0 (Front): Camera 1 (Built-in Front) - 1264×712
✅ Position 1 (Left):  Camera 5 (USB Camera)     - 1280×720
❌ Position 2 (Rear):  None
❌ Position 3 (Right): None
```
**Status**: Active, limited by 2-camera hardware constraint

### Configuration B: Full 4-Camera Setup (Requires 4 USB Cameras)
```
✅ Position 0 (Front): Camera 2 (USB Camera #1)
✅ Position 1 (Left):  Camera 3 (USB Camera #2)
✅ Position 2 (Rear):  Camera 4 (USB Camera #3)
✅ Position 3 (Right): Camera 5 (USB Camera #4)
```
**Status**: Not possible - hardware limited to 2 concurrent cameras
**Workaround**: Would require camera multiplexing or switching logic

### Configuration C: 2-Camera USB Setup
```
✅ Position 0 (Front): Camera 5 (USB Camera #1) - 1280×720
✅ Position 2 (Rear):  Camera 6 (USB Camera #2) - 1280×720 (if connected)
❌ Position 1 (Left):  None
❌ Position 3 (Right): None
```
**Status**: Possible with 2 USB cameras, skipping built-in cameras

### Configuration D: Built-in Only (Testing)
```
✅ Position 0 (Front): Camera 1 (Built-in Front) - 1264×712
✅ Position 2 (Rear):  Camera 0 (Built-in Back)  - Unknown resolution
❌ Position 1 (Left):  None
❌ Position 3 (Right): None
```
**Status**: Possible if USB cameras disconnected

## Hardware Limitations

### Maximum Concurrent Cameras: 2
**Error Message**:
```
Status(-8): '8: connectHelper:1394: Too many cameras already open, cannot open camera "0"'
```

**Impact**:
- Cannot use all 4 camera positions simultaneously
- True 360° surround view requires camera multiplexing or dual-SoC setup
- Current implementation works with 2 cameras (partial surround view)

**Possible Solutions**:
1. **Camera Switching**: Cycle between camera pairs (front/rear, left/right)
2. **External USB Hub with Multiplexer**: Hardware solution
3. **Dual Android Systems**: Run two instances, each with 2 cameras
4. **Upgrade Hardware**: Use SoC that supports 4+ concurrent cameras

## Camera Detection Logic

### From `SurroundViewActivity.kt`:
```kotlin
val allCameras = cameraManager.cameraIdList.toList()

// USB cameras (ID >= 2)
val usbCameras = allCameras.filter { id ->
    id.toIntOrNull()?.let { it >= 2 } ?: false
}

val backCamera = allCameras.find { id ->
    val chars = cameraManager.getCameraCharacteristics(id)
    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
}

val frontCamera = allCameras.find { id ->
    val chars = cameraManager.getCameraCharacteristics(id)
    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
}

// Assign to positions: 0=front, 1=left, 2=rear, 3=right
cameraAssignments[0] = frontCamera      // ID 1
cameraAssignments[1] = usbCameras[0]    // ID 5
cameraAssignments[2] = backCamera       // ID 0
cameraAssignments[3] = usbCameras[1]    // null (not connected)
```

## USB Camera Expansion

### Adding More USB Cameras

**Camera ID Assignment**:
- Built-in cameras: IDs 0-1
- USB cameras: IDs start at 2, increment for each connected camera
- Maximum detectable: Limited by USB ports and power

**Typical USB Camera IDs**:
```
Camera 2: First USB camera
Camera 3: Second USB camera
Camera 4: Third USB camera
Camera 5: Fourth USB camera
Camera 6: Fifth USB camera (if supported)
```

**Current USB Camera Count**: 1 (Camera ID 5)

## Recommended Configurations

### For Testing (Current Setup)
- **Front**: Built-in Camera 1
- **Left**: USB Camera 5
- **Rear**: Empty (limited by hardware)
- **Right**: Empty (no camera connected)

### For Production (Requires Hardware Upgrade)
- **All Positions**: 4× USB cameras (IDs 2, 3, 4, 5)
- **Requirement**: Android head unit supporting 4+ concurrent cameras
- **Example SoCs**: Qualcomm Snapdragon automotive series, NVIDIA Tegra

## Resolution Support

### Detected Resolutions

**Built-in Front Camera (ID 1)**:
- Preview: 1264×712
- Capture: Unknown (not queried)

**USB Camera (ID 5)**:
- Preview: 1280×720 (720p)
- Capture: Likely 1920×1080 (1080p) if supported

### Recommended Resolution
- **720p (1280×720)**: Best performance on MT8127/AC8227L SoCs
- **1080p (1920×1080)**: May cause frame drops on lower-end hardware
- **480p (640×480)**: Fallback for very old USB cameras

## Camera Service Status

### From `dumpsys media.camera`

```
Number of camera devices: 3
Number of normal camera devices: 3
Active Camera Clients:
  - Camera ID: 1 (PID: 23245, Package: com.usbcamera.viewer, State: ACTIVE)
  - Camera ID: 5 (PID: 23245, Package: com.usbcamera.viewer, State: ACTIVE)

Recent Events:
  - REJECT device 0: Too many cameras already open
  - CONNECT device 1: SUCCESS
  - CONNECT device 5: SUCCESS
```

## Troubleshooting

### "Too many cameras already open"
**Cause**: Hardware supports max 2 concurrent cameras
**Solution**: Close unused cameras before opening new ones, or upgrade hardware

### USB Camera Not Detected
**Check**:
1. `ls /dev/video*` - Should show `/dev/video0`, `/dev/video1`, etc.
2. USB camera permissions in AndroidManifest.xml
3. USB OTG power supply (cameras may need external power)

### Camera ID Changes on Reboot
**Cause**: USB enumeration order varies
**Solution**: Match cameras by characteristics (resolution, facing) instead of ID

---

**Last Updated**: 2026-06-22
**Device**: Android Head Unit with MT8127 SoC
**App Version**: 360 Viewer v1.0
