package com.example.sdnpu.engine

enum class BackendType(val id: Int) {
    CPU(0),
    GPU(1),
    HTP_NPU(2);

    companion object {
        fun fromId(id: Int): BackendType = entries.firstOrNull { it.id == id } ?: CPU
    }
}
