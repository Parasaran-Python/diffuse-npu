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

    @Test
    fun testTokenizeKnownPromptMatchesClipIds() {
        val tokenizer = ClipTokenizer()
        val tokens = tokenizer.tokenize("a photo of an astronaut")
        assertEquals(77, tokens.size)
        assertEquals(49406, tokens[0]) // <|startoftext|>
        // Verify tokens contains end-of-text marker
        assertTrue(tokens.contains(49407))
        // Verify exact standard CLIP token IDs:
        // "a</w>" -> 320, "photo</w>" -> 1125, "of</w>" -> 539, "an</w>" -> 550, "astronaut</w>" -> 18376
        assertEquals(320, tokens[1])
        assertEquals(1125, tokens[2])
        assertEquals(539, tokens[3])
        assertEquals(550, tokens[4])
        assertEquals(18376, tokens[5])
        assertEquals(49407, tokens[6]) // <|endoftext|>
    }

    @Test
    fun testCaseInsensitiveTokenization() {
        val tokenizer = ClipTokenizer()
        val lowerTokens = tokenizer.tokenize("a photo of an astronaut")
        val upperTokens = tokenizer.tokenize("A PHOTO OF AN ASTRONAUT")
        org.junit.Assert.assertArrayEquals(lowerTokens, upperTokens)
    }

    @Test
    fun testContractionsAndPunctuation() {
        val tokenizer = ClipTokenizer()
        val tokens = tokenizer.tokenize("it's an astronaut!")
        assertEquals(77, tokens.size)
        assertEquals(49406, tokens[0])
        assertEquals(585, tokens[1])   // "it</w>"
        assertEquals(568, tokens[2])   // "'s</w>"
        assertEquals(550, tokens[3])   // "an</w>"
        assertEquals(18376, tokens[4]) // "astronaut</w>"
        assertEquals(256, tokens[5])   // "!</w>"
        assertEquals(49407, tokens[6]) // EOS
    }

    @Test
    fun testCustomVocabAndMergesSecondaryConstructor() {
        val customVocab = mapOf(
            "<|startoftext|>" to 49406,
            "<|endoftext|>" to 49407,
            "<|pad|>" to 49407,
            "h" to 0,
            "i" to 1,
            "hi</w>" to 2
        )
        val customMerges = listOf("h" to "i</w>")
        val tokenizer = ClipTokenizer(customVocab, customMerges)
        assertEquals(customMerges, tokenizer.merges)
    }

    @Test
    fun testDefaultMergesCount() {
        assertEquals(48894, ClipTokenizer.DEFAULT_MERGES.size)
        assertEquals(49408, ClipTokenizer.DEFAULT_VOCAB.size)
    }
}