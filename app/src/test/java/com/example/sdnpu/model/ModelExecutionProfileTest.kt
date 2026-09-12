package com.example.sdnpu.model

import com.example.sdnpu.engine.npu.ModelPrecision
import com.example.sdnpu.engine.npu.TensorLayout
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ModelExecutionProfileTest {

    @Test
    fun testSd15QnnPrecompiledProfileDefaults() {
        val profile = ModelExecutionProfile.SD15_QNN_PRECOMPILED
        assertEquals("sd15_qnn_npu", profile.modelId)
        assertEquals(ModelPrecision.UINT16, profile.precision)
        assertEquals(TensorLayout.NHWC, profile.layout)
        assertTrue(profile.isPrecompiledContext)

        assertEquals(0.00093035854f, profile.textEncoderQuant.scale, 1e-7f)
        assertEquals(30063, profile.textEncoderQuant.zeroPoint)

        assertEquals(0.00024176309f, profile.unetLatentQuant.scale, 1e-7f)
        assertEquals(33983, profile.unetLatentQuant.zeroPoint)

        assertEquals(0.014770733f, profile.unetTimestepQuant.scale, 1e-7f)
        assertEquals(0, profile.unetTimestepQuant.zeroPoint)

        assertEquals(0.0009331561f, profile.unetTextEmbQuant.scale, 1e-7f)
        assertEquals(30103, profile.unetTextEmbQuant.zeroPoint)

        assertEquals(0.00018817355f, profile.unetOutLatentQuant.scale, 1e-7f)
        assertEquals(32340, profile.unetOutLatentQuant.zeroPoint)

        assertEquals(0.00034003708f, profile.vaeLatentQuant.scale, 1e-7f)
        assertEquals(34382, profile.vaeLatentQuant.zeroPoint)

        assertEquals(0.000015259022f, profile.vaeImageQuant.scale, 1e-7f)
        assertEquals(0, profile.vaeImageQuant.zeroPoint)
    }

    @Test
    fun testStandardFp16NchwProfileDefaults() {
        val profile = ModelExecutionProfile.STANDARD_FP16_NCHW
        assertEquals(ModelPrecision.FP16, profile.precision)
        assertEquals(TensorLayout.NCHW, profile.layout)
        assertFalse(profile.isPrecompiledContext)
        assertEquals(1.0f, profile.textEncoderQuant.scale, 1e-7f)
        assertEquals(0, profile.textEncoderQuant.zeroPoint)
    }

    @Test
    fun testFromModelDirectoryWithoutMetadataFallsBack() {
        val emptyDir = File(System.getProperty("java.io.tmpdir"), "empty_profile_test_${System.currentTimeMillis()}")
        emptyDir.mkdirs()
        try {
            val qnnProfile = ModelExecutionProfile.fromModelDirectory(emptyDir, "sd15_qnn_npu")
            assertEquals(ModelPrecision.UINT16, qnnProfile.precision)
            assertEquals(TensorLayout.NHWC, qnnProfile.layout)

            val turboProfile = ModelExecutionProfile.fromModelDirectory(emptyDir, "sdturbo")
            assertEquals("sdturbo", turboProfile.modelId)
            assertEquals(ModelPrecision.FP16, turboProfile.precision)
            assertEquals(TensorLayout.NCHW, turboProfile.layout)
        } finally {
            emptyDir.deleteRecursively()
        }
    }

    @Test
    fun testFromModelDirectoryWithMetadataJsonParsesSuccessfully() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "metadata_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val metadataJson = """
            {
              "model_id": "test_npu_custom",
              "runtime": "qnn",
              "precision": "w8a16",
              "chipset_attributes": {
                "name": "qualcomm-qcs8550-proxy"
              },
              "model_files": {
                "unet.onnx": {
                  "inputs": {
                    "latent": {
                      "shape": [1, 64, 64, 4],
                      "quantization_parameters": {
                        "scale": 0.00025,
                        "zero_point": 34000
                      }
                    },
                    "timestep": {
                      "quantization_parameters": {
                        "scale": 0.015,
                        "zero_point": 0
                      }
                    },
                    "text_emb": {
                      "quantization_parameters": {
                        "scale": 0.00095,
                        "zero_point": 30100
                      }
                    }
                  },
                  "outputs": {
                    "output_latent": {
                      "quantization_parameters": {
                        "scale": 0.00019,
                        "zero_point": 32000
                      }
                    }
                  }
                },
                "text_encoder.onnx": {
                  "outputs": {
                    "text_embedding": {
                      "quantization_parameters": {
                        "scale": 0.00094,
                        "zero_point": 30000
                      }
                    }
                  }
                },
                "vae.onnx": {
                  "inputs": {
                    "latent": {
                      "quantization_parameters": {
                        "scale": 0.00035,
                        "zero_point": 34500
                      }
                    }
                  },
                  "outputs": {
                    "image": {
                      "quantization_parameters": {
                        "scale": 0.000016,
                        "zero_point": 0
                      }
                    }
                  }
                }
              }
            }
            """.trimIndent()

            val metadataFile = File(tempDir, "metadata.json")
            metadataFile.writeText(metadataJson)

            val profile = ModelExecutionProfile.fromModelDirectory(tempDir)
            assertEquals("test_npu_custom", profile.modelId)
            assertEquals(ModelPrecision.UINT16, profile.precision)
            assertEquals(TensorLayout.NHWC, profile.layout)
            assertEquals("qualcomm-qcs8550-proxy", profile.targetHardware)
            assertTrue(profile.isPrecompiledContext)

            assertEquals(0.00025f, profile.unetLatentQuant.scale, 1e-7f)
            assertEquals(34000, profile.unetLatentQuant.zeroPoint)

            assertEquals(0.00094f, profile.textEncoderQuant.scale, 1e-7f)
            assertEquals(30000, profile.textEncoderQuant.zeroPoint)

            assertEquals(0.00035f, profile.vaeLatentQuant.scale, 1e-7f)
            assertEquals(34500, profile.vaeLatentQuant.zeroPoint)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testFromModelDirectoryWithCorruptJsonRecoversGracefully() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "corrupt_metadata_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val metadataFile = File(tempDir, "metadata.json")
            metadataFile.writeText("{ this is definitely not valid json }")

            val profile = ModelExecutionProfile.fromModelDirectory(tempDir, "sdturbo")
            assertEquals("sdturbo", profile.modelId)
            assertEquals(ModelPrecision.FP16, profile.precision)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
