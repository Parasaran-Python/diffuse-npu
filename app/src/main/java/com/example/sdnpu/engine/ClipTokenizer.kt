package com.example.sdnpu.engine

import android.content.Context
import java.io.File
import java.io.InputStream

class ClipTokenizer(
    private val vocabMap: Map<String, Int> = DEFAULT_VOCAB,
    private val bpeRanks: Map<Pair<String, String>, Int> = DEFAULT_BPE_RANKS
) {
    constructor(
        vocabMap: Map<String, Int>,
        merges: List<Pair<String, String>>
    ) : this(
        vocabMap = vocabMap,
        bpeRanks = merges.mapIndexed { idx, pair -> pair to idx }.toMap()
    )

    val merges: List<Pair<String, String>>
        get() = bpeRanks.entries.sortedBy { it.value }.map { it.key }

    // Cache for BPE subwords to avoid re-computing for repeated words
    private val bpeCache = HashMap<String, List<String>>().apply {
        put("<|startoftext|>", listOf("<|startoftext|>"))
        put("<|endoftext|>", listOf("<|endoftext|>"))
    }

    companion object {
        const val BOS_TOKEN = 49406
        const val EOS_TOKEN = 49407
        const val PAD_TOKEN = 49407
        const val MAX_LENGTH = 77
        const val VOCAB_ASSET_NAME = "bpe_simple_vocab_16e6.txt"

        private val PAT_REGEX = Regex(
            """<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|\p{L}+|\p{N}|[^\s\p{L}\p{N}]+""",
            setOf(RegexOption.IGNORE_CASE)
        )

        private val WHITESPACE_REGEX = Regex("""\s+""")

        private val BYTE_MAPPINGS: Pair<Map<Int, Char>, List<String>> by lazy {
            computeByteMappings()
        }

        val BYTE_ENCODER: Map<Int, Char> by lazy {
            BYTE_MAPPINGS.first
        }

        private val VOCAB_AND_RANKS: Pair<Map<String, Int>, Map<Pair<String, String>, Int>> by lazy {
            loadVocabAndRanks()
        }

        val DEFAULT_VOCAB: Map<String, Int> by lazy {
            VOCAB_AND_RANKS.first
        }

        val DEFAULT_BPE_RANKS: Map<Pair<String, String>, Int> by lazy {
            VOCAB_AND_RANKS.second
        }

        val DEFAULT_MERGES: List<Pair<String, String>> by lazy {
            DEFAULT_BPE_RANKS.entries.sortedBy { it.value }.map { it.key }
        }

        fun fromAsset(context: Context, assetName: String = VOCAB_ASSET_NAME): ClipTokenizer {
            val (vocab, ranks) = context.assets.open(assetName).use { parseVocabAndRanks(it) }
            return ClipTokenizer(vocab, ranks)
        }

        private fun openVocabStream(): InputStream? {
            // 1. Try file paths relative to working directory (JVM unit tests)
            val candidateFiles = listOf(
                File("app/src/main/assets/$VOCAB_ASSET_NAME"),
                File("src/main/assets/$VOCAB_ASSET_NAME"),
                File("../app/src/main/assets/$VOCAB_ASSET_NAME")
            )
            for (file in candidateFiles) {
                if (file.exists()) {
                    return file.inputStream()
                }
            }

            // 2. Try ClassLoader resources
            ClipTokenizer::class.java.getResourceAsStream("/$VOCAB_ASSET_NAME")?.let { return it }
            ClipTokenizer::class.java.classLoader?.getResourceAsStream(VOCAB_ASSET_NAME)?.let { return it }
            ClipTokenizer::class.java.classLoader?.getResourceAsStream("assets/$VOCAB_ASSET_NAME")?.let { return it }

            // 3. Try reflection for Android Application context if running on device
            try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val currentAppMethod = activityThreadClass.getMethod("currentApplication")
                val app = currentAppMethod.invoke(null) as? Context
                app?.assets?.open(VOCAB_ASSET_NAME)?.let { return it }
            } catch (_: Throwable) {}

            return null
        }

        private fun loadVocabAndRanks(): Pair<Map<String, Int>, Map<Pair<String, String>, Int>> {
            val stream = openVocabStream()
            return if (stream != null) {
                stream.use { parseVocabAndRanks(it) }
            } else {
                buildFallbackVocabAndRanks()
            }
        }

        fun parseVocabAndRanks(inputStream: InputStream): Pair<Map<String, Int>, Map<Pair<String, String>, Int>> {
            val unicodeStrings = BYTE_MAPPINGS.second
            val vocabMap = HashMap<String, Int>(50000)
            val bpeRanks = HashMap<Pair<String, String>, Int>(50000)

            // 0..255: byte unicode characters (order of cs from bytes_to_unicode.values())
            for (s in unicodeStrings) {
                vocabMap[s] = vocabMap.size
            }
            // 256..511: byte unicode characters + "</w>"
            for (s in unicodeStrings) {
                vocabMap[s + "</w>"] = vocabMap.size
            }

            var rank = 0
            inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                var isFirst = true
                for (line in lines) {
                    if (isFirst) {
                        isFirst = false
                        continue // skip header line (e.g. #version: 0.2)
                    }
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    val spaceIdx = trimmed.indexOf(' ')
                    if (spaceIdx > 0) {
                        val first = trimmed.substring(0, spaceIdx)
                        val second = trimmed.substring(spaceIdx + 1)
                        val pair = Pair(first, second)
                        bpeRanks[pair] = rank++
                        val merged = first + second
                        if (!vocabMap.containsKey(merged)) {
                            vocabMap[merged] = vocabMap.size
                        }
                    }
                    if (rank >= 48894) {
                        break
                    }
                }
            }

            vocabMap["<|startoftext|>"] = BOS_TOKEN // 49406
            vocabMap["<|endoftext|>"] = EOS_TOKEN   // 49407

            return Pair(vocabMap, bpeRanks)
        }

        private fun computeByteMappings(): Pair<Map<Int, Char>, List<String>> {
            val bs = ArrayList<Int>()
            for (b in 33..126) bs.add(b)
            for (b in 161..172) bs.add(b)
            for (b in 174..255) bs.add(b)

            val cs = ArrayList<Int>(bs)
            var n = 0
            for (b in 0..255) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            val map = HashMap<Int, Char>(256)
            for (i in bs.indices) {
                map[bs[i]] = cs[i].toChar()
            }
            val unicodeStrings = cs.map { it.toChar().toString() }
            return Pair(map, unicodeStrings)
        }

        private fun buildFallbackVocabAndRanks(): Pair<Map<String, Int>, Map<Pair<String, String>, Int>> {
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

            val merges = listOf(
                "Ġ" to "p", "Ġ" to "h", "Ġ" to "o", "Ġ" to "t", "Ġ" to "o",
                "p" to "h", "o" to "t", "o" to "g", "r" to "a", "p" to "h",
                "i" to "c", "s" to "t"
            )
            val ranks = merges.mapIndexed { idx, pair -> pair to idx }.toMap()
            return Pair(map, ranks)
        }
    }

    fun tokenize(text: String, maxLength: Int = MAX_LENGTH): IntArray {
        val result = IntArray(maxLength) { PAD_TOKEN }
        result[0] = BOS_TOKEN

        val bpeTokens = bpeEncode(text)
        if (bpeTokens.isEmpty()) {
            if (maxLength > 1) {
                result[1] = EOS_TOKEN
            }
            return result
        }

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

    private fun cleanText(text: String): String {
        var cleaned = text.trim()
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        cleaned = WHITESPACE_REGEX.replace(cleaned, " ")
        return cleaned.trim().lowercase()
    }

    private fun bpeEncode(text: String): List<String> {
        val cleaned = cleanText(text)
        if (cleaned.isEmpty()) return emptyList()

        val tokens = mutableListOf<String>()
        for (match in PAT_REGEX.findAll(cleaned)) {
            val rawToken = match.value
            val tokenBytes = rawToken.toByteArray(Charsets.UTF_8)
            val encodedBuilder = StringBuilder()
            for (b in tokenBytes) {
                val ub = b.toInt() and 0xFF
                encodedBuilder.append(BYTE_ENCODER[ub] ?: ub.toChar())
            }
            val encodedToken = encodedBuilder.toString()
            val subwords = bpe(encodedToken)
            tokens.addAll(subwords)
        }
        return tokens
    }

    private fun bpe(token: String): List<String> {
        synchronized(bpeCache) {
            bpeCache[token]?.let { return it }
        }

        if (token.isEmpty()) return emptyList()

        var word = ArrayList<String>(token.length)
        for (i in 0 until token.length - 1) {
            word.add(token[i].toString())
        }
        word.add(token.last().toString() + "</w>")

        var pairs = getPairs(word)
        if (pairs.isEmpty()) {
            val result = listOf(token + "</w>")
            synchronized(bpeCache) {
                bpeCache[token] = result
            }
            return result
        }

        while (true) {
            var minRank = Int.MAX_VALUE
            var bestPair: Pair<String, String>? = null

            for (pair in pairs) {
                val rank = bpeRanks[pair]
                if (rank != null && rank < minRank) {
                    minRank = rank
                    bestPair = pair
                }
            }

            if (bestPair == null) {
                break
            }

            val first = bestPair.first
            val second = bestPair.second
            val newWord = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    newWord.add(first + second)
                    i += 2
                } else {
                    newWord.add(word[i])
                    i += 1
                }
            }
            word = newWord
            if (word.size == 1) {
                break
            }
            pairs = getPairs(word)
        }

        synchronized(bpeCache) {
            bpeCache[token] = word
        }
        return word
    }

    private fun getPairs(word: List<String>): Set<Pair<String, String>> {
        if (word.size < 2) return emptySet()
        val pairs = LinkedHashSet<Pair<String, String>>()
        for (i in 0 until word.size - 1) {
            pairs.add(Pair(word[i], word[i + 1]))
        }
        return pairs
    }

    private fun hashTokenToId(token: String): Int {
        var hash = 5381
        for (c in token.toCharArray()) {
            hash = ((hash shl 5) + hash) + c.code
        }
        return 10000 + (hash and 0x7FFFFFFF) % 39408
    }
}