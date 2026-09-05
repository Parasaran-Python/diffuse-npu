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
}
