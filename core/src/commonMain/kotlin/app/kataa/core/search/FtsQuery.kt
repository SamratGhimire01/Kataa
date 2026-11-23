package app.kataa.core.search

/**
 * Turns free speech ("where's my keys?") into a safe FTS5 query ("keys").
 * Raw user text must never reach MATCH: quotes, apostrophes and words like AND/NOT/NEAR are FTS5 syntax.
 */
object FtsQuery {
    private val STOPWORDS = setOf(
        "a", "an", "the", "is", "are", "was", "were", "am", "be", "been", "being",
        "i", "me", "my", "mine", "myself", "you", "your", "it", "its", "we", "our", "us",
        "of", "to", "in", "on", "at", "for", "with", "and", "or", "but", "not", "no", "so",
        "did", "do", "does", "done", "have", "has", "had",
        "where", "what", "when", "which", "who", "whom", "whose", "how", "why",
        "that", "this", "these", "those", "there", "here", "then", "than",
        "s", "t", "d", "ll", "re", "ve", "m",
        "can", "could", "would", "should", "will", "shall", "may", "might", "must",
        "about", "remember", "remind", "tell", "show", "find", "search", "please", "again",
        "kataa", "kai", "hey", "ok", "okay", "just", "any", "some", "all",
        "near", // FTS5 keyword; also rarely useful as a search term
    )

    /** Lowercased content tokens, stopwords removed, order kept, duplicates dropped. */
    fun tokens(text: String, maxTokens: Int = 12): List<String> {
        val out = LinkedHashSet<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                val t = sb.toString()
                sb.clear()
                if (t !in STOPWORDS && (t.length >= 2 || t.all(Char::isDigit))) out += t
            }
        }
        for (ch in text.lowercase()) {
            if (ch.isLetterOrDigit()) sb.append(ch) else flush()
            if (out.size >= maxTokens) break
        }
        flush()
        return out.take(maxTokens)
    }

    /**
     * OR of quoted tokens, or null when nothing searchable remains.
     * BM25 then rewards rows matching more (and rarer) tokens. The porter tokenizer stems both sides.
     */
    fun build(text: String): String? {
        val t = tokens(text)
        if (t.isEmpty()) return null
        return t.joinToString(" OR ") { "\"" + it.replace("\"", "") + "\"" }
    }
}
