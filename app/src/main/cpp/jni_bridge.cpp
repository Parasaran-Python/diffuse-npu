#include <jni.h>
#include "qnn_wrapper.h"

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

}
