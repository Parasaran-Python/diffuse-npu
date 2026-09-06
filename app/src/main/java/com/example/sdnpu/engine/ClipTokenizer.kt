package com.example.sdnpu.engine

import java.util.regex.Pattern

class ClipTokenizer(
    private val vocabMap: Map<String, Int> = DEFAULT_VOCAB,
    private val merges: List<Pair<String, String>> = DEFAULT_MERGES
) {
    companion object {
        const val BOS_TOKEN = 49406
        const val EOS_TOKEN = 49407
        const val PAD_TOKEN = 49407
        const val MAX_LENGTH = 77

        // Simplified pattern without UNICODE_CHARACTER_CLASS flag
        // Matches contractions, words (ASCII letters), numbers, and punctuation
        private val PATTERN = Pattern.compile(
            """'s|'t|'re|'ve|'m|'ll|'d|[a-zA-Z]+|[0-9]+|[^\\sa-zA-Z0-9]+"""
        )

        val DEFAULT_VOCAB: Map<String, Int> by lazy {
            buildVocab()
        }

        val DEFAULT_MERGES: List<Pair<String, String>> by lazy {
            buildMerges()
        }

        private fun buildVocab(): Map<String, Int> {
            val map = mutableMapOf<String, Int>()
            map["<|startoftext|>"] = BOS_TOKEN
            map["<|endoftext|>"] = EOS_TOKEN
            map["<|pad|>"] = PAD_TOKEN

            var tokenId = 0
            for (c in 0..255) {
                val byteStr = String.format("<0x%02X>", c)
                map[byteStr] = tokenId++
            }
            tokenId = 256

            val commonTokens = listOf(
                "Ġthe", "Ġa", "Ġan", "Ġand", "Ġof", "Ġto", "Ġin", "Ġfor", "Ġis", "Ġon",
                "Ġwith", "Ġas", "Ġby", "Ġat", "Ġfrom", "Ġthat", "Ġthis", "Ġbe", "Ġare",
                "Ġphotorealistic", "Ġportrait", "Ġlandscape", "Ġmountains", "Ġlake",
                "Ġriver", "Ġsky", "Ġsunset", "Ġsunrise", "Ġbeautiful", "Ġdetailed",
                "Ġ8k", "Ġmasterpiece", "Ġcyberpunk", "Ġanime", "Ġvintage", "Ġoil",
                "Ġpainting", "Ġdigital", "Ġart", "Ġserene", "Ġnature", "Ġforest",
                "Ġcity", "Ġfuturistic", "Ġblurry", "Ġdistorted", "Ġlow", "Ġquality",
                "Ġugly", "Ġpoor", "Ġdark", "Ġhigh", "Ġresolution", "Ġsharp", "Ġfocus",
                "Ġlighting", "Ġcinematic", "Ġdepth", "Ġfield", "Ġbokeh", "Ġhdr",
                "Ġrealistic", "Ġphotograph", "Ġillustration", "Ġconcept", "Ġart",
                "Ġcharacter", "Ġface", "Ġeyes", "Ġhair", "Ġskin", "Ġclothing",
                "Ġbackground", "Ġforeground", "Ġcomposition", "Ġperspective",
                "Ġstyle", "Ġmood", "Ġatmosphere", "Ġcolor", "Ġpalette", "Ġtexture"
            )

            for (token in commonTokens) {
                if (!map.containsKey(token)) {
                    map[token] = tokenId++
                }
            }
            return map
        }

        private fun buildMerges(): List<Pair<String, String>> {
            return listOf(
                "Ġ" to "p",
                "Ġ" to "h",
                "Ġ" to "o",
                "Ġ" to "t",
                "Ġ" to "o",
                "p" to "h",
                "o" to "t",
                "o" to "g",
                "r" to "a",
                "p" to "h",
                "o" to "t",
                "o" to "g",
                "r" to "a",
                "p" to "h",
                "i" to "c",
                "s" to "t"
            )
        }
    }

    fun tokenize(text: String, maxLength: Int = MAX_LENGTH): IntArray {
        val result = IntArray(maxLength) { PAD_TOKEN }
        result[0] = BOS_TOKEN

        val bpeTokens = bpeEncode(text)
        var tokenIdx = 1

        for (token in bpeTokens) {
            if (tokenIdx >= maxLength - 1) break
            val id = vocabMap[token] ?: hashTokenToId(token)
            result[tokenIdx++] = id
        }

        if (tokenIdx < maxLength) {
            result[tokenIdx] = EOS_TOKEN
        }
        return result
    }

    private fun bpeEncode(text: String): List<String> {
        val words = mutableListOf<String>()
        val matcher = PATTERN.matcher(text.lowercase())
        while (matcher.find()) {
            words.add(matcher.group())
        }
        val tokens = mutableListOf<String>()

        for (word in words) {
            val wordWithPrefix = if (word.firstOrNull()?.isLetterOrDigit() == true) "Ġ$word" else word
            var tokenParts = wordWithPrefix.split("")

            for ((a, b) in merges) {
                var i = 0
                while (i < tokenParts.size - 1) {
                    if (tokenParts[i] == a && tokenParts[i + 1] == b) {
                        tokenParts = tokenParts.take(i) + listOf(a + b) + tokenParts.drop(i + 2)
                    } else {
                        i++
                    }
                }
            }
            tokens.addAll(tokenParts)
        }
        return tokens
    }

    private fun hashTokenToId(token: String): Int {
        var hash = 5381
        for (c in token.toCharArray()) {
            hash = ((hash shl 5) + hash) + c.code
        }
        return 10000 + (hash and 0x7FFFFFFF) % 39408
    }
}