package com.example.sdnpu.pipeline

import android.graphics.Bitmap
import com.example.sdnpu.data.GenerationEntity
import com.example.sdnpu.data.HistoryRepository
import com.example.sdnpu.engine.ESRGANEngine
import com.example.sdnpu.engine.SDEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater

class PipelineManager(
    private val modelsDir: File = File(System.getProperty("java.io.tmpdir"), "models"),
    private val outputDir: File = File(System.getProperty("java.io.tmpdir"), "generations"),
    var historyRepository: HistoryRepository? = null,
    private val secondaryModelsDir: File? = null
) {
    fun cancel() {
        SDEngine.cancel()
        ESRGANEngine.cancel()
    }

    fun resolveModelDirectory(modelId: String, preferredBase: File = this.modelsDir): File {
        val requiredComponents = listOf("text_encoder", "unet", "vae_decoder")
        val dirs = listOfNotNull(preferredBase, if (preferredBase == this.modelsDir) secondaryModelsDir else null)
        for (dir in dirs) {
            val mDir = File(dir, modelId)
            val allPresent = requiredComponents.all { comp ->
                File(mDir, "$comp.onnx").exists() || File(File(mDir, comp), "model.onnx").exists()
            }
            if (allPresent) {
                return dir
            }
        }
        return preferredBase
    }

    fun validateModelAvailability(modelId: String, modelsDir: File = this.modelsDir): Result<Unit> {
        val dirsToTry = listOfNotNull(modelsDir, if (modelsDir == this.modelsDir) secondaryModelsDir else null)
        val requiredComponents = listOf("text_encoder", "unet", "vae_decoder")
        var bestMissing = listOf<String>()
        for (dir in dirsToTry) {
            val modelDir = File(dir, modelId)
            val missing = requiredComponents.filter { comp ->
                val flat = File(modelDir, "$comp.onnx")
                val nested = File(File(modelDir, comp), "model.onnx")
                !flat.exists() && !nested.exists()
            }
            if (missing.isEmpty()) {
                return Result.success(Unit)
            }
            bestMissing = missing.map { "$it.onnx" }
        }
        return Result.failure(IllegalStateException("Model '$modelId' components not found (missing: ${bestMissing.joinToString(", ")}). Please download in Settings or sideload via ADB."))
    }

    fun runGeneration(params: GenerationParams): Flow<PipelineState> = channelFlow {
        android.util.Log.i("PipelineManager", "runGeneration starting: prompt='${params.prompt}', modelId='${params.modelId}'")
        val validation = params.validate()
        if (!validation.isValid) {
            val err = validation.errorMessage ?: "Invalid parameters"
            android.util.Log.e("PipelineManager", "Validation error: $err")
            send(PipelineState.Error(err))
            return@channelFlow
        }

        val modelValidation = validateModelAvailability(params.modelId, modelsDir)
        if (modelValidation.isFailure) {
            val err = modelValidation.exceptionOrNull()?.message ?: "Model '${params.modelId}' not found"
            android.util.Log.e("PipelineManager", "Model availability error: $err")
            send(PipelineState.Error(err))
            return@channelFlow
        }

        if (!com.example.sdnpu.system.MemoryDiagnostics.isMemorySafeForGeneration(requiredFreeMb = 1000L)) {
            android.util.Log.w("PipelineManager", "Memory unsafe (<1GB free)")
            send(PipelineState.Error("Insufficient system memory available for generation (<1GB free)"))
            return@channelFlow
        }

        try {
            val startTime = System.currentTimeMillis()
            send(PipelineState.LoadingModel(params.modelId))

            val effectiveModelsDir = resolveModelDirectory(params.modelId, modelsDir)
            val baseSeed = params.seed ?: System.currentTimeMillis()
            for (batchIdx in 0 until params.batchCount) {
                val batchSeed = baseSeed + batchIdx
                val batchParams = params.copy(seed = batchSeed)

                // Generate raw RGBA image bytes on IO dispatcher with direct step progress plumbing
                // Note: SD native context is strictly released before this call returns
                val imageBytes = withContext(Dispatchers.IO) {
                    SDEngine.generate(batchParams, effectiveModelsDir) { step, total ->
                        val msg = if (params.batchCount > 1) {
                            "Batch ${batchIdx + 1}/${params.batchCount} - Denoising step $step/$total"
                        } else {
                            "Denoising step $step/$total"
                        }
                        trySend(PipelineState.Generating(step, total, msg))
                    }
                }

                if (params.upscaleMode != UpscaleMode.OFF) {
                    val scale = if (params.upscaleMode == UpscaleMode.X2) 2 else 4
                    val esrganModelDir = listOfNotNull(modelsDir, secondaryModelsDir)
                        .firstOrNull { File(File(it, "realesrgan_x${scale}plus"), "model.bin").exists() }
                        ?.let { File(it, "realesrgan_x${scale}plus") }
                        ?: File(effectiveModelsDir, "realesrgan_x${scale}plus")

                    send(PipelineState.Upscaling(progress = 0f, scale = scale))

                    var lastProgressPercent = 0
                    val upscaledBytes = withContext(Dispatchers.IO) {
                        ESRGANEngine.upscale(
                            inputRgba = imageBytes,
                            inWidth = 512,
                            inHeight = 512,
                            scale = scale,
                            modelDir = esrganModelDir,
                            onProgress = { progress ->
                                val pct = (progress * 100).toInt().coerceIn(0, 100)
                                if (pct > lastProgressPercent) {
                                    lastProgressPercent = pct
                                    trySend(PipelineState.Upscaling(progress = progress, scale = scale))
                                }
                            }
                        )
                    }

                    if (lastProgressPercent < 100) {
                        send(PipelineState.Upscaling(progress = 1.0f, scale = scale))
                    }

                    val upscaledWidth = 512 * scale
                    val upscaledHeight = 512 * scale
                    val imageFile = File(outputDir, "sd_${System.currentTimeMillis()}_${batchIdx}_x${scale}.png")
                    withContext(Dispatchers.IO) {
                        saveRgbaAsPng(upscaledBytes, upscaledWidth, upscaledHeight, imageFile)
                    }

                    val totalDurationMs = System.currentTimeMillis() - startTime
                    historyRepository?.insert(
                        GenerationEntity(
                            prompt = batchParams.prompt,
                            negativePrompt = batchParams.negativePrompt,
                            modelName = batchParams.modelId,
                            imagePath = imageFile.absolutePath,
                            seed = batchSeed,
                            steps = batchParams.steps,
                            cfgScale = batchParams.cfgScale,
                            sampler = batchParams.sampler.ordinal,
                            upscaleMode = scale,
                            timestamp = System.currentTimeMillis(),
                            generationTimeMs = totalDurationMs,
                            width = upscaledWidth,
                            height = upscaledHeight,
                            fileSizeBytes = imageFile.length()
                        )
                    )
                    send(PipelineState.Completed(imageFile, totalDurationMs))
                } else {
                    val imageFile = File(outputDir, "sd_${System.currentTimeMillis()}_$batchIdx.png")
                    withContext(Dispatchers.IO) {
                        saveRgbaAsPng(imageBytes, 512, 512, imageFile)
                    }

                    val totalDurationMs = System.currentTimeMillis() - startTime
                    historyRepository?.insert(
                        GenerationEntity(
                            prompt = batchParams.prompt,
                            negativePrompt = batchParams.negativePrompt,
                            modelName = batchParams.modelId,
                            imagePath = imageFile.absolutePath,
                            seed = batchSeed,
                            steps = batchParams.steps,
                            cfgScale = batchParams.cfgScale,
                            sampler = batchParams.sampler.ordinal,
                            upscaleMode = 0,
                            timestamp = System.currentTimeMillis(),
                            generationTimeMs = totalDurationMs,
                            width = 512,
                            height = 512,
                            fileSizeBytes = imageFile.length()
                        )
                    )
                    send(PipelineState.Completed(imageFile, totalDurationMs))
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            send(PipelineState.Error(e.message ?: "Generation failed"))
        }
    }.buffer(capacity = 128, onBufferOverflow = BufferOverflow.SUSPEND)

    fun generate(params: GenerationParams): Flow<PipelineState> = runGeneration(params)

    fun generateImage(params: GenerationParams): Flow<PipelineState> = runGeneration(params)

    private fun saveRgbaAsPng(bytes: ByteArray, width: Int, height: Int, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        var bitmap: Bitmap? = null
        try {
            bitmap = com.example.sdnpu.system.BitmapPool.acquire(width, height)
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            FileOutputStream(outputFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
        } catch (e: Throwable) {
            // Fallback for JVM unit tests where Bitmap native implementation is not mocked
            writePngFallback(bytes, width, height, outputFile)
        } finally {
            if (bitmap != null) {
                com.example.sdnpu.system.BitmapPool.release(bitmap)
            }
        }
    }

    private fun writePngFallback(bytes: ByteArray, width: Int, height: Int, outputFile: File) {
        val rawScanlines = ByteArray(height * (1 + width * 4))
        for (y in 0 until height) {
            val rowStart = y * (1 + width * 4)
            rawScanlines[rowStart] = 0 // Filter type 0 (None)
            System.arraycopy(bytes, y * width * 4, rawScanlines, rowStart + 1, width * 4)
        }

        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        val compressedIdat = try {
            deflater.setInput(rawScanlines)
            deflater.finish()
            val baos = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                baos.write(buffer, 0, count)
            }
            baos.toByteArray()
        } finally {
            deflater.end()
        }

        FileOutputStream(outputFile).use { fos ->
            val dos = DataOutputStream(fos)
            // PNG signature
            dos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

            // IHDR chunk
            val ihdrData = ByteArrayOutputStream()
            val ihdrDos = DataOutputStream(ihdrData)
            ihdrDos.writeInt(width)
            ihdrDos.writeInt(height)
            ihdrDos.writeByte(8) // 8 bits per channel
            ihdrDos.writeByte(6) // RGBA color type
            ihdrDos.writeByte(0) // compression
            ihdrDos.writeByte(0) // filter
            ihdrDos.writeByte(0) // interlace
            val ihdrBytes = ihdrData.toByteArray()

            writeChunk(dos, "IHDR", ihdrBytes)
            writeChunk(dos, "IDAT", compressedIdat)
            writeChunk(dos, "IEND", ByteArray(0))
        }
    }

    private fun writeChunk(dos: DataOutputStream, type: String, data: ByteArray) {
        dos.writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        dos.write(typeBytes)
        dos.write(data)

        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        dos.writeInt(crc.value.toInt())
    }
}
