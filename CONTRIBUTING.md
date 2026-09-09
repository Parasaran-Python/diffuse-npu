# Contributing to Diffuse-NPU

Thank you for your interest in contributing to **Diffuse-NPU**! We are building an ultra-fast, completely offline on-device text-to-image generation and super-resolution engine for Android, powered by Qualcomm Hexagon HTP NPUs and Microsoft ONNX Runtime Mobile.

---

## Code of Conduct

All contributors and maintainers are expected to adhere to our [Code of Conduct](CODE_OF_CONDUCT.md). Please report unacceptable behavior following the guidelines therein.

---

## Development Prerequisites

Before building the project, ensure your workstation has the following tools installed:

- **Operating System**: Linux (Ubuntu 22.04+ / Debian / Kali), macOS, or Windows with WSL2.
- **JDK**: OpenJDK 21 (Temurin or equivalent).
- **Android Studio**: Ladybug (2024.2.1) or newer.
- **Android SDK**:
  - Android API Platform: `android-35` (Android 15)
  - Build-Tools: `35.0.0`
- **Android NDK**: Version `28.2.13676358` (NDK r28)
- **CMake**: Version `3.22.1` or newer.

You can install the SDK components using the Android Studio SDK Manager or the command line:

```bash
sdkmanager --install \
  "platforms;android-35" \
  "build-tools;35.0.0" \
  "ndk;28.2.13676358" \
  "cmake;3.22.1"
```

---

## Building and Testing Locally

### 1. Clone the Repository
```bash
git clone https://github.com/Parasaran-Python/diffuse-npu.git
cd diffuse-npu
```

### 2. Run Unit Tests
Diffuse-NPU maintains 100% passing rate across comprehensive unit test suites covering schedulers, tokenizers, mathematical Gaussian noise generation, and pipeline state machines:
```bash
./gradlew testDebugUnitTest
```

### 3. Run Static Analysis (Lint)
```bash
./gradlew lintDebug
```

### 4. Build Debug APK
This will compile both Kotlin sources and native C++ shared libraries (`arm64-v8a` and `x86_64`):
```bash
./gradlew assembleDebug
```
The output binary will be located at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Architecture Principles

When proposing modifications to the codebase, please uphold these key architectural principles:

1. **Hardware Independence & Graceful Fallback**:
   - The native C++ bridge uses dynamic runtime linking (`dlopen`/`dlsym`) for Qualcomm QNN libraries.
   - The app must always build and execute cleanly on development machines and emulators without requiring proprietary Qualcomm blobs.
2. **Sequential Memory Discipline**:
   - Stable Diffusion and RealESRGAN graphs must never be kept loaded concurrently in memory. Always unload the diffusion context before loading the super-resolution context to respect the < 4 GB peak RAM budget.
3. **Coroutines & Reactive State**:
   - The pipeline state is exposed as reactive `StateFlow` streams via `PipelineManager`. Long-running tasks must support cooperative cancellation (`isActive` / atomic booleans).
4. **Bitmap Zero-Thrash Pooling**:
   - Utilize `BitmapPool` when decoding or post-processing images to prevent GC pauses on mobile devices.

---

## Pull Request Process

1. **Create a Feature Branch**:
   ```bash
   git checkout -b feature/my-enhancement
   # or
   git checkout -b fix/issue-description
   ```
2. **Follow Conventional Commits**:
   - `feat(...)`: New feature or capability
   - `fix(...)`: Bug fix
   - `docs(...)`: Documentation updates
   - `ci(...)`: CI/CD workflow modifications
   - `perf(...)`: Performance optimization
   - `refactor(...)`: Code refactoring without behavioral change
3. **Verify Locally**:
   Ensure `./gradlew lintDebug` and `./gradlew testDebugUnitTest` pass with zero errors before pushing.
4. **Open a Pull Request**:
   Fill out the standard PR template completely, referencing any related issues and documenting verification steps on physical hardware or emulators.
