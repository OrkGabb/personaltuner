package com.orkgabb.fusao

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Módulo ausente = um aviso só: o banner fixo (MODULE_ABSENT_NOTICE) já mostra; depois de checar o módulo o
 * aviso dispensável nunca repete esse texto (antes os dois apareciam com "Módulo encore ausente.").
 */
class NoticeTest {

    @Test
    fun moduleCheckNeverDuplicatesTheAbsentBanner() {
        assertEquals("", noticeAfterModuleCheck(""))
        assertEquals("", noticeAfterModuleCheck(MODULE_ABSENT_NOTICE))
        assertEquals("", noticeAfterModuleCheck("Sem root — modo leitura. Refaça o temp-root e reabra."))
    }

    @Test
    fun moduleCheckKeepsOtherNotices() {
        assertEquals("Perfil game aplicado", noticeAfterModuleCheck("Perfil game aplicado"))
        assertEquals("módulo ausente", noticeAfterModuleCheck("módulo ausente"))
    }

    @Test
    fun absentTextNamesTheModuleNotItsInternalId() {
        assertEquals("Módulo FUSAO ausente.", MODULE_ABSENT_NOTICE)
    }
}
