package com.orkgabb.fusao

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Status rápido depois das ações; o completo só depois de `framework off` (CPU volta ao stock sem nova conferência). */
class StatusQuickTest {

    @Test
    fun frameworkOffNeedsTheFullStatus() {
        assertFalse(quickStatusAfterFlag("framework", false))
    }

    @Test
    fun otherFlagsUseTheQuickStatus() {
        for (cmd in listOf("fusaod", "framework", "perf", "dnd", "preload", "ramclean", "gos", "fas")) {
            assertTrue(cmd, quickStatusAfterFlag(cmd, true))
        }
        for (cmd in listOf("fusaod", "perf", "dnd", "preload", "ramclean", "gos", "fas")) {
            assertTrue(cmd, quickStatusAfterFlag(cmd, false))
        }
    }
}
