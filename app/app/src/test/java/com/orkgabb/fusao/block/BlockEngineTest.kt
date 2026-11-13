package com.orkgabb.fusao.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Motor do bloqueio pela saída: cada caso monta a configuração como o aparelho a guarda (texto do arquivo),
 * carrega pelo mesmo caminho do app e pergunta o que acontece com um número. Todo "bloqueia" tem o controle
 * negativo ao lado (um número ou canal que tem de passar).
 */
class BlockEngineTest {

    private val on: String = "enabled=1\n"

    private fun cfg(text: String): BlockConfig = BlockConfig.parse(text) ?: error("config ilegível: $text")

    private fun rule(pattern: String, match: String, channels: String = "both", action: String = "block", label: String = "") =
        "rule\t1\t$pattern\t$match\t$channels\t$action\t$label\n"

    private fun verdict(
        cfg: BlockConfig,
        number: String?,
        channel: BlockChannel = BlockChannel.CALL,
        contact: Boolean? = null,
        hour: Int = 12,
        minute: Int = 0,
    ): Verdict = BlockEngine.evaluate(cfg, number, channel, hour, minute) { contact }.verdict

    @Test
    fun exactRuleMatchesFormattedNumberAndCountryCodeSuffix() {
        val c = cfg(on + rule("11912345678", "exact"))
        assertEquals(Verdict.BLOCK, verdict(c, "+55 (11) 91234-5678"))
        assertEquals(Verdict.BLOCK, verdict(c, "11912345678", BlockChannel.SMS))
        assertEquals(Verdict.ALLOW, verdict(c, "11912345679"))
    }

    @Test
    fun suffixOffRequiresTheWholeNumber() {
        val c = cfg(on + "suffix_match=0\n" + rule("11912345678", "exact"))
        assertEquals(Verdict.ALLOW, verdict(c, "+5511912345678"))
        assertEquals(Verdict.BLOCK, verdict(c, "11912345678"))
    }

    @Test
    fun shortExactRuleNeverMatchesByTheEnd() {
        // código curto: "28000" não pode calar "11999928000"
        val c = cfg(on + rule("28000", "exact", "sms"))
        assertEquals(Verdict.BLOCK, verdict(c, "28000", BlockChannel.SMS))
        assertEquals(Verdict.ALLOW, verdict(c, "11999928000", BlockChannel.SMS))
    }

    @Test
    fun prefixRuleRespectsItsChannel() {
        val c = cfg(on + rule("0800", "prefix", "calls"))
        assertEquals(Verdict.BLOCK, verdict(c, "0800 123 4567"))
        assertEquals(Verdict.ALLOW, verdict(c, "0800 123 4567", BlockChannel.SMS))
        assertEquals(Verdict.ALLOW, verdict(c, "1108001234"))
    }

    @Test
    fun wildcardIsAnchoredAndIgnoresPunctuation() {
        val c = cfg(on + rule("(0800) *-555?", "wildcard"))
        assertEquals(Verdict.BLOCK, verdict(c, "0800 12 5559"))
        assertEquals(Verdict.ALLOW, verdict(c, "0800125559 0"))
        assertEquals(Verdict.ALLOW, verdict(c, "10800125559"))
    }

    @Test
    fun regexRunsOverDigitsOnly() {
        val c = cfg(on + rule("^19[0-9]{9}$", "regex"))
        assertEquals(Verdict.BLOCK, verdict(c, "(19) 99999-9999"))
        assertEquals(Verdict.ALLOW, verdict(c, "18888888888"))
    }

    @Test
    fun brokenPatternsStayListedButNeverMatch() {
        val c = cfg(on + rule("([0-9", "regex") + "rule\t2\t---\twildcard\tboth\tblock\t\n" + "rule\t3\tabc\texact\tboth\tblock\t\n")
        assertEquals(3, c.rules.size)
        assertTrue(c.rules.none { it.valid })
        assertEquals(Verdict.ALLOW, verdict(c, "123456789"))
        assertNull(BlockRule.create(9, "([0-9", MatchType.REGEX, RuleChannels.BOTH, RuleAction.BLOCK, ""))
        assertNotNull(BlockRule.create(9, "[0-9]+", MatchType.REGEX, RuleChannels.BOTH, RuleAction.BLOCK, ""))
    }

    @Test
    fun firstMatchingRuleWins() {
        val c = cfg(
            on + "rule\t1\t0800\tprefix\tboth\tsilence\tprimeira\n" + "rule\t2\t08001234567\texact\tboth\tblock\tsegunda\n",
        )
        val d = BlockEngine.evaluate(c, "08001234567", BlockChannel.CALL, 12, 0) { null }
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(1L, d.ruleId)
        assertEquals("primeira", d.reason)
    }

    @Test
    fun privateCallsOnlyWhenAskedAndNeverForSms() {
        val c = cfg(on + "block_private=1\n")
        assertEquals(Verdict.BLOCK, verdict(c, null))
        assertEquals(Verdict.BLOCK, verdict(c, "anonymous"))
        assertEquals(Verdict.ALLOW, verdict(c, null, BlockChannel.SMS))
        assertEquals(Verdict.ALLOW, verdict(cfg(on), null))
    }

    @Test
    fun nonContactsNeedsAnAnswerFromTheContactsBook() {
        val c = cfg(on + "block_non_contacts=1\n")
        assertEquals(Verdict.BLOCK, verdict(c, "11999998888", contact = false))
        assertEquals(Verdict.ALLOW, verdict(c, "11999998888", contact = true))
        // agenda indisponível (sem permissão, antes do desbloqueio): não bloqueia todo mundo
        assertEquals(Verdict.ALLOW, verdict(c, "11999998888", contact = null))
        // código curto / ramal fica de fora
        assertEquals(Verdict.ALLOW, verdict(c, "1057", contact = false))
        assertEquals(Verdict.ALLOW, verdict(c, "11999998888", BlockChannel.SMS, contact = false))
    }

    @Test
    fun contactsBookIsOnlyAskedWhenTheAnswerMatters() {
        val asked = IntArray(1)
        val lookup: () -> Boolean? = {
            asked[0]++
            false
        }
        val withRule = cfg(on + "block_non_contacts=1\n" + rule("0800", "prefix"))
        BlockEngine.evaluate(withRule, "08001234567", BlockChannel.CALL, 12, 0, lookup)
        BlockEngine.evaluate(withRule, "11999998888", BlockChannel.SMS, 12, 0, lookup)
        BlockEngine.evaluate(cfg(on), "11999998888", BlockChannel.CALL, 12, 0, lookup)
        assertEquals(0, asked[0])
        BlockEngine.evaluate(withRule, "11999998888", BlockChannel.CALL, 12, 0, lookup)
        assertEquals(1, asked[0])
    }

    @Test
    fun quietHoursCrossMidnightAndAreCallsOnly() {
        val c = cfg(on + "quiet_enabled=1\nquiet_start=23:00\nquiet_end=07:00\n")
        assertEquals(Verdict.SILENCE, verdict(c, "11999998888", hour = 1, minute = 30))
        assertEquals(Verdict.SILENCE, verdict(c, "11999998888", hour = 23, minute = 0))
        assertEquals(Verdict.ALLOW, verdict(c, "11999998888", hour = 7, minute = 0))
        assertEquals(Verdict.ALLOW, verdict(c, "11999998888", hour = 12, minute = 0))
        assertEquals(Verdict.ALLOW, verdict(c, "11999998888", BlockChannel.SMS, hour = 1, minute = 30))
        val rejecting = cfg(on + "quiet_enabled=1\nquiet_start=23:00\nquiet_end=07:00\nquiet_block=1\n")
        assertEquals(Verdict.BLOCK, verdict(rejecting, "11999998888", contact = true, hour = 3))
        assertFalse(BlockEngine.inQuietHours("08:00", "08:00", 8, 0))
        assertFalse(BlockEngine.inQuietHours("lixo", "07:00", 3, 0))
    }

    @Test
    fun silenceIsACallThingOnly() {
        val silenceRule = cfg(on + rule("11999998888", "exact", "both", "silence"))
        assertEquals(Verdict.SILENCE, verdict(silenceRule, "11999998888"))
        assertEquals(Verdict.BLOCK, verdict(silenceRule, "11999998888", BlockChannel.SMS))
        val global = cfg(on + "silence_calls=1\n" + rule("11999998888", "exact"))
        assertEquals(Verdict.SILENCE, verdict(global, "11999998888"))
        assertEquals(Verdict.BLOCK, verdict(global, "11999998888", BlockChannel.SMS))
    }

    @Test
    fun masterAndChannelSwitchesLetEverythingThrough() {
        val rules = rule("11999998888", "exact") + "block_private=1\n"
        assertEquals(Verdict.BLOCK, verdict(cfg(on + rules), "11999998888"))
        assertEquals(Verdict.ALLOW, verdict(cfg("enabled=0\n$rules"), "11999998888"))
        assertEquals(Verdict.ALLOW, verdict(cfg("enabled=0\n$rules"), null))
        // arquivo sem a chave: o padrão é desligado (nada bloqueia até o dono ligar)
        assertEquals(Verdict.ALLOW, verdict(cfg(rules), "11999998888"))
        val noCalls = cfg(on + "block_calls=0\n" + rules)
        assertEquals(Verdict.ALLOW, verdict(noCalls, "11999998888"))
        assertEquals(Verdict.BLOCK, verdict(noCalls, "11999998888", BlockChannel.SMS))
        val noSms = cfg(on + "block_sms=0\n" + rules)
        assertEquals(Verdict.ALLOW, verdict(noSms, "11999998888", BlockChannel.SMS))
        assertEquals(Verdict.BLOCK, verdict(noSms, "11999998888"))
    }

    @Test
    fun savedConfigStillBlocksAfterReload() {
        val original = BlockConfig(
            enabled = true,
            blockPrivate = true,
            quietEnabled = true,
            quietStart = "22:30",
            quietEnd = "06:15",
            suffixMatch = false,
            notifPackages = linkedSetOf("a.b", "c.d"),
            rules = listOf(
                BlockRule.create(4, "0800*", MatchType.WILDCARD, RuleChannels.CALLS, RuleAction.BLOCK, "Tele, \"marketing\"")!!,
                BlockRule.create(7, "^55119[0-9]{8}$", MatchType.REGEX, RuleChannels.SMS, RuleAction.SILENCE, "")!!,
            ),
        )
        val reloaded = cfg(original.serialize())
        assertEquals(original, reloaded)
        // o que só os dados sobreviventes respondem
        assertEquals(Verdict.BLOCK, verdict(reloaded, "0800 777 1234"))
        assertEquals(Verdict.BLOCK, verdict(reloaded, "+55 11 91234-5678", BlockChannel.SMS))
        assertEquals(Verdict.BLOCK, verdict(reloaded, null))
        assertEquals(Verdict.SILENCE, verdict(reloaded, "11999998888", hour = 22, minute = 30))
        assertEquals(8L, reloaded.nextRuleId)
        // controle: a mesma pergunta sem as regras gravadas
        val empty = cfg(original.copy(rules = emptyList(), blockPrivate = false, quietEnabled = false).serialize())
        assertEquals(Verdict.ALLOW, verdict(empty, "0800 777 1234"))
        assertEquals(Verdict.ALLOW, verdict(empty, "+55 11 91234-5678", BlockChannel.SMS))
        assertEquals(Verdict.ALLOW, verdict(empty, null))
        assertEquals(Verdict.ALLOW, verdict(empty, "11999998888", hour = 22, minute = 30))
    }

    @Test
    fun parserToleratesJunkAndRejectsAnEmptyFile() {
        assertNull(BlockConfig.parse(""))
        assertNull(BlockConfig.parse("# só comentário\n\n"))
        val c = cfg("garbage\r\nenabled=1\r\nquiet_start=99:99\r\nnotif_packages=;;\r\nrule\t1\t0800\tprefix\tboth\tblock\r\n")
        assertTrue(c.enabled)
        assertEquals("23:00", c.quietStart)
        assertEquals(BlockConfig.DEFAULT_NOTIF_PACKAGES, c.notifPackages)
        // linha de regra com 6 campos (sem o rótulo) não é regra
        assertTrue(c.rules.isEmpty())
        val dup = cfg("rule\t1\t0800\tprefix\tboth\tblock\t\nrule\t1\t0900\tprefix\tboth\tblock\t\n")
        assertEquals(listOf("0800"), dup.rules.map { it.pattern })
    }

    @Test
    fun exportedRulesComeBackAndStillBlock() {
        val rules = listOf(
            BlockRule.create(1, "0800*", MatchType.WILDCARD, RuleChannels.CALLS, RuleAction.BLOCK, "Tele, \"marketing\"")!!,
            BlockRule.create(2, "^19[0-9]{9,11}$", MatchType.REGEX, RuleChannels.BOTH, RuleAction.SILENCE, "")!!,
        )
        val back = BlockRulesText.import(BlockRulesText.export(rules), emptyList(), 10)
        assertEquals(0, back.skipped)
        assertEquals(listOf(10L, 11L), back.rules.map { it.id })
        assertEquals(rules.map { it.copy(id = 0) }, back.rules.map { it.copy(id = 0) })
        val c = BlockConfig(enabled = true, rules = back.rules)
        assertEquals(Verdict.BLOCK, verdict(c, "08007771234"))
        assertEquals(Verdict.SILENCE, verdict(c, "19999999999"))
        assertEquals(Verdict.ALLOW, verdict(BlockConfig(enabled = true), "08007771234"))
    }

    @Test
    fun importGuessesTypesSkipsRepeatsAndBadLines() {
        val text = "0800*\n11912345678\n^5511\n0800*\n,exact\nabc\n11912345678,exact,sms,block,Spam\nx,tipo-errado\n" +
            "rule\t5\t4004\tprefix\tcalls\tblock\tBanco\n"
        val existing = listOf(BlockRule.create(1, "11912345678", MatchType.EXACT, RuleChannels.BOTH, RuleAction.BLOCK, "")!!)
        val r = BlockRulesText.import(text, existing, 2)
        assertEquals(
            listOf("0800*" to MatchType.WILDCARD, "^5511" to MatchType.REGEX, "11912345678" to MatchType.EXACT, "4004" to MatchType.PREFIX),
            r.rules.map { it.pattern to it.match },
        )
        assertEquals(listOf(2L, 3L, 4L, 5L), r.rules.map { it.id })
        assertEquals(RuleChannels.SMS, r.rules[2].channels)
        assertEquals(5, r.skipped)
    }

    @Test
    fun normalizeKeepsDigitsAndCapsTheLength() {
        assertEquals("5511912345678", BlockEngine.normalize("+55 (11) 91234-5678"))
        assertEquals("", BlockEngine.normalize(null))
        assertEquals(24, BlockEngine.normalize("1".repeat(60)).length)
        assertEquals("07:05", BlockConfig.cleanTime("7:05"))
        assertNull(BlockConfig.cleanTime("7:5"))
        assertNull(BlockConfig.cleanTime("24:00"))
        assertEquals(setOf("a.b", "c.d_e"), BlockConfig.cleanPackages(" a.b , semponto; c.d_e\n9x.y"))
    }
}
