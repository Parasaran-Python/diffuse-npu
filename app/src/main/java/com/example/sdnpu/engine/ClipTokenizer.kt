package com.example.sdnpu.engine

class ClipTokenizer(
    private val vocabMap: Map<String, Int> = DEFAULT_VOCAB
) {
    companion object {
        const val BOS_TOKEN = 49406
        const val EOS_TOKEN = 49407
        const val PAD_TOKEN = 49407
        const val MAX_LENGTH = 77

        // Common seed vocabulary for base CLIP English tokens
        val DEFAULT_VOCAB: Map<String, Int> by lazy {
            val map = mutableMapOf<String, Int>()
            map["<|startoftext|>"] = BOS_TOKEN
            map["<|endoftext|>"] = EOS_TOKEN
            val commonWords = listOf(
                "a", "an", "the", "photorealistic", "portrait", "landscape", "mountains",
                "lake", "river", "sky", "sunset", "sunrise", "beautiful", "detailed",
                "8k", "masterpiece", "cyberpunk", "anime", "vintage", "oil", "painting",
                "digital", "art", "serene", "nature", "forest", "city", "futuristic",
                "blurry", "distorted", "low", "quality", "ugly", "poor", "dark"
            )
            commonWords.forEachIndexed { index, word ->
                map[word] = 1000 + index
            }
            map
        }
    }

    fun tokenize(text: String, maxLength: Int = MAX_LENGTH): IntArray {
        val result = IntArray(maxLength) { PAD_TOKEN }
        result[0] = BOS_TOKEN

        val words = text.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var tokenIdx = 1

        for (word in words) {
            if (tokenIdx >= maxLength - 1) break
            val cleanWord = word.replace(Regex("[^a-z0-9]"), "")
            if (cleanWord.isEmpty()) continue

            val id = vocabMap[cleanWord] ?: hashWordToTokenId(cleanWord)
            result[tokenIdx++] = id
        }

        // Place EOS token immediately after last word
        if (tokenIdx < maxLength) {
            result[tokenIdx] = EOS_TOKEN
        }
        return result
    }

    private fun hashWordToTokenId(word: String): Int {
        val hash = word.hashCode() and 0x7FFFFFFF
        return 2000 + (hash % 40000)
    }
}
