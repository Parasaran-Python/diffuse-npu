package com.example.sdnpu.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipTokenizerTest {

    @Test
    fun testTokenizeOutputLengthIs77() {
        val tokenizer = ClipTokenizer()
        val tokens = tokenizer.tokenize("a photorealistic landscape with mountains")
        assertEquals(77, tokens.size)
        // First token must be BOS (49406)
        assertEquals(ClipTokenizer.BOS_TOKEN, tokens[0])
        // Subsequent tokens must contain EOS (49407) and padding (49407)
        assertTrue(tokens.contains(ClipTokenizer.EOS_TOKEN))
        assertEquals(ClipTokenizer.PAD_TOKEN, tokens[76])
    }

    @Test
    fun testEmptyStringProducesBosEosAndPadding() {
        val tokenizer = ClipTokenizer()
        val tokens = tokenizer.tokenize("")
        assertEquals(77, tokens.size)
        assertEquals(ClipTokenizer.BOS_TOKEN, tokens[0])
        assertEquals(ClipTokenizer.EOS_TOKEN, tokens[1])
        for (i in 2 until 77) {
            assertEquals(ClipTokenizer.PAD_TOKEN, tokens[i])
        }
    }

    @Test
    fun testWhitespaceOnlyStringProducesBosEosAndPadding() {
        val tokenizer = ClipTokenizer()
        val tokens = tokenizer.tokenize("    ")
        assertEquals(77, tokens.size)
        assertEquals(ClipTokenizer.BOS_TOKEN, tokens[0])
        assertEquals(ClipTokenizer.EOS_TOKEN, tokens[1])
        for (i in 2 until 77) {
            assertEquals(ClipTokenizer.PAD_TOKEN, tokens[i])
        }
    }

    @Test
    fun testLongTextTruncationAtMaxLength() {
        val tokenizer = ClipTokenizer()
        // Generate 100 words
        val longText = (1..100).joinToString(" ") { "word$it" }
        val tokens = tokenizer.tokenize(longText)

        assertEquals(77, tokens.size)
        assertEquals(ClipTokenizer.BOS_TOKEN, tokens[0])
        // The last token must be EOS_TOKEN
        assertEquals(ClipTokenizer.EOS_TOKEN, tokens[76])
    }

    @Test
    fun testKnownKeywordsMatchVocab() {
        val tokenizer = ClipTokenizer()
        val tokens = tokenizer.tokenize("photorealistic landscape")
        assertEquals(ClipTokenizer.BOS_TOKEN, tokens[0])
        // Token 1 and 2 should match known vocab
        assertEquals(1003, tokens[1]) // "photorealistic" is index 3 in commonWords -> 1003
        assertEquals(1005, tokens[2]) // "landscape" is index 5 in commonWords -> 1005
        assertEquals(ClipTokenizer.EOS_TOKEN, tokens[3])
        assertEquals(ClipTokenizer.PAD_TOKEN, tokens[4])
    }

    @Test
    fun testOutOfVocabWordHashingFallback() {
        val tokenizer = ClipTokenizer()
        val tokens = tokenizer.tokenize("xyzunknownword123")
        assertEquals(ClipTokenizer.BOS_TOKEN, tokens[0])
        val expectedHash = "xyzunknownword123".hashCode() and 0x7FFFFFFF
        val expectedId = 2000 + (expectedHash % 40000)
        assertEquals(expectedId, tokens[1])
        assertEquals(ClipTokenizer.EOS_TOKEN, tokens[2])
    }
}
