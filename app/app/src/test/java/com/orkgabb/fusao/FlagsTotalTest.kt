package com.orkgabb.fusao

import org.junit.Assert.assertEquals
import org.junit.Test

/** Aba Flags: o ramclean saiu do game-enter (módulo 2.12.0) e só conta enquanto a flag antiga estiver ligada. */
class FlagsTotalTest {

    @Test
    fun ramcleanCountsOnlyWhileOn() {
        assertEquals(7, FusaoStatus(fasSupported = true).flagsTotal)
        assertEquals(8, FusaoStatus(fasSupported = true, ramclean = true).flagsTotal)
        assertEquals(6, FusaoStatus().flagsTotal)
    }

    @Test
    fun activeCountMatchesTheVisibleKeys() {
        val st = FusaoStatus(framework = true, perf = true, gos = true, fas = true, fasSupported = true)
        assertEquals(4, st.activeFlagsCount)
        assertEquals(7, st.flagsTotal)
    }
}
