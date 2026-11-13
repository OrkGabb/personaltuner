package com.orkgabb.fusao.block

enum class Verdict { ALLOW, BLOCK, SILENCE }

/** Resultado do motor. [ruleId] = -1 quando a decisão veio de um ajuste global, não de uma regra. */
data class BlockDecision(val verdict: Verdict, val ruleId: Long, val reason: String) {
    val blocks: Boolean get() = verdict == Verdict.BLOCK
    val silences: Boolean get() = verdict == Verdict.SILENCE

    companion object {
        val ALLOW: BlockDecision = BlockDecision(Verdict.ALLOW, -1L, "")
        const val REASON_PRIVATE: String = "Sem identificação"
        const val REASON_NON_CONTACT: String = "Fora dos contatos"
        const val REASON_QUIET: String = "Horário de silêncio"
    }
}

/**
 * Motor de decisão: Kotlin puro, sem Android, usado pela triagem de chamadas, pelo receptor de SMS, pelo
 * listener de notificações e pelo teste de número da aba.
 */
object BlockEngine {

    private const val MAX_DIGITS: Int = 24

    /** Abaixo disso o número é curto demais para a regra "fora dos contatos" (códigos curtos, ramais). */
    private const val NON_CONTACT_MIN_DIGITS: Int = 8

    /** Só os dígitos: "+55 (11) 91234-5678" -> "5511912345678". */
    fun normalize(raw: String?): String {
        if (raw == null) return ""
        val out = StringBuilder(minOf(raw.length, MAX_DIGITS))
        for (ch in raw) {
            if (ch in '0'..'9') {
                out.append(ch)
                if (out.length >= MAX_DIGITS) break
            }
        }
        return out.toString()
    }

    /**
     * [isContact] só é chamado se a decisão depender da agenda (chamada, "fora dos contatos" ligado e nenhuma
     * regra casou); null = agenda indisponível, e aí a regra é pulada em vez de bloquear todo mundo.
     */
    fun evaluate(
        cfg: BlockConfig,
        rawNumber: String?,
        channel: BlockChannel,
        hour: Int,
        minute: Int,
        isContact: () -> Boolean?,
    ): BlockDecision {
        if (!cfg.enabled) return BlockDecision.ALLOW
        val call: Boolean = channel == BlockChannel.CALL
        if (call && !cfg.blockCalls) return BlockDecision.ALLOW
        if (!call && !cfg.blockSms) return BlockDecision.ALLOW

        val digits: String = normalize(rawNumber)
        if (digits.isEmpty()) {
            return if (call && cfg.blockPrivate) {
                BlockDecision(Verdict.BLOCK, -1L, BlockDecision.REASON_PRIVATE)
            } else {
                BlockDecision.ALLOW
            }
        }

        for (rule in cfg.rules) {
            if (!rule.channels.covers(channel) || !rule.matches(digits, cfg.suffixMatch)) continue
            // SMS não tem o que silenciar à parte; em chamada o ajuste global troca bloquear por silenciar
            val silence: Boolean = call && (rule.action == RuleAction.SILENCE || cfg.silenceCalls)
            return BlockDecision(
                verdict = if (silence) Verdict.SILENCE else Verdict.BLOCK,
                ruleId = rule.id,
                reason = rule.label.ifEmpty { rule.pattern },
            )
        }

        if (call) {
            if (cfg.blockNonContacts && digits.length >= NON_CONTACT_MIN_DIGITS && isContact() == false) {
                return BlockDecision(Verdict.BLOCK, -1L, BlockDecision.REASON_NON_CONTACT)
            }
            if (cfg.quietEnabled && inQuietHours(cfg.quietStart, cfg.quietEnd, hour, minute)) {
                return BlockDecision(
                    verdict = if (cfg.quietBlock) Verdict.BLOCK else Verdict.SILENCE,
                    ruleId = -1L,
                    reason = BlockDecision.REASON_QUIET,
                )
            }
        }
        return BlockDecision.ALLOW
    }

    /** Janela [start, end) em HH:MM; atravessa a meia-noite (23:00 -> 07:00). Início = fim: janela vazia. */
    fun inQuietHours(start: String, end: String, hour: Int, minute: Int): Boolean {
        val from: Int = minutesOf(start) ?: return false
        val to: Int = minutesOf(end) ?: return false
        val now: Int = hour * 60 + minute
        return when {
            from == to -> false
            from < to -> now in from until to
            else -> now >= from || now < to
        }
    }

    private fun minutesOf(value: String): Int? {
        val clean: String = BlockConfig.cleanTime(value) ?: return null
        return clean.substring(0, 2).toInt() * 60 + clean.substring(3, 5).toInt()
    }
}

/**
 * Quem mandou a notificação de SMS, a partir dos campos de remetente (título, título da conversa, remetentes das
 * mensagens, pessoas). O corpo da mensagem fica de fora de propósito: um número bloqueado citado no texto de
 * outra pessoa não pode esconder a mensagem dela.
 */
object SenderFields {

    private val EMBEDDED: Regex = Regex("""\+?[0-9][0-9\s().\-]{6,}[0-9]""")
    private const val PHONE_PUNCT: String = "+()-.  ‪‬‎‏"

    /**
     * Números candidatos, sem repetição. Um campo que é só número (inclusive código curto, "28000") entra
     * inteiro; num campo com texto ("tel:+5511...", "Maria +55 11 ...") só entram números de 8 a 15 dígitos.
     */
    fun candidates(fields: List<CharSequence?>): List<String> {
        val out = LinkedHashSet<String>()
        for (field in fields) {
            val text: String = field?.toString()?.trim().orEmpty()
            if (text.isEmpty()) continue
            if (text.all { it in '0'..'9' || it in PHONE_PUNCT }) {
                if (text.count { it in '0'..'9' } in 3..15) out.add(text)
                continue
            }
            for (m in EMBEDDED.findAll(text)) {
                if (BlockEngine.normalize(m.value).length in 8..15) out.add(m.value)
            }
        }
        return out.toList()
    }
}
