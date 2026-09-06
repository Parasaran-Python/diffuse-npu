#include <jni.h>
#include "qnn_wrapper.h"
#include "sd_pipeline.h"
#include "esrgan_pipeline.h"

extern "C" {

JNIEXPORT jint JNICALL
Java_com_example_sdnpu_engine_QnnNativeBridge_nativeInitBackend(
    JNIEnv* env,
    jobject /* this */,
    jint backendType) {
    return static_cast<jint>(QnnEngineWrapper::getInstance().initialize(
        static_cast<QnnBackendTarget>(backendType)));
}

JNIEXPORT jobject JNICALL
Java_com_example_sdnpu_engine_QnnNativeBridge_nativeGetBackendStatus(
    JNIEnv* env,
    jobject /* this */) {
    QnnRuntimeInfo info = QnnEngineWrapper::getInstance().getRuntimeInfo();

    jclass statusClass = env->FindClass("com/example/sdnpu/engine/BackendStatus");
    if (!statusClass) {
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(
        statusClass,
        "<init>",
        "(Ljava/lang/String;ZZLjava/lang/String;Ljava/lang/String;)V"
    );
    if (!constructor) {
        env->DeleteLocalRef(statusClass);
        return nullptr;
    }


    jstring backendName = env->NewStringUTF(info.backendName.c_str());
    jstring versionString = env->NewStringUTF(info.versionString.c_str());
    jstring statusMessage = env->NewStringUTF(info.statusMessage.c_str());

    jobject result = env->NewObject(
        statusClass,
        constructor,
        backendName,
        static_cast<jboolean>(info.isHtpAvailable),
        static_cast<jboolean>(info.isLoaded),
        versionString,
        statusMessage
    );

    env->DeleteLocalRef(backendName);
    env->DeleteLocalRef(versionString);
    env->DeleteLocalRef(statusMessage);
    env->DeleteLocalRef(statusClass);

    return result;
}

JNIEXPORT jfloat JNICALL
Java_com_example_sdnpu_engine_QnnNativeBridge_nativeRunBenchmarkDummy(
    JNIEnv* env,
    jobject /* this */,
    jint iterations) {
    return QnnEngineWrapper::getInstance().runBenchmarkDummy(iterations);
}

JNIEXPORT void JNICALL
Java_com_example_sdnpu_engine_QnnNativeBridge_nativeReleaseBackend(
    JNIEnv* env,
    jobject /* this */) {
    QnnEngineWrapper::getInstance().release();
}

JNIEXPORT jboolean JNICALL
Java_com_example_sdnpu_engine_SDEngine_nativeLoadSdContext(
    JNIEnv* env,
    jobject /* this */,
    jstring modelDir) {
    if (!modelDir) return JNI_FALSE;
    const char* dirChars = env->GetStringUTFChars(modelDir, nullptr);
    if (!dirChars) return JNI_FALSE;
    std::string dirStr(dirChars);
    env->ReleaseStringUTFChars(modelDir, dirChars);

    bool result = SdPipeline::getInstance().loadContext(dirStr);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_sdnpu_engine_SDEngine_nativeGenerateSd(
    JNIEnv* env,
    jobject /* this */,
    jintArray promptTokens,
    jintArray negTokens,
    jint steps,
    jfloat cfgScale,
    jlong seed,
    jint sampler,
    jobject jCallback) {
    if (!promptTokens || !negTokens) return nullptr;

    jsize promptLen = env->GetArrayLength(promptTokens);
    jint* promptElems = env->GetIntArrayElements(promptTokens, nullptr);
    if (!promptElems) return nullptr;
    std::vector<int32_t> promptVec(promptElems, promptElems + promptLen);
    env->ReleaseIntArrayElements(promptTokens, promptElems, JNI_ABORT);

    jsize negLen = env->GetArrayLength(negTokens);
    jint* negElems = env->GetIntArrayElements(negTokens, nullptr);
    if (!negElems) return nullptr;
    std::vector<int32_t> negVec(negElems, negElems + negLen);
    env->ReleaseIntArrayElements(negTokens, negElems, JNI_ABORT);

    std::function<void(int, int)> progressCb = nullptr;
    if (jCallback) {
        jclass cbClass = env->GetObjectClass(jCallback);
        if (cbClass) {
            jmethodID onStepMethod = env->GetMethodID(cbClass, "onStep", "(II)V");
            if (onStepMethod) {
                progressCb = [env, jCallback, onStepMethod](int step, int total) {
                    env->CallVoidMethod(jCallback, onStepMethod, static_cast<jint>(step), static_cast<jint>(total));
                };
            }
        }
    }

    SamplerAlgorithm samplerAlgo = static_cast<SamplerAlgorithm>(sampler);
    std::vector<uint8_t> outBytes;
    bool ok = SdPipeline::getInstance().generate(
        promptVec,
        negVec,
        static_cast<int>(steps),
        static_cast<float>(cfgScale),
        static_cast<int64_t>(seed),
        samplerAlgo,
        progressCb,
        outBytes
    );

    if (!ok || outBytes.empty()) {
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(outBytes.size()));
    if (!result) return nullptr;

    env->SetByteArrayRegion(result, 0, static_cast<jsize>(outBytes.size()),
                           reinterpret_cast<const jbyte*>(outBytes.data()));
    return result;
}

JNIEXPORT void JNICALL
Java_com_example_sdnpu_engine_SDEngine_nativeCancelSd(
    JNIEnv* /* env */,
    jobject /* this */) {
    SdPipeline::getInstance().requestCancel();
}

JNIEXPORT void JNICALL
Java_com_example_sdnpu_engine_SDEngine_nativeUnloadSdContext(
    JNIEnv* /* env */,
    jobject /* this */) {
    SdPipeline::getInstance().unloadContext();
}

JNIEXPORT jboolean JNICALL
Java_com_example_sdnpu_engine_ESRGANEngine_nativeLoadEsrganContext(
    JNIEnv* env,
    jobject /* this */,
    jstring modelPath,
    jint scale) {
    std::string pathStr;
    if (modelPath) {
        const char* pathChars = env->GetStringUTFChars(modelPath, nullptr);
        if (pathChars) {
            pathStr = pathChars;
            env->ReleaseStringUTFChars(modelPath, pathChars);
        }
    }
    bool result = EsrganPipeline::getInstance().loadContext(pathStr, static_cast<int>(scale));
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_sdnpu_engine_ESRGANEngine_nativeUpscaleEsrgan(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray inRgba,
    jint inW,
    jint inH,
    jint scale) {
    if (!inRgba || inW <= 0 || inH <= 0 || scale <= 0) {
        return nullptr;
    }

    jsize inLen = env->GetArrayLength(inRgba);
    size_t expectedInSize = static_cast<size_t>(inW) * static_cast<size_t>(inH) * 4;
    if (static_cast<size_t>(inLen) < expectedInSize) {
        return nullptr;
    }

    jbyte* inElems = env->GetByteArrayElements(inRgba, nullptr);
    if (!inElems) {
        return nullptr;
    }

    std::vector<uint8_t> inVec(
        reinterpret_cast<const uint8_t*>(inElems),
        reinterpret_cast<const uint8_t*>(inElems) + expectedInSize
    );
    env->ReleaseByteArrayElements(inRgba, inElems, JNI_ABORT);

    try {
        int outW = inW * scale;
        int outH = inH * scale;
        size_t outSize = static_cast<size_t>(outW) * static_cast<size_t>(outH) * 4;
        std::vector<uint8_t> outVec(outSize);

        bool ok = EsrganPipeline::getInstance().upscale(
            inVec.data(),
            static_cast<int>(inW),
            static_cast<int>(inH),
            outVec.data(),
            outW,
            outH,
            static_cast<int>(scale)
        );

        if (!ok) {
            return nullptr;
        }

        jbyteArray result = env->NewByteArray(static_cast<jsize>(outSize));
        if (!result) {
            return nullptr;
        }

        env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(outSize),
            reinterpret_cast<const jbyte*>(outVec.data())
        );
        return result;
    } catch (...) {
        return nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_example_sdnpu_engine_ESRGANEngine_nativeCancelEsrgan(
    JNIEnv* /* env */,
    jobject /* this */) {
    EsrganPipeline::getInstance().requestCancel();
}

JNIEXPORT void JNICALL
Java_com_example_sdnpu_engine_ESRGANEngine_nativeUnloadEsrganContext(
    JNIEnv* /* env */,
    jobject /* this */) {
    EsrganPipeline::getInstance().unloadContext();
}

}
