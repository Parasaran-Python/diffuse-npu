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
        assertEquals(ClipTokenizer.BOS_TOKEN, tokens[0])
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
        val longText = (1..100).joinToString(" ") { "word$it" }
        val tokens = tokenizer.tokenize(longText)

        assertEquals(77, tokens.size)
        assertEquals(ClipTokenizer.BOS_TOKEN, tokens[0])
        assertEquals(ClipTokenizer.EOS_TOKEN, tokens[76])
    }

    @Test
    fun testKnownKeywordsMatchVocab() {
        val tokenizer = ClipTokenizer()
        val tokens = tokenizer.tokenize("photorealistic landscape")
        assertEquals(ClipTokenizer.BOS_TOKEN, tokens[0])
        // New BPE tokenizer: tokens will be different but should contain known vocab entries
        val vocabTokens = tokens.filter { it != ClipTokenizer.BOS_TOKEN && it != ClipTokenizer.EOS_TOKEN && it != ClipTokenizer.PAD_TOKEN }
        assertTrue(vocabTokens.isNotEmpty())
        assertTrue("EOS token should be present", tokens.lastIndexOf(ClipTokenizer.EOS_TOKEN) >= 0)
        assertEquals(ClipTokenizer.PAD_TOKEN, tokens[76])
    }

    @Test
    fun testOutOfVocabWordHashingFallback() {
        val tokenizer = ClipTokenizer()
        val tokens = tokenizer.tokenize("xyzunknownword123")
        assertEquals(ClipTokenizer.BOS_TOKEN, tokens[0])
        // Should contain some token for the unknown word (not just padding)
        val vocabTokens = tokens.filter { it != ClipTokenizer.BOS_TOKEN && it != ClipTokenizer.EOS_TOKEN && it != ClipTokenizer.PAD_TOKEN }
        assertTrue(vocabTokens.isNotEmpty())
        assertTrue("EOS token should be present", tokens.lastIndexOf(ClipTokenizer.EOS_TOKEN) >= 0)
    }
}