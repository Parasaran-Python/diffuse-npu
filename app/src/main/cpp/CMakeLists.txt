cmake_minimum_required(VERSION 3.22.1)
project("sdnpu_engine")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# Bundled QNN libraries (fallback for devices without system QNN)
set(JNI_LIBS_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
if(EXISTS "${JNI_LIBS_DIR}")
    message(STATUS "Using bundled QNN libraries from: ${JNI_LIBS_DIR}")
    link_directories(${JNI_LIBS_DIR})
endif()

# Optional external QAIRT SDK integration if QAIRT_SDK_PATH is set
if(DEFINED QAIRT_SDK_PATH AND EXISTS "${QAIRT_SDK_PATH}")
    message(STATUS "Using external QAIRT SDK from: ${QAIRT_SDK_PATH}")
    include_directories(${QAIRT_SDK_PATH}/include/QNN)
    link_directories(${QAIRT_SDK_PATH}/lib/${ANDROID_ABI})
endif()

add_library(
    sdnpu_engine
    SHARED
    qnn_wrapper.cpp
    qnn_loader.cpp
    clip_encoder.cpp
    unet_denoiser.cpp
    vae_decoder.cpp
    sd_pipeline.cpp
    esrgan_pipeline.cpp
    jni_bridge.cpp
)

find_library(log-lib log)

# QNN libraries are optional - only link if found
set(QNN_LIBS "")
if(EXISTS "${JNI_LIBS_DIR}/libQnnSystem.so")
    list(APPEND QNN_LIBS QnnSystem)
endif()
if(EXISTS "${JNI_LIBS_DIR}/libQnnHtp.so")
    list(APPEND QNN_LIBS QnnHtp)
endif()
if(EXISTS "${JNI_LIBS_DIR}/libQnnHtpV73Stub.so")
    list(APPEND QNN_LIBS QnnHtpV73Stub)
endif()

target_link_libraries(
    sdnpu_engine
    ${log-lib}
    dl
    ${QNN_LIBS}
)