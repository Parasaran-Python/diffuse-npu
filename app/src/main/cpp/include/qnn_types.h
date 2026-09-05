#pragma once
#include <cstdint>
#include <cstddef>

#define QNN_SUCCESS 0
#define QNN_ERROR_GENERAL 1
#define QNN_ERROR_UNSUPPORTED_FEATURE 2
#define QNN_ERROR_MEM_ALLOC 3
#define QNN_ERROR_INVALID_ARGUMENT 4
#define QNN_ERROR_NOT_INITIALIZED 5

typedef uint32_t Qnn_ErrorHandle_t;
typedef void* Qnn_BackendHandle_t;
typedef void* Qnn_ContextHandle_t;
typedef void* Qnn_GraphHandle_t;

enum QnnBackendTarget {
    BACKEND_CPU = 0,
    BACKEND_GPU = 1,
    BACKEND_HTP_NPU = 2
};
