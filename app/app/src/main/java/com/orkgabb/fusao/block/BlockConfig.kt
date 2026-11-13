package com.orkgabb.fusao.block

import androidx.compose.runtime.Immutable
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Como o padrão da regra é comparado com os dígitos do número. */
enum class MatchType(val key: String, val label: String) {
    EXACT("exact", "Exato"),
    PREFIX("prefix", "Prefixo"),
    WILDCARD("wildcard", "Curinga"),
    REGEX("regex", "Regex");

    companion object {
        fun fromKey(key: String): MatchType? = entries.firstOrNull { it.key == key }
    }
}

/** Canais em que a regra vale. */
enum class RuleChannels(val key: String, val label: String) {
    CALLS("calls", "Chamadas"),
    SMS("sms", "SMS"),
    BOTH("both", "Ambos");

    fun covers(channel: BlockChannel): Boolean = when (this) {
        BOTH -> true
        CALLS -> channel == BlockChannel.CALL
        SMS -> channel == BlockChannel.SMS
    }

    companion object {
        fun fromKey(key: String): RuleChannels? = entries.firstOrNull { it.key == key }
    }
}

/** O que fazer com a chamada (em SMS, silenciar equivale a bloquear: só existe a notificação). */
enum class RuleAction(val key: String, val label: String) {
    BLOCK("block", "Bloquear"),
    SILENCE("silence", "Silenciar");

    companion object {
        fun fromKey(key: String): RuleAction? = entries.firstOrNull { it.key == key }
    }
}

enum class BlockChannel { CALL, SMS }

/**
 * Uma regra de bloqueio. O padrão é comparado só com os dígitos do número ("+55 (11) 91234-5678" vira
 * "5511912345678"). Regra com padrão inutilizável ([valid] falso) fica na lista, mas nunca casa.
 */
@Immutable
data class BlockRule(
    val id: Long,
    val pattern: String,
    val match: MatchType,
    val channels: RuleChannels,
    val action: RuleAction,
    val label: String,
) {
    private val digits: String = if (match == MatchType.EXACT || match == MatchType.PREFIX) {
        BlockEngine.normalize(pattern)
    } else {
        ""
    }

    private val compiled: Pattern? = when (match) {
        MatchType.REGEX -> compileOrNull(pattern)
        MatchType.WILDCARD -> wildcardRegex(pattern)?.let { compileOrNull(it) }
        MatchType.EXACT, MatchType.PREFIX -> null
    }

    val valid: Boolean = when (match) {
        MatchType.EXACT, MatchType.PREFIX -> digits.isNotEmpty()
        MatchType.WILDCARD, MatchType.REGEX -> compiled != null
    }

    /** [numberDigits] já normalizado; [suffix] liga a comparação pelo final nas regras exatas. */
    fun matches(numberDigits: String, suffix: Boolean): Boolean {
        if (!valid) return false
        return when (match) {
            MatchType.EXACT -> numberDigits == digits ||
                (
                    suffix && numberDigits.length >= SUFFIX_MIN_DIGITS && digits.length >= SUFFIX_MIN_DIGITS &&
                        (numberDigits.endsWith(digits) || digits.endsWith(numberDigits))
                    )
            MatchType.PREFIX -> numberDigits.startsWith(digits)
            MatchType.REGEX -> compiled?.matcher(numberDigits)?.find() == true
            MatchType.WILDCARD -> compiled?.matcher(numberDigits)?.matches() == true
        }
    }

    /** Linha do arquivo: `rule<TAB>id<TAB>padrão<TAB>tipo<TAB>canais<TAB>ação<TAB>rótulo`. */
    fun serialize(): String =
        listOf("rule", id.toString(), pattern, match.key, channels.key, action.key, label).joinToString("\t")

    companion object {
        const val MAX_PATTERN: Int = 160
        const val MAX_LABEL: Int = 60

        /** Abaixo disso um número não é comparado pelo final (evita casar ramais e códigos curtos). */
        const val SUFFIX_MIN_DIGITS: Int = 10

        private val LINE_BREAKS: Regex = Regex("[\\t\\r\\n]")

        fun cleanPattern(raw: String): String = raw.replace(LINE_BREAKS, "").trim().take(MAX_PATTERN)

        fun cleanLabel(raw: String): String = raw.replace(LINE_BREAKS, " ").trim().take(MAX_LABEL)

        /** Monta a regra já saneada; null = padrão que nunca casaria (vazio, regex inválida, curinga sem dígito). */
        fun create(
            id: Long,
            pattern: String,
            match: MatchType,
            channels: RuleChannels,
            action: RuleAction,
            label: String,
        ): BlockRule? {
            val rule = BlockRule(id, cleanPattern(pattern), match, channels, action, cleanLabel(label))
            return rule.takeIf { it.valid }
        }

        /** Lê uma linha `rule<TAB>...`; null quando a linha não é uma regra bem formada. */
        fun parse(line: String): BlockRule? {
            val parts: List<String> = line.split('\t')
            if (parts.size < 7 || parts[0] != "rule") return null
            val id: Long = parts[1].trim().toLongOrNull() ?: return null
            val pattern: String = parts[2]
            if (pattern.isEmpty() || pattern.length > MAX_PATTERN) return null
            val match: MatchType = MatchType.fromKey(parts[3].trim().lowercase()) ?: return null
            val channels: RuleChannels = RuleChannels.fromKey(parts[4].trim().lowercase()) ?: return null
            val action: RuleAction = RuleAction.fromKey(parts[5].trim().lowercase()) ?: return null
            return BlockRule(id, pattern, match, channels, action, cleanLabel(parts[6]))
        }

        private fun compileOrNull(regex: String): Pattern? =
            if (regex.isEmpty()) {
                null
            } else {
                try {
                    Pattern.compile(regex)
                } catch (_: PatternSyntaxException) {
                    null
                }
            }

        /**
         * Curinga -> regex ancorada: dígitos literais, `*` qualquer sequência, `?` um dígito; o resto (espaço,
         * traço, parêntese) é ignorado. null quando não sobra nada. Só `*` vale: é "todos os números".
         */
        private fun wildcardRegex(pattern: String): String? {
            val out = StringBuilder("^")
            for (ch in pattern) {
                when {
                    ch in '0'..'9' -> out.append(ch)
                    ch == '*' -> out.append("[0-9]*")
                    ch == '?' -> out.append("[0-9]")
                }
            }
            if (out.length == 1) return null
            return out.append('$').toString()
        }
    }
}

/**
 * Configuração do bloqueio. Formato do arquivo (o mesmo do SMSKILL, de onde o recurso veio):
 * linhas `chave=valor` e linhas de regra separadas por TAB. A primeira regra que casar vence.
 */
@Immutable
data class BlockConfig(
    /** Chave geral: desligado, nada é bloqueado nem silenciado. */
    val enabled: Boolean = false,
    val blockCalls: Boolean = true,
    val blockSms: Boolean = true,
    /** Chamadas sem número (privado/anônimo). */
    val blockPrivate: Boolean = false,
    /** Chamadas de quem não está na agenda (exige READ_CONTACTS). */
    val blockNonContacts: Boolean = false,
    /** Converte "bloquear" em "silenciar" nas chamadas: a chamada segue, sem tocar. */
    val silenceCalls: Boolean = false,
    val quietEnabled: Boolean = false,
    val quietStart: String = "23:00",
    val quietEnd: String = "07:00",
    /** No horário de silêncio: rejeita em vez de só silenciar. */
    val quietBlock: Boolean = false,
    /** Regras exatas com 10+ dígitos também casam pelo final (com/sem código do país). */
    val suffixMatch: Boolean = true,
    /** Apps de SMS cujas notificações são analisadas. */
    val notifPackages: Set<String> = DEFAULT_NOTIF_PACKAGES,
    val rules: List<BlockRule> = emptyList(),
) {
    val nextRuleId: Long get() = (rules.maxOfOrNull { it.id } ?: 0L) + 1L

    fun serialize(): String {
        val sb = StringBuilder()
        sb.append("# FUSAO bloqueio de chamadas e SMS\n")
        sb.append("enabled=").append(bit(enabled)).append('\n')
        sb.append("block_calls=").append(bit(blockCalls)).append('\n')
        sb.append("block_sms=").append(bit(blockSms)).append('\n')
        sb.append("block_private=").append(bit(blockPrivate)).append('\n')
        sb.append("block_non_contacts=").append(bit(blockNonContacts)).append('\n')
        sb.append("silence_calls=").append(bit(silenceCalls)).append('\n')
        sb.append("quiet_enabled=").append(bit(quietEnabled)).append('\n')
        sb.append("quiet_start=").append(quietStart).append('\n')
        sb.append("quiet_end=").append(quietEnd).append('\n')
        sb.append("quiet_block=").append(bit(quietBlock)).append('\n')
        sb.append("suffix_match=").append(bit(suffixMatch)).append('\n')
        sb.append("notif_packages=").append(notifPackages.joinToString(",")).append('\n')
        for (rule in rules) sb.append(rule.serialize()).append('\n')
        return sb.toString()
    }

    companion object {
        val DEFAULT_NOTIF_PACKAGES: Set<String> = linkedSetOf(
            "com.samsung.android.messaging",
            "com.google.android.apps.messaging",
            "com.android.mms",
        )

        private val TIME: Regex = Regex("""([01]?[0-9]|2[0-3]):([0-5][0-9])""")
        private val PACKAGE: Regex = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")

        private fun bit(value: Boolean): String = if (value) "1" else "0"

        /** "7:5" não vale; "07:05" e "7:05" valem. null = horário inválido. */
        fun cleanTime(raw: String): String? {
            val m: MatchResult = TIME.matchEntire(raw.trim()) ?: return null
            return "%02d:%s".format(m.groupValues[1].toInt(), m.groupValues[2])
        }

        /** "a.b, c.d" -> {a.b, c.d}; entradas que não são nome de pacote caem fora. */
        fun cleanPackages(raw: String): Set<String> =
            raw.split(',', ' ', '\n', ';').map { it.trim() }.filter { PACKAGE.matches(it) }.toCollection(LinkedHashSet())

        /** null quando o texto não tem nenhuma chave nem regra (arquivo vazio ou lixo). */
        fun parse(text: String): BlockConfig? {
            var cfg = BlockConfig()
            val rules = ArrayList<BlockRule>()
            val seenIds = HashSet<Long>()
            var any = false
            for (rawLine in text.split('\n')) {
                val line: String = rawLine.removeSuffix("\r")
                if (line.startsWith("rule\t")) {
                    any = true
                    val rule: BlockRule = BlockRule.parse(line) ?: continue
                    if (seenIds.add(rule.id)) rules.add(rule)
                    continue
                }
                val trimmed: String = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val eq: Int = trimmed.indexOf('=')
                if (eq <= 0) continue
                val key: String = trimmed.substring(0, eq).trim()
                val value: String = trimmed.substring(eq + 1).trim()
                val next: BlockConfig = cfg.withKey(key, value) ?: continue
                cfg = next
                any = true
            }
            return if (any) cfg.copy(rules = rules) else null
        }

        private fun flag(value: String, default: Boolean): Boolean = when (value.lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> default
        }

        /** null = chave desconhecida (a linha é ignorada). Valor inválido mantém o que já estava. */
        private fun BlockConfig.withKey(key: String, value: String): BlockConfig? = when (key) {
            "enabled" -> copy(enabled = flag(value, enabled))
            "block_calls" -> copy(blockCalls = flag(value, blockCalls))
            "block_sms" -> copy(blockSms = flag(value, blockSms))
            "block_private" -> copy(blockPrivate = flag(value, blockPrivate))
            "block_non_contacts" -> copy(blockNonContacts = flag(value, blockNonContacts))
            "silence_calls" -> copy(silenceCalls = flag(value, silenceCalls))
            "quiet_enabled" -> copy(quietEnabled = flag(value, quietEnabled))
            "quiet_start" -> copy(quietStart = cleanTime(value) ?: quietStart)
            "quiet_end" -> copy(quietEnd = cleanTime(value) ?: quietEnd)
            "quiet_block" -> copy(quietBlock = flag(value, quietBlock))
            "suffix_match" -> copy(suffixMatch = flag(value, suffixMatch))
            "notif_packages" -> cleanPackages(value).let { if (it.isEmpty()) this else copy(notifPackages = it) }
            else -> null
        }
    }
}

/** Texto colado/exportado de regras: uma por linha, CSV (`padrão,tipo,canais,ação,rótulo`) ou linha `rule<TAB>`. */
object BlockRulesText {

    fun export(rules: List<BlockRule>): String {
        val sb = StringBuilder("# padrão,tipo,canais,ação,rótulo\n")
        for (r in rules) {
            sb.append(listOf(r.pattern, r.match.key, r.channels.key, r.action.key, r.label).joinToString(",") { csvField(it) })
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Regras lidas de [text] com ids a partir de [firstId]; linhas inválidas ou repetidas (mesmo padrão, tipo e
     * canais de uma regra em [existing] ou já lida) são contadas em [ImportResult.skipped].
     */
    fun import(text: String, existing: List<BlockRule>, firstId: Long): ImportResult {
        val out = ArrayList<BlockRule>()
        val seen = HashSet<String>()
        for (r in existing) seen.add(dedupKey(r))
        var skipped = 0
        var nextId: Long = firstId
        for (rawLine in text.split('\n')) {
            val line: String = rawLine.removeSuffix("\r")
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val draft: BlockRule? = if (line.startsWith("rule\t")) {
                BlockRule.parse(line)?.let { BlockRule.create(nextId, it.pattern, it.match, it.channels, it.action, it.label) }
            } else {
                fromCsv(parseCsvLine(line), nextId)
            }
            if (draft == null || !seen.add(dedupKey(draft))) {
                skipped++
                continue
            }
            out.add(draft)
            nextId++
        }
        return ImportResult(out, skipped)
    }

    data class ImportResult(val rules: List<BlockRule>, val skipped: Int)

    private fun dedupKey(r: BlockRule): String = r.match.key + "\u0000" + r.channels.key + "\u0000" + r.pattern

    private fun fromCsv(fields: List<String>, id: Long): BlockRule? {
        val pattern: String = fields.getOrNull(0)?.trim().orEmpty()
        if (pattern.isEmpty()) return null
        val typeKey: String = fields.getOrNull(1)?.trim()?.lowercase().orEmpty()
        val match: MatchType = if (typeKey.isEmpty()) guessMatch(pattern) else MatchType.fromKey(typeKey) ?: return null
        val channelsKey: String = fields.getOrNull(2)?.trim()?.lowercase().orEmpty()
        val channels: RuleChannels =
            if (channelsKey.isEmpty()) RuleChannels.BOTH else RuleChannels.fromKey(channelsKey) ?: return null
        val actionKey: String = fields.getOrNull(3)?.trim()?.lowercase().orEmpty()
        val action: RuleAction = if (actionKey.isEmpty()) RuleAction.BLOCK else RuleAction.fromKey(actionKey) ?: return null
        return BlockRule.create(id, pattern, match, channels, action, fields.getOrNull(4).orEmpty())
    }

    /** Sem tipo na linha: `*`/`?` = curinga, `^ $ \` = regex, senão exato. */
    fun guessMatch(pattern: String): MatchType = when {
        pattern.any { it == '^' || it == '$' || it == '\\' || it == '[' } -> MatchType.REGEX
        pattern.any { it == '*' || it == '?' } -> MatchType.WILDCARD
        else -> MatchType.EXACT
    }

    private fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    /** CSV de uma linha com aspas duplas ("a,b" e "" como aspa literal). */
    internal fun parseCsvLine(line: String): List<String> {
        val fields = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch: Char = line[i]
            when {
                quoted && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    cur.append('"')
                    i++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    fields.add(cur.toString())
                    cur.setLength(0)
                }
                else -> cur.append(ch)
            }
            i++
        }
        fields.add(cur.toString())
        return fields
    }
}
