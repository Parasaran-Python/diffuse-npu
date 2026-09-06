cmake_minimum_required(VERSION 3.22.1)
project("sdnpu_engine")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

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
    clip_encoder.cpp
    scheduler.cpp
    unet_denoiser.cpp
    vae_decoder.cpp
    sd_pipeline.cpp
    jni_bridge.cpp
)

find_library(log-lib log)

target_link_libraries(
    sdnpu_engine
    ${log-lib}
    dl
)

