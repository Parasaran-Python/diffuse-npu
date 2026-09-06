# QNN Libraries for arm64-v8a

Place the following Qualcomm QNN libraries here for devices without system QNN:

## Required Libraries
- `libQnnSystem.so`
- `libQnnHtp.so`
- `libQnnHtpV73Stub.so`
- `libQnnHtpV73Skel.so`
- `libQnnCpu.so`
- `libQnnGpu.so`

## Source
Download from Qualcomm AI Engine Direct SDK (QAIRT):
- SDK 2.49+: https://developer.qualcomm.com/software/qualcomm-ai-engine-direct-sdk
- Path in SDK: `lib/aarch64-android/`

## Samsung S23 Ultra (Snapdragon 8 Gen 2)
On this device, these libraries are typically available in `/vendor/lib64/` and the app will use them automatically. This directory is a fallback for:
- Development on non-Snapdragon devices
- Testing without target hardware
- Devices with older/incomplete vendor images

## Note
The app's `qnn_loader.cpp` detects `libQnnHtp.so` via `dlopen` at runtime. If found in system paths, it uses those. Otherwise, it falls back to bundled libs in this directory.