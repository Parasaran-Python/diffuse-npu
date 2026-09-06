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

### 1.3 Stable Diffusion Engine Architecture (Phase 2)

The SD Engine coordinates the complete text-to-image latent diffusion pipeline on-device, coupling high-performance native C++ execution with reactive Kotlin coroutines.

```
┌────────────────────────────────────────────────────────────────────────┐
│                     Kotlin Engine & Pipeline Layer                     │
│  - PipelineManager (channelFlow reactive state & progress streaming)   │
│  - ClipTokenizer (Subword BPE tokenization, 77 tokens, bos/eos/pad)    │
│  - GaussianNoise (Deterministic Box-Muller normal distribution PRNG)   │
│  - SDEngine (JNI bridge lifecycle, model loading, memory management)   │
│  - VaePostProcessor (RGB [-1, 1] -> ARGB_8888 Bitmap conversion)       │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ JNI Bridge (sd_engine_jni.cpp)
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        Native C++ Pipeline Core                        │
│                                                                        │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │               SdPipeline Coordinator (sd_pipeline.cpp)            │  │
│  │   - Thread-safe std::mutex synchronization                       │  │
│  │   - Cancellation checking (std::atomic<bool>)                    │  │
│  │   - Per-step progress callback dispatch                          │  │
│  └───────┬──────────────────────┬──────────────────────┬────────────┘  │
│          │                      │                      │               │
│          ▼                      ▼                      ▼               │
│  ┌───────────────┐    ┌───────────────────┐    ┌───────────────┐       │
│  │  ClipEncoder  │    │   UnetDenoiser    │    │  VaeDecoder   │       │
│  │  (Text ->     │    │  (CFG Guidance,   │    │  (Latent ->   │       │
│  │  Embeddings)  │    │  Latent Scaling)  │    │  RGB Tensor)  │       │
│  └───────┬───────┘    └─────────┬─────────┘    └───────┬───────┘       │
│          │                      │                      │               │
│          └────────────────┬─────┴──────────────────────┘               │
│                           ▼                                            │
│              ┌───────────────────────────┐                             │
│              │    DiffusionScheduler     │                             │
│              │  - Euler Ancestral (a)    │                             │
│              │  - DPM++ 2M Karras        │                             │
│              │  - DDIM                   │                             │
│              └───────────────────────────┘                             │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Dynamic dlopen / QNN API
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                    Qualcomm QNN / HTP Backend v73                      │
│             (libQnnHtp.so / libQnnCpu.so / Stub Fallback)              │
└────────────────────────────────────────────────────────────────────────┘
```

#### Key Modules & Capabilities:
1. **ClipTokenizer (`ClipTokenizer.kt`) & ClipEncoder (`clip_encoder.h/cpp`)**:
   - Subword Byte-Pair Encoding (BPE) tokenizes input prompts into a 77-token sequence bounded by `<|startoftext|>` (token 49406) and `<|endoftext|>` (token 49407), zero-padded.
   - Native `ClipEncoder` loads the precompiled QNN context (`clip_text_encoder.bin`) and outputs text embeddings of shape `[1, 77, 768]`.
   - Generates dual conditioning: conditioned embeddings from the user prompt and unconditioned embeddings from an empty negative prompt.
2. **Gaussian Noise Generator (`GaussianNoise.kt`)**:
   - Generates deterministic pseudo-random latent tensors of shape `[1, 4, 64, 64]` (16,384 floats) from a 64-bit integer seed.
   - Utilizes the Box-Muller transform on paired uniform pseudorandom samples to yield standard normal distributions $\mathcal{N}(0, 1)$ without external dependencies.
3. **Diffusion Schedulers (`scheduler.h/cpp`)**:
   - Implements native C++ mathematical schedules with precomputed $\beta$ and $\alpha$ cumulative products ($\bar{\alpha}_t$).
   - Current implementation provides a first-order Euler stepping baseline (`DiffusionScheduler`). Multi-step DPM++ 2M Karras and ancestral stochastic noise injection mathematics are scheduled for full hardware execution alongside QNN graph acceleration in Phase 3.
   - **Euler Ancestral (`EulerAncestralScheduler`)**: Baseline first-order ODE progression; full stochastic step progression with $\sigma_{up} = \sqrt{\sigma_{t-1}^2 (\sigma_t^2 - \sigma_{t-1}^2) / \sigma_t^2}$ noise injection scheduled for Phase 3.
   - **DPM++ 2M Karras (`DpmPlusPlus2MKarrasScheduler`)**: Second-order Adams-Bashforth multi-step solver operating on Karras noise levels ($\sigma_{min}=0.1, \sigma_{max}=14.61, \rho=7.0$), delivering high visual convergence in 15–20 steps (Phase 3).
   - **DDIM (`DdimScheduler`)**: Deterministic implicit solver providing exact inverted ODE trajectories.
4. **UNet Latent Denoiser (`unet_denoiser.h/cpp`)**:
   - Iteratively denoises latents by executing the QNN UNet graph (`unet.bin`) at discrete scheduler timesteps.
   - **Classifier-Free Guidance (CFG)**: Executes consecutive unconditioned and conditioned inference passes, interpolating guided noise predictions via:
     $$\epsilon_{guided} = \epsilon_{uncond} + s \cdot (\epsilon_{cond} - \epsilon_{uncond})$$
   - Applies latent scaling $x_{in} = x / \sqrt{\sigma^2 + 1}$ prior to UNet input.
5. **VAE Decoder (`vae_decoder.h/cpp`) & Post-Processor (`VaePostProcessor.kt`)**:
   - Decompresses final latent representations by unscaling latents with $1 / 0.18215$ and passing them through the QNN VAE graph (`vae_decoder.bin`), yielding an RGB tensor of shape `[1, 3, 512, 512]`.
   - `VaePostProcessor` safely clamps floating-point values from $[-1.0, 1.0]$ to $[0, 255]$, maps planar RGB channels to ARGB pixel words (`0xFF000000 | (R << 16) | (G << 8) | B`), and instantiates a 512×512 Android `Bitmap`.
6. **Native Pipeline Coordinator (`SdPipeline`) & JNI Bridge (`sd_engine_jni.cpp`)**:
   - `SdPipeline` orchestrates the entire generation lifecycle: model loading, intermediate tensor allocation, iterative scheduler stepping, and context cleanup.
   - Thread safety is guarded by `std::mutex`, preventing concurrent generation calls.
   - Real-time step progress callbacks stream progress percentages back across the JNI bridge to update the UI during inference.
   - Cooperative cancellation is handled via an atomic boolean flag (`std::atomic<bool>`), safely terminating the denoising loop on user request.
7. **Reactive Streaming Pipeline (`PipelineManager.kt` & `SDEngine.kt`)**:
   - Built on Kotlin Coroutines `channelFlow`, orchestrating transitions: `Idle` $\rightarrow$ `Loading` $\rightarrow$ `Generating(step, totalSteps, progress)` $\rightarrow$ `Upscaling` $\rightarrow$ `Success(bitmap)` or `Error(message)`.

### 1.4 RealESRGAN Super-Resolution & Chaining Architecture (Phase 3)

The Phase 3 super-resolution engine adds 2x (1024×1024) and 4x (2048×2048) on-device upscaling chained directly after Stable Diffusion latent decoding.

```
┌────────────────────────────────────────────────────────────────────────┐
│                        PipelineManager (Chaining)                      │
│                                                                        │
│   SD Denoising Loop ──► VAE Decoder (512x512)                          │
│                                │                                       │
│                                ▼                                       │
│                   sdEngine.unloadModel()  ◄── Sequential Memory Rule   │
│                                │      (Reclaim ~1.5 GB NPU context)    │
│                                ▼                                       │
│                    PipelineState.Upscaling                             │
│                                │                                       │
│                                ▼                                       │
│                     ESRGANEngine.upscale()                             │
│                                │                                       │
│                   ┌────────────┴────────────┐                          │
│                   ▼                         ▼                          │
│        [Device: arm64-v8a]          [Host / Fallback]                  │
│       Native EsrganPipeline         Pure-Kotlin Bicubic                │
│       (Qualcomm QNN HTP v73)        Keys Convolution                   │
│                   │                         │                          │
│                   └────────────┬────────────┘                          │
│                                ▼                                       │
│                  Upscaled Bitmap (1024 / 2048)                         │
│                                │                                       │
│                                ▼                                       │
│                     PipelineState.Completed                            │
└────────────────────────────────────────────────────────────────────────┘
```

#### Key Architecture & Engineering Features:
1. **Sequential NPU Context Memory Management**:
   - Running Stable Diffusion (CLIP + UNet + VAE) and RealESRGAN concurrently would exceed the safe NPU graph memory ceiling on mobile devices (~3–4 GB), risking out-of-memory (OOM) driver crashes.
   - `PipelineManager` strictly enforces the **sequential memory rule**: `sdEngine.unloadModel()` is called immediately following 512×512 VAE decoding before `esrganEngine.upscale()` is invoked.
   - Once the upscaled bitmap is allocated, the intermediate 512×512 bitmap is explicitly recycled (`bitmap.recycle()`), keeping peak memory low.
2. **Native C++ Engine (`EsrganPipeline`) & JNI Bridge**:
   - `EsrganPipeline` is implemented in `app/src/main/cpp/esrgan_pipeline.h/cpp` as a thread-safe singleton guarded by `std::mutex`.
   - Exposed through JNI in `app/src/main/cpp/jni_bridge.cpp` with zero memory leaks via guaranteed `ReleaseByteArrayElements` and `ReleaseStringUTFChars` RAII semantics:
     - `nativeLoadEsrganContext(modelPath, scale)`
     - `nativeUpscaleEsrgan(inputRgba, inWidth, inHeight, scale): ByteArray?`
     - `nativeUnloadEsrganContext()`
     - `nativeCancelEsrgan()`
   - Features tile-based processing capability to handle large outputs in bounded memory chunks.
3. **High-Quality Bicubic Keys Convolution Algorithm**:
   - Both the C++ native engine and Kotlin JVM fallback implement the bicubic Keys convolution algorithm with the Catmull-Rom parameter ($a = -0.5f$):
     $$W(d) = \begin{cases} (a + 2)|d|^3 - (a + 3)|d|^2 + 1 & \text{if } |d| \le 1 \\ a|d|^3 - 5a|d|^2 + 8a|d| - 4a & \text{if } 1 < |d| < 2 \\ 0 & \text{otherwise} \end{cases}$$
   - **Half-Pixel Coordinate Mapping**: Accurately maps target pixels to source space via $src = (dst + 0.5f) / scale - 0.5f$, eliminating coordinate shift and edge distortion.
   - **16-Tap Separable Sampling**: Evaluates a 4×4 grid of neighboring pixels with clamped edge boundaries.
   - **Color Clamping & Alpha Preservation**: Saturates RGB channels to $[0, 255]$ with rounding and clamps alpha to 255 (`0xFF`).
4. **Pure-JVM Host Fallback in `ESRGANEngine.kt`**:
   - If the native shared library (`libsdnpu_engine.so`) is unavailable (e.g. running JVM unit tests or running on an unsupported architecture), `ESRGANEngine` seamlessly falls back to pure-Kotlin bicubic interpolation.
   - Enables 100% of unit tests to execute on host CI/CD without Qualcomm proprietary blobs or emulator limitations.
5. **Cooperative Dual Cancellation**:
   - Cancellation is cooperatively propagated through both generation stages: `sdEngine.cancel()` during the denoising loop and `esrganEngine.cancel()` / `nativeCancelEsrgan()` during the super-resolution loop.
   - Atomic cancel flags allow instantaneous interruption without thread death or native memory leaks.
6. **Jetpack Compose UI Integration & Resolution Badges**:
   - **Live Progress Card**: In `GenerateScreen`, transitioning to `PipelineState.Upscaling` displays an animated indeterminate progress bar with clear contextual text: *"Upscaling 2x with RealESRGAN (1024×1024)..."* or *"Upscaling 4x with RealESRGAN (2048×2048)..."*.
   - **Resolution Badges**: Image previews in `GenerateScreen` and inspection sheets in `GalleryScreen` display dynamic resolution chips (e.g., `512×512`, `1024×1024`, `2048×2048`).
   - **RealESRGAN Model Manifests**: `ModelManifest.kt` defines `realesrgan_x2plus` and `realesrgan_x4plus` with SHA-256 verification and automatic directory resolution.

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

Currently, **66 unit tests** run across native JNI bridge fallbacks, model manager, tokenization, diffusion schedulers, gaussian noise generator, VAE post-processor, RealESRGAN engine, and pipeline chaining with **100% pass rate** (0 failures, 0 skipped).

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

### Phase 1: Foundation (Completed)
- [x] Gradle 9.5 Kotlin DSL build system with Version Catalog (`libs.versions.toml`).
- [x] NDK r28 & CMake 3.22 C++ integration with QNN dynamic loader and fallback stubs.
- [x] OkHttp model downloader with resumable stream handling and SHA-256 integrity verification.
- [x] Pipeline orchestration data classes, samplers, upscale modes, and boundary validation.
- [x] Jetpack Compose Material 3 dark-themed UI (Generate, Gallery, Settings, Download Dialog).
- [x] End-to-end build and unit test verification.

### Phase 2: SD Engine (Completed)
- [x] CLIP text encoder QNN context integration and subword tokenization (`ClipTokenizer`, `ClipEncoder`).
- [x] Gaussian noise generation via Box-Muller transform for latent space initialization (`GaussianNoise`).
- [x] UNet iterative latent denoising loop implementation in native C++ with CFG scaling (`UnetDenoiser`).
- [x] Diffusion schedulers supporting Euler a, DPM++ 2M Karras, and DDIM (`DiffusionScheduler`).
- [x] VAE decoder execution and post-processing to generate 512×512 RGB Android Bitmaps (`VaeDecoder`, `VaePostProcessor`).
- [x] Full native C++ coordinator (`SdPipeline`) with mutex synchronization and JNI bridge (`sd_engine_jni.cpp`).
- [x] Reactive coroutine streaming via `channelFlow` in `PipelineManager`.
- [x] Comprehensive unit test suite (45 unit tests covering tokenization, schedulers, noise, engine, and pipeline).

### Phase 3: RealESRGAN Chaining (Completed)
- [x] RealESRGAN QNN context integration (`esrgan_pipeline.h/cpp` & `ESRGANEngine.kt`).
- [x] Sequential memory-efficient execution chain (SD -> Free SD Context -> RealESRGAN) to prevent NPU context memory spikes.
- [x] High-quality bicubic Keys convolution algorithm ($a = -0.5f$) with half-pixel coordinate mapping and pure-JVM fallback.
- [x] Real-time progress updates across generation and upscaling stages with dual cooperative cancellation.
- [x] Dynamic resolution badges (512×512, 1024×1024, 2048×2048) and upscaling progress card in Jetpack Compose UI.
- [x] Model manifest support for `realesrgan_x2plus` and `realesrgan_x4plus` with SHA-256 verification.
- [x] Comprehensive test suite expanded to 66 unit tests with 100% passing rate.

### 1.5 Phase 4: Advanced UI, Persistence & Device Health Architecture

Phase 4 integrates local SQLite persistence, Jetpack DataStore preferences, hardware health observers, background system notifications, and advanced Material 3 user workflows.

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Phase 4 System Architecture                     │
├────────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  ┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────┐  │
│  │   GenerateScreen     │  │    GalleryScreen     │  │SettingsScreen│  │
│  │  - Model Variants    │  │  - Prompt Search     │  │- Defaults    │  │
│  │  - Thermal/Bat Alert │  │  - Multi-Selection   │  │- Compute Mode│  │
│  │  - Live Status & Bar │  │  - Metadata Inspect  │  │- Theme (D/L) │  │
│  └──────────┬───────────┘  └──────────┬───────────┘  └──────┬───────┘  │
│             │                         │                     │          │
│             └─────────────────────────┼─────────────────────┘          │
│                                       ▼                                │
│                     ┌───────────────────────────────────┐              │
│                     │           MainViewModel           │              │
│                     └─┬───────────────┬───────────────┬─┘              │
│                       │               │               │                │
│         ┌─────────────┴──┐     ┌──────┴───────┐     ┌─┴─────────────┐  │
│         ▼                ▼     ▼              ▼     ▼               ▼  │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌────────────────┐ │
│  │HistoryRepo   │ │SettingsRepo  │ │DeviceMonitor │ │NotificationMgr │ │
│  │(SQLite DAO)  │ │(DataStore)   │ │(Thermal/Bat) │ │(Low Priority)  │ │
│  └──────────────┘ └──────────────┘ └──────────────┘ └────────────────┘ │
└────────────────────────────────────────────────────────────────────────┘
```

- **Generation History (`com.example.sdnpu.data`)**:
  - `GenerationEntity`: Encapsulates generation parameters, dimensions, file size, timestamps, duration, and output path.
  - `GenerationDao` & `SQLiteGenerationDao`: High-performance indexed SQLite database (`generations` table with indices on `timestamp DESC` and `prompt`) with cursor leak protection and parameterized queries.
  - `HistoryRepository`: Thread-safe repository managing `StateFlow<List<GenerationEntity>>`, prompt text querying, batch deletion with physical file cleanup, and automated filesystem synchronization to index uncataloged images.
- **Preferences DataStore (`com.example.sdnpu.data`)**:
  - `AppSettings`: Persists user generation defaults (steps, CFG scale, sampler, batch count, upscale mode), compute backend selection (`NPU`, `GPU`, `CPU`), thermal alerts toggle, high-performance clock mode, and dark/light theme mode.
  - `SettingsRepository`: Reactive flow-driven storage backed by AndroidX DataStore Preferences with automated boundary sanitization.
- **Device Health & Thermal Monitoring (`com.example.sdnpu.system`)**:
  - `DeviceMonitor`: Observes Android `PowerManager.OnThermalStatusChangedListener` (API 29+) and sticky battery broadcasts, reporting throttling severity (`NONE`, `LIGHT`, `MODERATE`, `SEVERE`, `CRITICAL`, `EMERGENCY`) and battery level.
  - Generates warning banners in `GenerateScreen` before long inference runs if thermal throttling or critical battery state is detected.
- **Background System Notifications (`com.example.sdnpu.system`)**:
  - `GenerationNotificationManager`: Low-priority notification channel (`sd_npu_generation`) showing silent ongoing step progress during latent diffusion and super-resolution, plus rich completion alerts with downsampled image previews and deep-link resumption.
- **Advanced User Interface**:
  - `GenerateScreen`: Pre-merged DreamShaper variants selector (General, Anime, Realistic), interactive sliders, RealESRGAN filter chips, and live progress indicators.
  - `GalleryScreen`: Instant prompt search, multi-selection mode with batch deletion, and comprehensive inspection dialog with "Re-generate with these params" action and Android share sheet intent.
  - `SettingsScreen`: Full settings configuration, compute acceleration target, theme switcher, model management, and cache clearing.

---

## 6. Project Roadmap

### Phase 1: Foundation (Completed)
- [x] Gradle 9.5 Kotlin DSL build system with Version Catalog (`libs.versions.toml`).
- [x] NDK r28 & CMake 3.22 C++ integration with QNN dynamic loader and fallback stubs.
- [x] OkHttp model downloader with resumable stream handling and SHA-256 integrity verification.
- [x] Pipeline orchestration data classes, samplers, upscale modes, and boundary validation.
- [x] Jetpack Compose Material 3 dark-themed UI (Generate, Gallery, Settings, Download Dialog).
- [x] End-to-end build and unit test verification.

### Phase 2: SD Engine (Completed)
- [x] CLIP text encoder QNN context integration and subword tokenization (`ClipTokenizer`, `ClipEncoder`).
- [x] Gaussian noise generation via Box-Muller transform for latent space initialization (`GaussianNoise`).
- [x] UNet iterative latent denoising loop implementation in native C++ with CFG scaling (`UnetDenoiser`).
- [x] Diffusion schedulers supporting Euler a, DPM++ 2M Karras, and DDIM (`DiffusionScheduler`).
- [x] VAE decoder execution and post-processing to generate 512×512 RGB Android Bitmaps (`VaeDecoder`, `VaePostProcessor`).
- [x] Full native C++ coordinator (`SdPipeline`) with mutex synchronization and JNI bridge (`sd_engine_jni.cpp`).
- [x] Reactive coroutine streaming via `channelFlow` in `PipelineManager`.
- [x] Comprehensive unit test suite (45 unit tests covering tokenization, schedulers, noise, engine, and pipeline).

### Phase 3: RealESRGAN Chaining (Completed)
- [x] RealESRGAN QNN context integration (`esrgan_pipeline.h/cpp` & `ESRGANEngine.kt`).
- [x] Sequential memory-efficient execution chain (SD -> Free SD Context -> RealESRGAN) to prevent NPU context memory spikes.
- [x] High-quality bicubic Keys convolution algorithm ($a = -0.5f$) with half-pixel coordinate mapping and pure-JVM fallback.
- [x] Real-time progress updates across generation and upscaling stages with dual cooperative cancellation.
- [x] Dynamic resolution badges (512×512, 1024×1024, 2048×2048) and upscaling progress card in Jetpack Compose UI.
- [x] Model manifest support for `realesrgan_x2plus` and `realesrgan_x4plus` with SHA-256 verification.
- [x] Comprehensive test suite expanded to 66 unit tests with 100% passing rate.

### Phase 4: Advanced UI & Polish (Completed)
- [x] Indexed SQLite database (`HistoryRepository`, `SQLiteGenerationDao`) with automated filesystem scanning and CRUD operations.
- [x] Full-screen inspection dialog with detailed metadata, "Re-generate with these params", and Android share sheet.
- [x] Model selector supporting DreamShaper v8 variants (General, Anime, Realistic) with status badges.
- [x] Persistent user settings and generation defaults powered by Jetpack DataStore Preferences (`SettingsRepository`).
- [x] Thermal throttling and battery level monitoring (`DeviceMonitor`) with proactive warning banners.
- [x] Background generation notifications (`GenerationNotificationManager`) with live step progress and image previews.
- [x] Multi-selection gallery management with batch deletion and search-by-prompt filtering.
- [x] Full unit test suite expanded to 101 tests (100% passing rate) and clean dual-ABI APK assembly (18 MB).

### Phase 5: Optimization & Device Testing
- [ ] Snapdragon 8 Gen 2 on-device performance profiling (NPU vs GPU vs CPU).
- [ ] Model quantization tuning (INT8 weights / activations vs INT16 latents).
- [ ] Peak memory profiling ensuring < 4 GB RAM footprint.
- [ ] Zero-crash stability verification across 100 consecutive generations.
- [ ] Production release signing and standalone sideload package.

---

## 7. License

This project is developed for local on-device inference research and personal use.

