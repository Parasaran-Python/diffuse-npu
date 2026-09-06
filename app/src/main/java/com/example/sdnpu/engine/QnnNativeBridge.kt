package com.example.sdnpu.engine

object QnnNativeBridge {
    private var isNativeLoaded = false

    init {
        try {
            System.loadLibrary("sdnpu_engine")
            isNativeLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            isNativeLoaded = false
        }
    }

    fun isLibraryLoaded(): Boolean = isNativeLoaded

    external fun nativeInitBackend(backendType: Int): Int
    external fun nativeGetBackendStatus(): BackendStatus
    external fun nativeRunBenchmarkDummy(iterations: Int): Float
    external fun nativeReleaseBackend()
    external fun nativeSetHtpPerformanceProfile(profileOrdinal: Int): Boolean

    fun setHtpPerformanceProfile(profileOrdinal: Int): Boolean {
        return if (isLibraryLoaded()) nativeSetHtpPerformanceProfile(profileOrdinal) else true
    }

    fun setHtpPerformanceProfile(profile: com.example.sdnpu.model.HtpPowerProfile): Boolean {
        return setHtpPerformanceProfile(profile.ordinal)
    }
}
