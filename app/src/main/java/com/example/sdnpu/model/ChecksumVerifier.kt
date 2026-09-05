package com.example.sdnpu.model

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object ChecksumVerifier {
    fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifyFile(file: File, expectedSha256: String): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val computed = calculateSha256(file)
        return computed.equals(expectedSha256, ignoreCase = true)
    }
}
