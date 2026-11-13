package com.orkgabb.fusao.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Caminho do SMS até a notificação: o receptor marca o remetente/texto bloqueado, o listener tem de reconhecer
 * a notificação que o app de mensagens monta, que não mostra o número do mesmo jeito.
 */
class BlockSuppressionTest {

    private val t0: Long = 1_000_000L
    private val minute: Long = 60_000L

    private fun digitsOf(vararg fields: String): List<String> =
        SenderFields.candidates(fields.toList()).map { BlockEngine.normalize(it) }

    @Test
    fun contactNameNotificationIsRecognisedByTheMessageText() {
        val body = "Parabens!  Voce ganhou\num premio. Ligue ja"
        val notification = "Fulano Spam Parabens! Voce ganhou um premio. Ligue ja"
        val s = BlockSuppression()
        s.add("+5511912345678", body, t0)
        assertTrue(s.matches(digitsOf("Fulano Spam"), notification, t0 + minute))
        // controle 1: sem o SMS marcado, a mesma notificação passa
        assertFalse(BlockSuppression().matches(digitsOf("Fulano Spam"), notification, t0 + minute))
        // controle 2: comparar o texto cru (como o SMSKILL fazia) não acharia: a quebra de linha e o espaço
        // duplo do SMS não existem na notificação
        assertFalse(notification.lowercase().contains(body.lowercase().trim()))
    }

    @Test
    fun differentNumberFormatIsRecognisedByTheNumber() {
        val s = BlockSuppression()
        s.add("+5511912345678", "oi", t0)
        assertTrue(s.matches(digitsOf("(11) 91234-5678"), "(11) 91234-5678 oi", t0))
        assertFalse(s.matches(digitsOf("(11) 91234-5679"), "(11) 91234-5679 oi", t0))
    }

    @Test
    fun shortCodeMatchesOnlyItself() {
        val s = BlockSuppression()
        s.add("28000", "ok", t0)
        assertTrue(s.matches(digitsOf("28000"), "28000 ok", t0))
        // um número comum terminado em 28000 não é o código curto
        assertFalse(s.matches(digitsOf("+55 11 99992-8000"), "+55 11 99992-8000 ok", t0))
    }

    @Test
    fun blockedNumberQuotedInSomeoneElsesMessageIsNotHidden() {
        val s = BlockSuppression()
        s.add("+5511912345678", "x", t0)
        // título = quem mandou (Maria); o número bloqueado só aparece no corpo
        val senders = digitsOf("Maria")
        assertEquals(emptyList<String>(), senders)
        assertFalse(s.matches(senders, "Maria me ligaram do 11 91234-5678, conhece?", t0))
    }

    @Test
    fun entriesExpire() {
        val s = BlockSuppression(ttlMillis = 15 * minute)
        s.add("+5511912345678", "mensagem com mais de doze letras", t0)
        assertTrue(s.matches(digitsOf("11912345678"), "", t0 + 15 * minute))
        assertFalse(s.matches(digitsOf("11912345678"), "mensagem com mais de doze letras", t0 + 15 * minute + 1))
    }

    @Test
    fun oldestEntryLeavesWhenFull() {
        val s = BlockSuppression(maxEntries = 3)
        for (i in 1..4) s.add("1190000000$i", null, t0 + i)
        assertFalse(s.matches(listOf("11900000001"), "", t0 + 10))
        assertTrue(s.matches(listOf("11900000002"), "", t0 + 10))
        assertTrue(s.matches(listOf("11900000004"), "", t0 + 10))
    }

    @Test
    fun shortTextIsNotAFingerprint() {
        assertEquals(null, BlockSuppression.snippetOf("Oi, tudo?"))
        assertEquals("oi, tudo bem com voce", BlockSuppression.snippetOf("  Oi,  tudo\tbem com VOCE "))
        assertEquals(64, BlockSuppression.snippetOf("a".repeat(200))?.length)
    }

    @Test
    fun senderFieldsAcceptShortCodesButNotLooseDigitsInText() {
        assertEquals(listOf("28000"), SenderFields.candidates(listOf("28000", null, "")))
        assertEquals(listOf("+55 11 91234-5678"), SenderFields.candidates(listOf("+55 11 91234-5678")))
        assertEquals(listOf("+5511912345678"), SenderFields.candidates(listOf("tel:+5511912345678", "Maria")))
        // "3 novas mensagens" e "Banco 24h" não são remetentes numéricos
        assertEquals(emptyList<String>(), SenderFields.candidates(listOf("3 novas mensagens", "Banco 24h", "12")))
        assertEquals(1, SenderFields.candidates(listOf("28000", "28000")).size)
    }
}
