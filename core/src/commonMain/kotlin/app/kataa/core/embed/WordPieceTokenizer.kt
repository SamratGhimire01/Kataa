package app.kataa.core.embed

/**
 * BERT uncased WordPiece tokenizer (the one bge-small-en-v1.5 and MiniLM use), in common Kotlin so
 * Android and iOS tokenize identically. Matches HuggingFace `BertTokenizer(do_lower_case=True)` for
 * English text; verified against reference token ids in tests.
 */
class WordPieceTokenizer(vocab: List<String>, private val maxLength: Int = 128) {
    private val ids: Map<String, Int> = HashMap<String, Int>(vocab.size * 2).apply { vocab.forEachIndexed { i, t -> put(t, i) } }
    val clsId = id("[CLS]")
    val sepId = id("[SEP]")
    val padId = id("[PAD]")
    private val unkId = id("[UNK]")

    private fun id(t: String) = ids[t] ?: error("vocab is missing $t")

    /** Token ids with [CLS] ... [SEP], truncated to [maxLength]. */
    fun encode(text: String): IntArray {
        val out = ArrayList<Int>(32)
        out += clsId
        for (word in basicTokens(text)) {
            wordPiece(word, out)
            if (out.size >= maxLength - 1) break
        }
        while (out.size > maxLength - 1) out.removeAt(out.size - 1)
        out += sepId
        return out.toIntArray()
    }

    /** Lowercase, strip accents, split on whitespace and punctuation (each punctuation char is a token). */
    internal fun basicTokens(text: String): List<String> {
        val tokens = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { tokens += sb.toString(); sb.clear() } }
        for (raw in text) {
            if (raw.code == 0 || raw.code == 0xFFFD || (raw.isISOControl() && raw != '\t' && raw != '\n' && raw != '\r')) continue
            val c = raw.lowercaseChar()
            when {
                c.isWhitespace() -> flush()
                isPunctuation(c) || isCjk(c) -> { flush(); tokens += c.toString() }
                isCombiningMark(c) -> Unit
                else -> sb.append(fold(c))
            }
        }
        flush()
        return tokens
    }

    private fun wordPiece(word: String, out: MutableList<Int>) {
        if (word.length > 100) { out += unkId; return }
        val start = out.size
        var i = 0
        while (i < word.length) {
            var end = word.length
            var found = -1
            while (end > i) {
                val piece = if (i == 0) word.substring(i, end) else "##" + word.substring(i, end)
                val id = ids[piece]
                if (id != null) { found = id; break }
                end--
            }
            if (found < 0) {
                while (out.size > start) out.removeAt(out.size - 1)
                out += unkId
                return
            }
            out += found
            i = end
        }
    }

    private fun isPunctuation(c: Char): Boolean {
        val cp = c.code
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (c.category) {
            CharCategory.CONNECTOR_PUNCTUATION, CharCategory.DASH_PUNCTUATION, CharCategory.START_PUNCTUATION,
            CharCategory.END_PUNCTUATION, CharCategory.INITIAL_QUOTE_PUNCTUATION, CharCategory.FINAL_QUOTE_PUNCTUATION,
            CharCategory.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    private fun isCjk(c: Char): Boolean {
        val cp = c.code
        return cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF
    }

    private fun isCombiningMark(c: Char) = c.category == CharCategory.NON_SPACING_MARK

    /** Accent stripping for precomposed Latin letters (NFD + drop marks, without needing a platform normalizer). */
    private fun fold(c: Char): Char {
        if (c.code < 0xC0) return c
        val idx = FOLD_FROM.indexOf(c)
        return if (idx >= 0) FOLD_TO[idx] else c
    }

    companion object {
        private const val FOLD_FROM = "àáâãäåèéêëìíîïñòóôõöùúûüýÿçāăąćĉċčďēĕėęěĝğġģĥĩīĭįĵķĺļľńņňōŏőŕŗřśŝşšţťũūŭůűųŵŷźżž"
        private const val FOLD_TO = "aaaaaaeeeeiiiinooooouuuuyycaaacccccdeeeeegggghiiiijklllnnnooorrrssssttuuuuuuwyzzz"

        fun fromVocabText(text: String, maxLength: Int = 128) =
            WordPieceTokenizer(text.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.toList(), maxLength)
    }
}
