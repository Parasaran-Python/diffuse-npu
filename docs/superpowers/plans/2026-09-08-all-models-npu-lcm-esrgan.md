# All Models on NPU: DreamShaper LCM & RealESRGAN Hardware Acceleration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make all image generation (SD-Turbo and DreamShaper LCM) and super-resolution upscaling (RealESRGAN) models in the app execute genuinely on the Qualcomm Hexagon NPU via ONNX Runtime Mobile, eliminating all CPU simulation mocks, with verified public 1-click Hugging Face download presets.

**Architecture:** Extend `OnnxDiffusionEngine` with pluggable schedulers (`SdTurboScheduler` and a new mathematical `LcmScheduler`) and dynamic graph input binding (`timestep_cond`, `attention_mask`). Implement `OnnxEsrganEngine` for NPU-accelerated super-resolution convolution. Consolidate model manifests and download presets to point to verified, single-file FP16 ONNX models on Hugging Face.

**Tech Stack:** Kotlin, Android Jetpack Compose, Microsoft ONNX Runtime Mobile (1.20.0), Qualcomm QNN Execution Provider (HTP v73), Android NNAPI, OkHttp3, JUnit4.

**Spec:** `docs/superpowers/specs/2026-09-08-all-models-npu-lcm-esrgan-design.md`

## Global Constraints
- Target Device: Samsung Galaxy S23 Ultra (`SM-S918B`, Snapdragon 8 Gen 2 / SM8550, Hexagon v73 HTP NPU).
- Git Branch: `feat/dreamshaper-npu-support` (STRICT: Do NOT merge to `master`).
- Release APK size constraint: Release APK must remain `< 50MB` (enforced via Proguard/R8 shrinking).
- Preservation: All 179 existing unit tests must remain passing with 0 regressions.
- No Native Memory Leaks: Every `OrtSession` and `SessionOptions` must be closed natively via `.use { }`.

---

### Task 1: Mathematical `LcmScheduler` with Sinusoidal Guidance Embeddings

**Files:**
- Create: `app/src/main/java/com/example/sdnpu/engine/LcmScheduler.kt`
- Test: `app/src/test/java/com/example/sdnpu/engine/LcmSchedulerTest.kt`

**Interfaces:**
- Consumes: None (pure Kotlin mathematics).
- Produces:
  ```kotlin
  object LcmScheduler {
      data class Schedule(val timesteps: LongArray, val numInferenceSteps: Int)
      fun getSchedule(numInferenceSteps: Int): Schedule
      fun getBoundaryScalings(timestep: Long): Pair<Float, Float> // Pair(cSkip, cOut)
      fun getGuidanceEmbedding(guidanceScale: Float, embeddingDim: Int = 256): FloatArray
      fun step(
          sample: FloatArray,
          modelOutput: FloatArray,
          stepIndex: Int,
          schedule: Schedule,
          generatorNoise: FloatArray?,
          outSample: FloatArray
      )
  }
  ```

- [ ] **Step 1: Write failing unit tests in `LcmSchedulerTest.kt`**
Create `app/src/test/java/com/example/sdnpu/engine/LcmSchedulerTest.kt`:
```kotlin
package com.example.sdnpu.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LcmSchedulerTest {

    @Test
    fun testScheduleTimestepsDescendingFor4Steps() {
        val schedule = LcmScheduler.getSchedule(4)
        assertEquals(4, schedule.timesteps.size)
        // Check timesteps are strictly descending
        for (i in 0 until schedule.timesteps.size - 1) {
            assertTrue("Timesteps must be descending", schedule.timesteps[i] > schedule.timesteps[i + 1])
        }
        assertEquals(999L, schedule.timesteps[0])
    }

    @Test
    fun testBoundaryConditionScalings() {
        // At t = 0, cSkip -> 1.0, cOut -> 0.0
        val (cSkipZero, cOutZero) = LcmScheduler.getBoundaryScalings(0L)
        assertEquals(1.0f, cSkipZero, 0.001f)
        assertEquals(0.0f, cOutZero, 0.001f)

        // At large t, cSkip -> 0.0, cOut -> 1.0
        val (cSkipLarge, cOutLarge) = LcmScheduler.getBoundaryScalings(999L)
        assertTrue(cSkipLarge < 0.01f)
        assertTrue(cOutLarge > 0.99f)
    }

    @Test
    fun testGuidanceEmbeddingDimension() {
        val emb = LcmScheduler.getGuidanceEmbedding(1.0f, 256)
        assertEquals(256, emb.size)
        // Verify values are bounded in [-1.0, 1.0]
        assertTrue(emb.all { it in -1.0f..1.0f })
    }

    @Test
    fun testStepCalculationFinalStepOmitsNoise() {
        val schedule = LcmScheduler.getSchedule(4)
        val sample = FloatArray(16) { 1.0f }
        val modelOutput = FloatArray(16) { 0.1f }
        val outSample = FloatArray(16)
        val noise = FloatArray(16) { 999.0f } // Extreme noise that should NOT be used on final step

        // Step index 3 is final step for 4-step schedule
        LcmScheduler.step(sample, modelOutput, 3, schedule, noise, outSample)

        // Verify output is finite and did not inject the extreme noise
        assertTrue(outSample.none { abs(it) > 100f })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**
Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.engine.LcmSchedulerTest"`
Expected: FAIL with "Unresolved reference: LcmScheduler"

- [ ] **Step 3: Implement `LcmScheduler.kt`**
Create `app/src/main/java/com/example/sdnpu/engine/LcmScheduler.kt`:
```kotlin
package com.example.sdnpu.engine

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

object LcmScheduler {
    const val NUM_TRAIN_TIMESTEPS = 1000
    const val BETA_START = 0.00085
    const val BETA_END = 0.012
    const val ORIGINAL_INFERENCE_STEPS = 50
    const val TIMESTEP_SCALING = 10.0f
    const val SIGMA_DATA = 0.5f

    data class Schedule(val timesteps: LongArray, val numInferenceSteps: Int)

    val alphasCumprod: FloatArray = FloatArray(NUM_TRAIN_TIMESTEPS).also { cumprod ->
        val startSqrt = sqrt(BETA_START)
        val endSqrt = sqrt(BETA_END)
        var currentAlphaCumprod = 1.0
        for (i in 0 until NUM_TRAIN_TIMESTEPS) {
            val betaSqrt = startSqrt + i.toDouble() * (endSqrt - startSqrt) / (NUM_TRAIN_TIMESTEPS - 1)
            val beta = betaSqrt * betaSqrt
            val alpha = 1.0 - beta
            currentAlphaCumprod *= alpha
            cumprod[i] = currentAlphaCumprod.toFloat()
        }
    }

    fun getSchedule(numInferenceSteps: Int): Schedule {
        val steps = numInferenceSteps.coerceIn(1, ORIGINAL_INFERENCE_STEPS)
        val k = NUM_TRAIN_TIMESTEPS / ORIGINAL_INFERENCE_STEPS // 20
        // lcm_origin_timesteps in descending order: [999, 979, ..., 19]
        val lcmOriginTimesteps = LongArray(ORIGINAL_INFERENCE_STEPS) { idx ->
            ((ORIGINAL_INFERENCE_STEPS - idx) * k - 1).toLong()
        }

        // Evenly spaced indices
        val timesteps = LongArray(steps)
        for (i in 0 until steps) {
            val index = floor((i.toDouble() * ORIGINAL_INFERENCE_STEPS.toDouble()) / steps.toDouble()).toInt()
            timesteps[i] = lcmOriginTimesteps[index.coerceIn(0, ORIGINAL_INFERENCE_STEPS - 1)]
        }
        return Schedule(timesteps, steps)
    }

    fun getBoundaryScalings(timestep: Long): Pair<Float, Float> {
        val scaledTimestep = timestep.toFloat() * TIMESTEP_SCALING
        val sigmaSq = SIGMA_DATA * SIGMA_DATA
        val cSkip = sigmaSq / (scaledTimestep * scaledTimestep + sigmaSq)
        val cOut = scaledTimestep / sqrt(scaledTimestep * scaledTimestep + sigmaSq)
        return Pair(cSkip, cOut)
    }

    fun getGuidanceEmbedding(guidanceScale: Float, embeddingDim: Int = 256): FloatArray {
        val embedding = FloatArray(embeddingDim)
        val w = (guidanceScale - 1.0f) * 1000.0f
        val halfDim = embeddingDim / 2
        val logScale = ln(10000.0) / (halfDim - 1).toDouble()

        for (i in 0 until halfDim) {
            val freq = exp(-i.toDouble() * logScale)
            val angle = w.toDouble() * freq
            embedding[i] = sin(angle).toFloat()
            embedding[i + halfDim] = cos(angle).toFloat()
        }
        return embedding
    }

    fun step(
        sample: FloatArray,
        modelOutput: FloatArray,
        stepIndex: Int,
        schedule: Schedule,
        generatorNoise: FloatArray?,
        outSample: FloatArray
    ) {
        val timestep = schedule.timesteps[stepIndex]
        val prevTimestep = if (stepIndex + 1 < schedule.timesteps.size) {
            schedule.timesteps[stepIndex + 1]
        } else {
            timestep
        }

        val alphaProdT = alphasCumprod[timestep.toInt().coerceIn(0, NUM_TRAIN_TIMESTEPS - 1)]
        val alphaProdTPrev = if (prevTimestep >= 0) {
            alphasCumprod[prevTimestep.toInt().coerceIn(0, NUM_TRAIN_TIMESTEPS - 1)]
        } else {
            1.0f
        }

        val betaProdT = (1.0f - alphaProdT).coerceAtLeast(0f)
        val betaProdTPrev = (1.0f - alphaProdTPrev).coerceAtLeast(0f)

        val sqrtAlphaT = sqrt(alphaProdT)
        val sqrtBetaT = sqrt(betaProdT)
        val sqrtAlphaPrev = sqrt(alphaProdTPrev)
        val sqrtBetaPrev = sqrt(betaProdTPrev)

        val (cSkip, cOut) = getBoundaryScalings(timestep)
        val count = minOf(sample.size, modelOutput.size, outSample.size)
        val isFinalStep = stepIndex == schedule.numInferenceSteps - 1

        for (i in 0 until count) {
            // 1. Predicted original sample x_0
            val predX0 = (sample[i] - sqrtBetaT * modelOutput[i]) / sqrtAlphaT
            // 2. Denoised estimate
            val denoised = cOut * predX0 + cSkip * sample[i]

            // 3. Noise injection for intermediate steps
            if (!isFinalStep && generatorNoise != null && i < generatorNoise.size) {
                outSample[i] = sqrtAlphaPrev * denoised + sqrtBetaPrev * generatorNoise[i]
            } else {
                outSample[i] = denoised
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**
Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.engine.LcmSchedulerTest"`
Expected: PASS (4 tests passing)

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/example/sdnpu/engine/LcmScheduler.kt app/src/test/java/com/example/sdnpu/engine/LcmSchedulerTest.kt
git commit -m "feat(engine): implement mathematical LcmScheduler with boundary condition scalings and guidance embeddings"
```

---

### Task 2: Dynamic Multi-Architecture Support in `OnnxDiffusionEngine` & Native Cleanup

**Files:**
- Modify: `app/src/main/java/com/example/sdnpu/engine/OnnxDiffusionEngine.kt`
- Modify: `app/src/main/java/com/example/sdnpu/engine/SDEngine.kt`
- Test: `app/src/test/java/com/example/sdnpu/engine/OnnxDiffusionEngineTest.kt`

**Interfaces:**
- Consumes: `LcmScheduler`, `SdTurboScheduler`, `ClipTokenizer`
- Produces:
  ```kotlin
  OnnxDiffusionEngine.generate(params: GenerationParams, modelDir: File, onStep: ((Int, Int) -> Unit)?): ByteArray
  ```

- [ ] **Step 1: Write failing test in `OnnxDiffusionEngineTest.kt` for LCM schedule branch & input binding**
Add to `app/src/test/java/com/example/sdnpu/engine/OnnxDiffusionEngineTest.kt`:
```kotlin
@Test
fun testIsLcmModelDetection() {
    assertTrue(OnnxDiffusionEngine.isLcmModel("dreamshaper_v8_base"))
    assertTrue(OnnxDiffusionEngine.isLcmModel("dreamshaper_lcm"))
    assertTrue(OnnxDiffusionEngine.isLcmModel("lcm_sd15"))
    org.junit.Assert.assertFalse(OnnxDiffusionEngine.isLcmModel("sdturbo"))
}
```

- [ ] **Step 2: Run test to verify it fails**
Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.engine.OnnxDiffusionEngineTest.testIsLcmModelDetection"`
Expected: FAIL with "Unresolved reference: isLcmModel"

- [ ] **Step 3: Implement dynamic scheduler branching and inputs in `OnnxDiffusionEngine.kt`**
1. Add helper `fun isLcmModel(modelId: String): Boolean = modelId.startsWith("dreamshaper") || modelId.contains("lcm")`
2. In `generate(...)`:
   - If `isLcmModel(params.modelId)`: use `LcmScheduler.getSchedule(params.steps)`
   - Else: use `SdTurboScheduler.getSchedule(params.steps)`
3. In `denoiseLoop(...)`:
   - Check if `unetSession.inputNames.contains("timestep_cond")`
   - If present: create `timestepCondTensor` using `LcmScheduler.getGuidanceEmbedding(params.cfgScale)` and pass into `inputs` map, closing it in `finally`.
4. In `SDEngine.kt`:
   - Remove the fallback to `nativeGenerateSd()` (which had `std::sin()` mock).
   - `SDEngine.generate()` routes directly to `OnnxDiffusionEngine.generate()`.

- [ ] **Step 4: Run tests to verify they pass**
Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.engine.OnnxDiffusionEngineTest"`
Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/example/sdnpu/engine/OnnxDiffusionEngine.kt app/src/main/java/com/example/sdnpu/engine/SDEngine.kt app/src/test/java/com/example/sdnpu/engine/OnnxDiffusionEngineTest.kt
git commit -m "feat(engine): add dynamic LCM scheduler dispatch, guidance tensor binding, and remove CPU simulation"
```

---

### Task 3: Hardware-Accelerated Super-Resolution (`OnnxEsrganEngine`) & `ESRGANEngine` Integration

**Files:**
- Create: `app/src/main/java/com/example/sdnpu/engine/OnnxEsrganEngine.kt`
- Modify: `app/src/main/java/com/example/sdnpu/engine/ESRGANEngine.kt`
- Test: `app/src/test/java/com/example/sdnpu/engine/OnnxEsrganEngineTest.kt`
- Modify: `app/src/test/java/com/example/sdnpu/engine/ESRGANEngineTest.kt`

**Interfaces:**
- Consumes: `OrtEnvironment`, `OrtSession`, `ESRGANEngine.upscaleBicubicJvm`
- Produces:
  ```kotlin
  object OnnxEsrganEngine {
      fun upscale(
          inputRgba: ByteArray,
          inWidth: Int,
          inHeight: Int,
          scale: Int,
          modelDir: File,
          onProgress: ((Float) -> Unit)? = null
      ): ByteArray
  }
  ```

- [ ] **Step 1: Write failing test in `OnnxEsrganEngineTest.kt`**
Create `app/src/test/java/com/example/sdnpu/engine/OnnxEsrganEngineTest.kt`:
```kotlin
package com.example.sdnpu.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class OnnxEsrganEngineTest {

    @Test
    fun testRgbaToPlanarFloatAndBack() {
        val width = 2
        val height = 2
        val rgba = byteArrayOf(
            255.toByte(), 0, 0, 255.toByte(),
            0, 255.toByte(), 0, 255.toByte(),
            0, 0, 255.toByte(), 255.toByte(),
            128.toByte(), 128.toByte(), 128.toByte(), 255.toByte()
        )
        val floats = OnnxEsrganEngine.rgbaToPlanarRgbFloats(rgba, width, height)
        assertEquals(3 * width * height, floats.size)
        // Red channel of first pixel should be 1.0f
        assertEquals(1.0f, floats[0], 0.01f)

        val reconstructed = OnnxEsrganEngine.planarRgbFloatsToRgba(floats, width, height)
        assertEquals(rgba.size, reconstructed.size)
        assertEquals(rgba[0], reconstructed[0])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**
Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.engine.OnnxEsrganEngineTest"`
Expected: FAIL with "Unresolved reference: OnnxEsrganEngine"

- [ ] **Step 3: Implement `OnnxEsrganEngine.kt` and wire into `ESRGANEngine.kt`**
1. Create `app/src/main/java/com/example/sdnpu/engine/OnnxEsrganEngine.kt`:
   - Methods: `rgbaToPlanarRgbFloats`, `planarRgbFloatsToRgba`, `createSession(env, modelFile)`, `upscale(...)`.
   - Uses `OnnxDiffusionEngine.createSession(...)` multi-tier execution provider strategy (QNN HTP -> NNAPI -> CPU).
2. Update `ESRGANEngine.kt`:
   - In `upscale(...)`:
     ```kotlin
     val onnxFile = modelDir?.let { dir ->
         listOf(File(dir, "model.onnx"), File(dir, "RealESRGAN_x${scale}plus.fp16.onnx")).firstOrNull { it.exists() && it.length() > 0 }
     }
     if (onnxFile != null) {
         try {
             return OnnxEsrganEngine.upscale(inputRgba, inWidth, inHeight, scale, modelDir, onProgress)
         } catch (t: Throwable) {
             Log.w("ESRGANEngine", "ONNX NPU upscaling failed, falling back to bicubic", t)
         }
     }
     return upscaleBicubicJvm(inputRgba, inWidth, inHeight, scale, onProgress)
     ```

- [ ] **Step 4: Run tests to verify they pass**
Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.engine.*"`
Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/example/sdnpu/engine/OnnxEsrganEngine.kt app/src/main/java/com/example/sdnpu/engine/ESRGANEngine.kt app/src/test/java/com/example/sdnpu/engine/OnnxEsrganEngineTest.kt
git commit -m "feat(engine): implement OnnxEsrganEngine for NPU-accelerated super-resolution"
```

---

### Task 4: Public Model Manifests, Verified Hugging Face Presets, UI Consolidation & On-Device Deployment

**Files:**
- Modify: `app/src/main/java/com/example/sdnpu/model/ModelManifest.kt`
- Modify: `app/src/main/java/com/example/sdnpu/model/ModelManager.kt`
- Modify: `app/src/main/java/com/example/sdnpu/ui/screens/ModelDownloadDialog.kt`
- Modify: `app/src/main/java/com/example/sdnpu/ui/screens/GenerateScreen.kt`
- Test: `app/src/test/java/com/example/sdnpu/model/ModelManifestTest.kt`
- Test: `app/src/test/java/com/example/sdnpu/model/ModelManagerTest.kt`

**Interfaces:**
- Consumes: `ModelDownloadPresets`, `ModelVariants`, `ModelManager`
- Produces:
  - Verified presets for:
    - `sdturbo`: `https://huggingface.co/microsoft/sd-turbo-webnn/resolve/main/`
    - `dreamshaper_v8_base`: `https://huggingface.co/softwareweaver/LCM_Dreamshaper_v7_Olive_Onnx/resolve/main/`
    - `realesrgan_x2plus`: `https://huggingface.co/tamnvcc/RealESRGAN-onnx/resolve/main/onnx/`
    - `realesrgan_x4plus`: `https://huggingface.co/tamnvcc/RealESRGAN-onnx/resolve/main/onnx/`

- [ ] **Step 1: Write unit tests in `ModelManifestTest.kt`**
Add tests verifying:
1. `ModelManifest.dreamshaper_v8_base()` components are `.onnx`.
2. `ModelDownloadPresets.PRESETS` contains non-blank URLs for all presets.
3. `ModelVariants.getSdVariants()` returns working NPU models.

- [ ] **Step 2: Run test to verify it fails**
Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.model.ModelManifestTest"`
Expected: FAIL (empty URL check fails)

- [ ] **Step 3: Update `ModelManifest.kt`, `ModelManager.kt`, and UI screens**
1. In `ModelManifest.kt`:
   - Update `dreamshaperV8Base` components to `text_encoder.onnx`, `unet.onnx`, `vae_decoder.onnx`.
   - Update `realesrganX2Plus` and `realesrganX4Plus` component to `model.onnx` (and candidate `RealESRGAN_x2plus.fp16.onnx`).
   - In `ModelDownloadPresets`: configure verified public URLs.
   - In `ModelVariants`: consolidate to `sdturbo` and `dreamshaper_v8_base`.
2. In `ModelManager.kt`:
   - Update candidate URLs so `model.onnx` matches `RealESRGAN_x${scale}plus.fp16.onnx`.
   - Check presence of `.onnx` files in `isModelAvailable()` and `isRealESRGANAvailable()`.
3. In `ModelDownloadDialog.kt`:
   - Include RealESRGAN presets if upscaler is selected.

- [ ] **Step 4: Run full test suite & assemble release APK**
1. Run `./gradlew testDebugUnitTest` and verify 100% tests pass.
2. Run `./gradlew :app:assembleRelease` and verify release APK size is `< 50MB`.

- [ ] **Step 5: Deploy to connected Samsung Galaxy S23 Ultra**
```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.example.sdnpu/.MainActivity
adb logcat -d | grep -i "sdnpu" | tail -n 20
```

- [ ] **Step 6: Commit and Push**
```bash
git add app/src/main/java/com/example/sdnpu/model/ app/src/main/java/com/example/sdnpu/ui/ app/src/test/java/com/example/sdnpu/
git commit -m "feat(ui,model): integrate verified Hugging Face ONNX presets and consolidate NPU model variants"
git push origin feat/dreamshaper-npu-support
```
