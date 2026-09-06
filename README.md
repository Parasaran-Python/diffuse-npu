# Android Stable Diffusion + RealESRGAN on NPU

On-device text-to-image generation and super-resolution for Android, powered by Qualcomm Neural Processing SDK (QNN) and the Hexagon Tensor Processor (HTP).

---

## 1. Overview & Architecture

This application runs **Stable Diffusion (DreamShaper v8)** and **RealESRGAN** completely on-device with zero internet dependency after models are downloaded. Compute-heavy operations are offloaded to Qualcomm's Hexagon v73 HTP NPU on Snapdragon 8 Gen 2 hardware.

### 1.1 Generation & Upscaling Pipeline

```
Prompt ──► CLIP Text Encoder ──► Latent Embeddings
                                       │
                                       ▼
Seed & Noise ───────────► UNet Iterative Denoising (N Steps)
                                       │
                                       ▼
                                 VAE Decoder
                                       │
                                       ▼
                             SD Output (512×512)
                                       │
                        ┌──────────────┴──────────────┐
                        ▼                             ▼
              [Pass-through / Done]         RealESRGAN (2x / 4x)
                                                      │
                                                      ▼
                                            Upscaled Image (1024 / 2048)
```

1. **CLIP Text Encoder**: Encodes the prompt and negative prompt into text embeddings.
2. **UNet Latent Denoiser**: Runs an iterative noise-prediction loop in latent space according to the selected scheduler/sampler (Euler a, DPM++ 2M Karras, DPM++ SDE Karras, DDIM) and CFG scale.
3. **VAE Decoder**: Decodes the final latents into a 512×512 RGB bitmap.
4. **RealESRGAN Super-Resolution**: Upscales the decoded image by 2x (1024×1024) or 4x (2048×2048).
5. **Sequential Context Management**: High memory efficiency is maintained by loading and unloading QNN graph contexts sequentially between the SD stage and the RealESRGAN stage.

### 1.2 Application Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                          Jetpack Compose UI                            │
│  - GenerateScreen (Prompts, Sliders, Sampler, Seed, Upscale Mode)      │
│  - GalleryScreen (Generated History & Image Inspection)                │
│  - SettingsScreen (Hardware/HTP Status, Model Downloader, Config)      │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ StateFlow / Coroutines
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                           Pipeline Manager                             │
│  - Parameter Validation & Boundary Checks                              │
│  - Pipeline Lifecycle (Idle -> Loading -> Generating -> Upscaling)     │
│  - Batch Scheduling & Progress Reporting                               │
└───────────────────┬────────────────────────────────┬───────────────────┘
                    │                                │
                    ▼                                ▼
┌──────────────────────────────────────┐ ┌───────────────────────────────┐
│            Model Manager             │ │       QNN Native Bridge       │
│  - HTTP Range Downloads (Resumable)  │ │  - JNI C++ Interface          │
│  - SHA-256 Checksum Verification     │ │  - Dynamic `dlopen` Loader    │
│  - Model Directory Caching           │ │  - HTP Detection & Stub Mode  │
└──────────────────────────────────────┘ └───────────────────────────────┘
```

- **UI Layer (`com.example.sdnpu.ui`)**: Built with Jetpack Compose and Material 3 design system, supporting dark theme, exposed dropdown menus for samplers and models, interactive sliders, and real-time generation feedback.
- **Pipeline Layer (`com.example.sdnpu.pipeline`)**: Validates input parameters (`GenerationParams`) and orchestrates generation steps through a reactive `StateFlow<PipelineState>`.
- **Model Layer (`com.example.sdnpu.model`)**: Handles model discovery, remote manifest downloads via OkHttp, streaming downloads with progress reporting, and cryptographic SHA-256 verification.
- **Engine Layer (`com.example.sdnpu.engine` & `cpp/`)**: Native C++ bridge (`libsdnpu_engine.so`) with dynamic QNN runtime library loading (`dlopen`/`dlsym`), allowing seamless builds and execution on development machines without requiring proprietary Qualcomm blobs.

---

## 2. Target Device Specifications

- **Device**: Samsung Galaxy S23 Ultra (SM-S918B / SM-S918U / SM-S9180)
- **SoC**: Qualcomm Snapdragon 8 Gen 2 for Galaxy (SM8550-AC)
- **NPU**: Qualcomm Hexagon v73 HTP (Hexagon Tensor Processor)
- **Target Architectures**:
  - `arm64-v8a`: Primary production target with full QNN HTP hardware acceleration.
  - `x86_64`: Supported for host emulator testing with stub backend fallback.

---

## 3. Prerequisites & Environment Setup

To build and run this project, make sure your development environment meets the following specifications:

| Requirement | Supported Version | Notes |
|---|---|---|
| **Operating System** | Linux / macOS / Windows | Linux (Ubuntu/Debian/Fedora/Arch) recommended |
| **JDK** | Java 17 | Eclipse Adoptium OpenJDK 17 or OpenJDK 17 |
| **Android SDK** | API 35 (`compileSdk` & `targetSdk`) | `minSdk = 26` (Android 8.0+) |
| **Android NDK** | `28.2.13676358` (NDK r28) | Configured in `app/build.gradle.kts` |
| **CMake** | `3.22.1` | Installed via Android SDK Manager |
| **Gradle** | `9.5.0` | Provided via Gradle Wrapper (`./gradlew`) |
| **Android Gradle Plugin** | `9.3.2` | Configured in `gradle/libs.versions.toml` |
| **Kotlin** | `2.0.21` | Jetpack Compose compiler enabled |
| **Qualcomm QAIRT SDK** | `2.49.0+` *(Optional for dev builds)* | Required only for generating compiled QNN context binaries |

### Android SDK Components Installation

Ensure the required SDK, NDK, and CMake versions are installed:

```bash
sdkmanager "platforms;android-35" \
           "build-tools;35.0.0" \
           "ndk;28.2.13676358" \
           "cmake;3.22.1"
```

---

## 4. Building & Running Tests

### 4.1 Build the Debug APK

Run Gradle to compile native C++ sources, generate Kotlin/Compose artifacts, and package the debug APK:

```bash
./gradlew assembleDebug
```

The assembled APK will be located at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### 4.2 Run the Unit Test Suite

Execute the complete unit test suite across all modules (engine, model, pipeline):

```bash
./gradlew testDebugUnitTest
```

To force a re-run of all tests and inspect detailed execution logs:

```bash
./gradlew testDebugUnitTest --rerun-tasks --info
```

HTML test reports are generated at:
```
app/build/reports/tests/testDebugUnitTest/index.html
```

### 4.3 Sideloading to Device

Connect your target device via USB (with USB Debugging enabled) and run:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 5. Local Model Server Setup

Due to large binary file sizes (models range from 500 MB to 2 GB), weights are downloaded at runtime rather than bundled inside the APK.

### 5.1 Storage Directory Layout

Models on the host server and on-device follow this layout:

```
models/
└── dreamshaper_v8/
    ├── manifest.json
    ├── clip_text_encoder.bin
    ├── unet.bin
    └── vae_decoder.bin
```

On-device, models are stored inside the app-private directory:
```
context.filesDir/models/<model_id>/
```

### 5.2 Model Manifest Schema (`manifest.json`)

The server must provide a `manifest.json` inside each model's directory:

```json
{
  "model_id": "dreamshaper_v8",
  "version": "1.0",
  "components": [
    {
      "name": "clip_text_encoder",
      "file": "clip_text_encoder.bin",
      "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    },
    {
      "name": "unet",
      "file": "unet.bin",
      "sha256": "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb"
    },
    {
      "name": "vae_decoder",
      "file": "vae_decoder.bin",
      "sha256": "4e1243bd22c66e76c2ba9eddc1f91394e57f9f835c9101d2fd823b207a9775f0"
    }
  ],
  "qnn_sdk_version": "2.49.0",
  "target_htp": "v73"
}
```

- `model_id`: Unique identifier for the model.
- `version`: Version string.
- `components`: Array of required binary graph weights.
  - `name`: Component identifier (`clip_text_encoder`, `unet`, `vae_decoder`, etc.).
  - `file`: Relative filename.
  - `sha256`: 64-character lowercase hexadecimal SHA-256 hash.
- `qnn_sdk_version`: Target QAIRT/QNN SDK version.
- `target_htp`: Hexagon Tensor Processor target (`v73` for Snapdragon 8 Gen 2).

### 5.3 Serving Models with Python

On your local workstation or server (connected to the same local Wi-Fi network as your phone):

```bash
# Navigate to the directory containing the 'models' folder
cd /path/to/hosting_directory

# Start the HTTP server on port 8080
python3 -m http.server 8080
```

Verify the endpoint in a browser:
```
http://<HOST_IP>:8080/models/dreamshaper_v8/manifest.json
```

### 5.4 Downloading via the App

1. Open the app on your Samsung S23 Ultra.
2. Navigate to the **Settings** tab.
3. Tap **Download Model**.
4. Enter your host's local IP and endpoint, for example:
   ```
   http://192.168.1.100:8080/models/dreamshaper_v8/
   ```
5. Tap **Download**. The app will:
   - Fetch and parse `manifest.json`.
   - Download each component file with streaming progress indicators.
   - Calculate and verify the SHA-256 checksum for each downloaded file.
   - Cache the model ready for generation.

---

## 6. Project Roadmap

### Phase 1: Foundation (Current - Completed)
- [x] Gradle 9.5 Kotlin DSL build system with Version Catalog (`libs.versions.toml`).
- [x] NDK r28 & CMake 3.22 C++ integration with QNN dynamic loader and fallback stubs.
- [x] OkHttp model downloader with resumable stream handling and SHA-256 integrity verification.
- [x] Pipeline orchestration data classes, samplers, upscale modes, and boundary validation.
- [x] Jetpack Compose Material 3 dark-themed UI (Generate, Gallery, Settings, Download Dialog).
- [x] End-to-end build and unit test verification.

### Phase 2: SD Engine
- [ ] CLIP text encoder QNN context integration and subword tokenization.
- [ ] UNet iterative latent denoising loop implementation in native C++.
- [ ] VAE decoder execution to generate 512×512 RGB images.
- [ ] Schedulers / samplers integration (Euler a, DPM++ 2M Karras, DDIM).
- [ ] End-to-end text-to-image generation benchmark on S23 Ultra NPU.

### Phase 3: RealESRGAN Chaining
- [ ] RealESRGAN QNN context integration (2x and 4x scale models).
- [ ] Sequential memory-efficient execution chain (SD -> Free SD Context -> RealESRGAN).
- [ ] Batch generation support (1 to 4 images per prompt).
- [ ] Real-time progress updates across generation and upscaling stages.

### Phase 4: Advanced UI & Polish
- [ ] Room database integration for persistent generation history and image metadata.
- [ ] Full-screen image viewer with metadata inspection, sharing, and export.
- [ ] Model selector supporting pre-merged LoRA variants (anime, realistic, artistic).
- [ ] App settings persistence using Jetpack DataStore.
- [ ] Battery and thermal throttling detection with warning indicators.

### Phase 5: Optimization & Device Testing
- [ ] Snapdragon 8 Gen 2 on-device performance profiling (NPU vs GPU vs CPU).
- [ ] Model quantization tuning (INT8 weights / activations vs INT16 latents).
- [ ] Peak memory profiling ensuring < 4 GB RAM footprint.
- [ ] Zero-crash stability verification across 100 consecutive generations.
- [ ] Production release signing and standalone sideload package.

---

## 7. License

This project is developed for local on-device inference research and personal use.
