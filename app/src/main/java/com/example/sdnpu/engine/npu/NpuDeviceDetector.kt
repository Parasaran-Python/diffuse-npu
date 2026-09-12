package com.example.sdnpu.engine.npu

import android.os.Build
import com.example.sdnpu.engine.BackendType

enum class SocVendor(val displayName: String) {
    QUALCOMM("Qualcomm Snapdragon"),
    MEDIATEK("MediaTek Dimensity"),
    GOOGLE("Google Tensor"),
    SAMSUNG("Samsung Exynos"),
    UNKNOWN("Generic / Other")
}

data class DeviceNpuInfo(
    val vendor: SocVendor,
    val socModel: String,
    val hardwareName: String,
    val supportedBackendTypes: List<BackendType>,
    val htpVersion: String? = null // e.g., "v73" for Snapdragon 8 Gen 2
)

object NpuDeviceDetector {
    private fun getSystemProperty(propName: String): String {
        return try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val getMethod = systemPropertiesClass.getMethod("get", String::class.java, String::class.java)
            (getMethod.invoke(null, propName, "") as? String).orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    fun detect(
        buildHardware: String? = null,
        buildManufacturer: String? = null,
        buildSocManufacturer: String? = null,
        buildSocModel: String? = null
    ): DeviceNpuInfo {
        val hardware = (buildHardware ?: runCatching { Build.HARDWARE }.getOrNull()).orEmpty()
        val manufacturer = (buildManufacturer ?: runCatching { Build.MANUFACTURER }.getOrNull()).orEmpty()
        val socManufacturer = (buildSocManufacturer ?: runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else ""
        }.getOrNull()).orEmpty()
        val socModel = (buildSocModel ?: runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
        }.getOrNull()).orEmpty()

        val roSocManufacturer = getSystemProperty("ro.soc.manufacturer")
        val roBoardPlatform = getSystemProperty("ro.board.platform")
        val roChipName = getSystemProperty("ro.hardware.chipname")

        val manufacturerCombined = "$manufacturer $socManufacturer $roSocManufacturer".lowercase()
        val platformCombined = "$hardware $socModel $roBoardPlatform $roChipName".lowercase()

        val vendor = when {
            manufacturerCombined.contains("qualcomm") ||
                    platformCombined.contains("qcom") ||
                    platformCombined.contains("sm8") ||
                    platformCombined.contains("sm7") ||
                    platformCombined.contains("kalama") ||
                    platformCombined.contains("taro") ||
                    platformCombined.contains("pineapple") ||
                    platformCombined.contains("sun") -> SocVendor.QUALCOMM

            manufacturerCombined.contains("mediatek") ||
                    platformCombined.contains("mt6") ||
                    platformCombined.contains("mt8") ||
                    platformCombined.contains("dimensity") -> SocVendor.MEDIATEK

            manufacturerCombined.contains("google") ||
                    platformCombined.contains("gs101") ||
                    platformCombined.contains("gs201") ||
                    platformCombined.contains("zuma") ||
                    platformCombined.contains("laguna") ||
                    platformCombined.contains("tensor") -> SocVendor.GOOGLE

            manufacturerCombined.contains("samsung") && (platformCombined.contains("exynos") || platformCombined.contains("s5e")) ->
                SocVendor.SAMSUNG

            else -> SocVendor.UNKNOWN
        }

        val htpVersion = if (vendor == SocVendor.QUALCOMM) {
            when {
                platformCombined.contains("sun") || platformCombined.contains("sm8750") -> "v79"
                platformCombined.contains("pineapple") || platformCombined.contains("sm8650") -> "v75"
                platformCombined.contains("kalama") || platformCombined.contains("sm8550") -> "v73"
                platformCombined.contains("taro") || platformCombined.contains("sm8450") -> "v69"
                platformCombined.contains("lahaina") || platformCombined.contains("sm8350") -> "v68"
                else -> "v73" // Default to v73 if unknown Snapdragon 8 series
            }
        } else null

        val supportedBackends = when (vendor) {
            SocVendor.QUALCOMM -> listOf(
                BackendType.QNN_HTP,
                BackendType.NNAPI_NPU,
                BackendType.GPU,
                BackendType.CPU
            )
            SocVendor.MEDIATEK -> listOf(
                BackendType.MEDIATEK_APU,
                BackendType.NNAPI_NPU,
                BackendType.GPU,
                BackendType.CPU
            )
            SocVendor.GOOGLE -> listOf(
                BackendType.GOOGLE_TPU,
                BackendType.NNAPI_NPU,
                BackendType.GPU,
                BackendType.CPU
            )
            SocVendor.SAMSUNG -> listOf(
                BackendType.EXYNOS_NPU,
                BackendType.NNAPI_NPU,
                BackendType.GPU,
                BackendType.CPU
            )
            SocVendor.UNKNOWN -> listOf(
                BackendType.NNAPI_NPU,
                BackendType.GPU,
                BackendType.CPU
            )
        }

        val socModelName = (if (socModel.isNotBlank()) socModel else roBoardPlatform)
            .ifBlank { hardware }

        return DeviceNpuInfo(
            vendor = vendor,
            socModel = socModelName,
            hardwareName = hardware,
            supportedBackendTypes = supportedBackends,
            htpVersion = htpVersion
        )
    }
}
