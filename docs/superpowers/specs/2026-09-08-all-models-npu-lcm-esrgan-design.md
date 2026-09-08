# All Models on NPU: DreamShaper LCM & RealESRGAN Hardware Acceleration Design Spec

## Goal
Make **all image generation and super-resolution upscaling models** in the app run genuinely on the Qualcomm Snapdragon 8 Gen 2 Hexagon v73 HTP NPU (via Microsoft ONNX Runtime Mobile with Qualcomm QNN Execution Provider and Android NNAPI fallback), eliminating all CPU simulation mocks, with verified public 1-click Hugging Face download presets.

---

## 1. Subsystem A: `LcmScheduler` & Dynamic `OnnxDiffusionEngine`

### Problem Statement
Currently, `OnnxDiffusionEngine.kt` only supports SD-Turbo via `SdTurboScheduler.kt` (1–4 step Euler trailing schedule). The DreamShaper models in `SDEngine.kt` were mapped to a C++ CPU simulation stub (`std::sin()`). Running standard SD 1.5 with 20–50 steps and dual-pass Classifier-Free Guidance (CFG) is too slow on mobile hardware (~60–90 seconds per image), whereas **Latent Consistency Models (LCM)** distill DreamShaper into **4 to 8 steps** with single-pass inference suitable for mobile NPUs.

### Design Details
1. **Mathematical `LcmScheduler` (`app/src/main/java/com/example/sdnpu/engine/LcmScheduler.kt`)**:
   - **Hyperparameters**:
     - `NUM_TRAIN_TIMESTEPS = 1000`
     - `BETA_START = 0.00085`
     - `BETA_END = 0.012`
     - `ORIGINAL_INFERENCE_STEPS = 50`
     - `TIMESTEP_SCALING = 10.0f`
     - `SIGMA_DATA = 0.5f`
   - **Timestep Schedule Calculation**:
     - Precomputes $\bar{\alpha}_t = \prod_{i=0}^t (1 - \beta_i)$ using scaled linear schedule.
     - For $N$ steps (default 4, configurable 2–8):
       - $k = 1000 / 50 = 20$
       - Original timesteps: $[1, 2, \dots, 50] \times 20 - 1 = [19, 39, \dots, 999]$ (descending: $[999, 979, \dots, 19]$)
       - Spaced sample indices: $\lfloor \text{linspace}(0, 50, N, \text{endpoint}=\text{false}) \rfloor$
       - Timesteps: $[999, 759, 499, 259]$ (for 4 steps)
   - **Boundary Condition Scalings**:
     $$c_{skip}(t) = \frac{0.25f}{(10.0f \cdot t)^2 + 0.25f}, \quad c_{out}(t) = \frac{10.0f \cdot t}{\sqrt{(10.0f \cdot t)^2 + 0.25f}}$$
   - **Denoising Step Calculation**:
     $$\hat{x}_0 = \frac{x_t - \sqrt{1 - \bar{\alpha}_t} \cdot \epsilon_\theta}{\sqrt{\bar{\alpha}_t}}$$
     $$denoised = c_{out}(t) \cdot \hat{x}_0 + c_{skip}(t) \cdot x_t$$
     If step $i < N - 1$ (non-final step):
       $$x_{prev} = \sqrt{\bar{\alpha}_{prev}} \cdot denoised + \sqrt{1 - \bar{\alpha}_{prev}} \cdot z, \quad z \sim \mathcal{N}(0, I)$$
     If step $i == N - 1$ (final step):
       $$x_{prev} = denoised$$

2. **Sinusoidal Guidance Conditioning (`timestep_cond`)**:
   - LCM models accept guidance scale embedding `timestep_cond` of dimension 256.
   - For guidance scale $w$ (default 1.0f):
     - Compute sinusoidal positional embedding of $(w - 1.0f) \times 1000.0f$.
     - Bind as `FloatBuffer` or `FloatArray` of shape `[1, 256]` when `unetSession.inputNames.contains("timestep_cond")`.

3. **Engine Integration in `OnnxDiffusionEngine.kt`**:
   - Inspects `params.modelId`:
     - If `sdturbo`: uses `SdTurboScheduler`.
     - If `dreamshaper*` or `lcm*`: uses `LcmScheduler`.
   - Dynamic session inputs:
     - Handles `sample` / `latent_sample`.
     - Handles `timestep` (INT64 / FLOAT / FLOAT16).
     - Handles `encoder_hidden_states` / `context`.
     - Handles optional `timestep_cond` and `attention_mask`.
   - Retains sequential execution with `.use { }` to guarantee zero native memory leaks.

---

## 2. Subsystem B: `OnnxEsrganEngine` (RealESRGAN Super-Resolution on NPU)

### Problem Statement
In `ESRGANEngine.kt`, upscaling currently falls back to `upscaleBicubicJvm` on the CPU because the C++ native QNN loader had stubbed bindings.

### Design Details
1. **`OnnxEsrganEngine.kt` (`app/src/main/java/com/example/sdnpu/engine/OnnxEsrganEngine.kt`)**:
   - Uses Microsoft ONNX Runtime Mobile with multi-tier execution providers (Tier 1: QNN HTP, Tier 2: NNAPI, Tier 3: CPU multi-threading).
   - Loads `realesrgan_x2plus/model.onnx` or `realesrgan_x4plus/model.onnx` (FP16 weights, ~33.6 MB).
   - **Input Pre-processing**:
     - Converts ARGB 512x512 byte array into planar RGB float buffer `[1, 3, 512, 512]` in range `[0.0, 1.0]`.
   - **Inference**:
     - Runs model forward pass on Qualcomm NPU.
     - Produces output tensor `[1, 3, 512 * scale, 512 * scale]` (1024x1024 for 2x, 2048x2048 for 4x).
   - **Output Post-processing**:
     - Converts planar RGB float output back into 32-bit ARGB ByteArray.
2. **Fallback Integration in `ESRGANEngine.kt`**:
   - Check if `model.onnx` exists in the local model directory.
   - If present, delegate to `OnnxEsrganEngine.upscale()`.
   - If absent, fallback to `upscaleBicubicJvm` with informative log.

---

## 3. Subsystem C: Model Manifests, Verified Hugging Face Presets & UI

### Problem Statement
The app currently shows models with empty URLs (`defaultUrl = ""`) and legacy variants (`anime`, `realistic`) that have no working NPU weights.

### Design Details
1. **Verified Public Model Presets in `ModelDownloadPresets` (`ModelManifest.kt`)**:
   - **`sdturbo`**:
     - URL: `https://huggingface.co/microsoft/sd-turbo-webnn/resolve/main/`
     - Components: `text_encoder.onnx`, `unet.onnx`, `vae_decoder.onnx` (~2.6 GB total FP16).
   - **`dreamshaper_v8_base` / `dreamshaper_lcm`**:
     - URL: `https://huggingface.co/softwareweaver/LCM_Dreamshaper_v7_Olive_Onnx/resolve/main/`
     - Components: `text_encoder.onnx`, `unet.onnx`, `vae_decoder.onnx` (~2.06 GB total FP16, single files without `.data` fragmentation).
   - **`realesrgan_x2plus`**:
     - URL: `https://huggingface.co/tamnvcc/RealESRGAN-onnx/resolve/main/onnx/RealESRGAN_x2plus.fp16.onnx` (~33.6 MB).
   - **`realesrgan_x4plus`**:
     - URL: `https://huggingface.co/tamnvcc/RealESRGAN-onnx/resolve/main/onnx/RealESRGAN_x4plus.fp16.onnx` (~33.6 MB).

2. **Model Variants Consolidation in `ModelVariants` (`ModelManifest.kt`)**:
   - Present the real, verified NPU models:
     - `sdturbo`: "SD-Turbo (ONNX / LCM)" - 1-4 steps, near-instant.
     - `dreamshaper_lcm` (aliased with `dreamshaper_v8_base` for backward compatibility): "DreamShaper (LCM v7)" - 4-8 steps, photorealistic artistic SD 1.5 checkpoint.
   - Cleanly deprecate/remove the non-functional legacy placeholders (`anime`, `realistic`).

---

## 4. Subsystem D: Legacy Native Cleanup

### Problem Statement
The C++ files `unet_denoiser.cpp` and `vae_decoder.cpp` contained placeholder `std::sin()` math loops that simulated generation instead of performing inference.

### Design Details
- Update `SDEngine.kt`:
  - Always route model generation through `OnnxDiffusionEngine`.
  - Remove fallback to simulated native code.
- Keep `QnnNativeBridge` for HTP performance profile configuration (Burst / High Performance / Power Saver).

---

## 5. Verification & Testing Strategy

1. **Unit Tests (TDD)**:
   - `LcmSchedulerTest.kt`:
     - Test discrete timesteps for 1, 4, and 8 inference steps.
     - Test boundary condition scalings ($c_{skip}$ and $c_{out}$).
     - Test epsilon prediction step with known input tensors.
     - Test guidance embedding generation for `timestep_cond`.
   - `OnnxEsrganEngineTest.kt`:
     - Test planar float tensor layout and ARGB conversion.
     - Test graceful fallback when model is absent.
   - `ModelManifestTest.kt`:
     - Validate all model manifests, file component names, and download presets.
   - Run full suite: `./gradlew testDebugUnitTest` (ensure 0 regressions).

2. **Release APK Constraint**:
   - Run `./gradlew :app:assembleRelease`.
   - Verify APK size remains $< 50$ MB.

3. **On-Device Physical Verification**:
   - Install release APK on connected Samsung Galaxy S23 Ultra (`SM-S918B`).
   - Verify app launch and absence of crashes in `adb logcat`.
