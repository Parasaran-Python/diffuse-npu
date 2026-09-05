package com.example.sdnpu.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChecksumVerifierTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSha256Calculation() {
        val file = tempFolder.newFile("sample.bin")
        file.writeText("hello stable diffusion npu")

        // Expected SHA-256 for "hello stable diffusion npu"
        val expectedSha = "009e6ec48bef8503c5dcc1cb4fe11874725654e7856996aace18869cbda2077a"
        val actualSha = ChecksumVerifier.calculateSha256(file)

        assertEquals(expectedSha, actualSha)
        assertTrue(ChecksumVerifier.verifyFile(file, expectedSha))
    }
}
