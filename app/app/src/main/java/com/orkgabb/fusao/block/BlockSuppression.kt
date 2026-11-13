package com.orkgabb.fusao.block

/**
 * Memória curta dos SMS bloqueados. O receptor de SMS vê o número real do remetente; a notificação do app de
 * mensagens muitas vezes só mostra o nome do contato ou o número em outro formato. Guardar o número e o começo
 * do texto por alguns minutos deixa o listener reconhecer a notificação mesmo assim.
 *
 * Só memória (some com o processo) e limitada: o que não couber sai do mais antigo.
 */
class BlockSuppression(
    private val ttlMillis: Long = 15L * 60L * 1000L,
    private val maxEntries: Int = 120,
) {
    private val numbers = LinkedHashMap<String, Long>()
    private val snippets = LinkedHashMap<String, Long>()

    @Synchronized
    fun add(rawNumber: String?, body: String?, nowMillis: Long) {
        val expires: Long = nowMillis + ttlMillis
        val digits: String = BlockEngine.normalize(rawNumber)
        if (digits.isNotEmpty()) put(numbers, digits, expires, nowMillis)
        val snippet: String? = snippetOf(body)
        if (snippet != null) put(snippets, snippet, expires, nowMillis)
    }

    /**
     * [senderDigits]: dígitos de cada candidato a remetente da notificação; [text]: todo o texto dela.
     * Número: igual, ou um termina com o outro quando os dois têm 8+ dígitos (com/sem código do país).
     */
    @Synchronized
    fun matches(senderDigits: List<String>, text: String, nowMillis: Long): Boolean {
        prune(numbers, nowMillis)
        prune(snippets, nowMillis)
        for (candidate in senderDigits) {
            if (candidate.isEmpty()) continue
            for (stored in numbers.keys) {
                if (candidate == stored) return true
                if (candidate.length >= SUFFIX_MIN && stored.length >= SUFFIX_MIN &&
                    (candidate.endsWith(stored) || stored.endsWith(candidate))
                ) {
                    return true
                }
            }
        }
        if (snippets.isEmpty()) return false
        val normalized: String = normalizeText(text)
        return snippets.keys.any { normalized.contains(it) }
    }

    @Synchronized
    fun clear() {
        numbers.clear()
        snippets.clear()
    }

    private fun put(map: LinkedHashMap<String, Long>, key: String, expires: Long, nowMillis: Long) {
        map.remove(key)
        prune(map, nowMillis)
        while (map.size >= maxEntries) {
            val oldest: String = map.keys.iterator().next()
            map.remove(oldest)
        }
        map[key] = expires
    }

    private fun prune(map: LinkedHashMap<String, Long>, nowMillis: Long) {
        val it: MutableIterator<Map.Entry<String, Long>> = map.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value < nowMillis) it.remove()
        }
    }

    companion object {
        private const val SUFFIX_MIN: Int = 8
        private const val SNIPPET_MIN: Int = 12
        private const val SNIPPET_MAX: Int = 64
        private val SPACES: Regex = Regex("\\s+")

        /** Minúsculas e espaços colapsados: o mesmo tratamento no texto do SMS e no da notificação. */
        fun normalizeText(text: String): String = text.lowercase().replace(SPACES, " ").trim()

        /** Começo do texto do SMS; curto demais (< 12) não identifica uma mensagem e fica de fora. */
        fun snippetOf(body: String?): String? {
            if (body == null) return null
            val s: String = normalizeText(body)
            if (s.length < SNIPPET_MIN) return null
            return s.take(SNIPPET_MAX)
        }
    }
}
