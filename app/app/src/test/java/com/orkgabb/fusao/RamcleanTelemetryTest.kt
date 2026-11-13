package com.orkgabb.fusao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RamcleanTelemetryTest {
    @Test
    fun emptyLinesReturnDefaults() {
        val t = RamcleanTelemetry.fromLines(emptyList())
        assertEquals("—", t.stateLabel)
        assertFalse(t.isEnabled)
        assertEquals("", t.lastTransition)
        assertEquals("", t.lastAvailTransition)
        assertEquals("", t.lastAvailKb)
        assertNull(t.lastDeltaMb)
        assertNull(t.lastDeltaKb)
        assertNull(t.lastTrimmedCount)
        assertEquals("", t.lastMglruTag)
    }

    @Test
    fun parsesMglruDeltaAndTrimMetadata() {
        val t = RamcleanTelemetry.fromLines(
            listOf(
                "ramclean=off (só manual: game-enter não limpa)",
                "--- último ramclean no log ---",
                "01:42:10 ramclean ok free=204800->512000 avail=2621440->2988544 delta_kb=+367104 delta_mb=+358 cached=2500000->2499000 trimmed=12 mglru=0x0003:pulse rc=0/0/0",
            ),
        )
        assertEquals("off (só manual: game-enter não limpa)", t.stateLabel)
        assertFalse(t.isEnabled)
        assertEquals("204800->512000", t.lastTransition)
        assertEquals("2621440->2988544", t.lastAvailTransition)
        assertEquals("2988544", t.lastAvailKb)
        assertEquals(358, t.lastDeltaMb)
        assertEquals(367104L, t.lastDeltaKb)
        assertEquals(12, t.lastTrimmedCount)
        assertEquals("0x0003:pulse", t.lastMglruTag)
        assertEquals("0/0/0", t.lastRc)
        assertEquals("ok", t.lastStatus)
    }

    @Test
    fun parsesNoopPartialAndFailStatuses() {
        val noop = RamcleanTelemetry.fromLines(
            listOf("01:42:10 ramclean noop free=900000->900000 avail=4000000->4000000 delta_kb=0 delta_mb=0 trimmed=0 frozen_skip=4 mglru=0x0003:ttl rc=0/0/0"),
        )
        assertEquals("noop", noop.lastStatus)
        assertEquals(0, noop.lastDeltaMb)
        assertEquals(0, noop.lastTrimmedCount)

        val partial = RamcleanTelemetry.fromLines(
            listOf("01:42:11 ramclean partial free=200000->220000 avail=2000000->2040960 delta_kb=+40960 delta_mb=+40 trimmed=0 mglru=0x0003:ttl rc=1/0/0"),
        )
        assertEquals("partial", partial.lastStatus)
        assertEquals(40, partial.lastDeltaMb)

        val fail = RamcleanTelemetry.fromLines(
            listOf("ramclean fail free=100000->100000 avail=1000000->1000000 delta_kb=0 delta_mb=0 trimmed=0 mglru=off:trim rc=1/1/0"),
        )
        assertEquals("fail", fail.lastStatus)
    }

    @Test
    fun parsesNegativeDeltaAndZeroTrim() {
        val t = RamcleanTelemetry.fromLines(
            listOf(
                "ramclean ok free=300000->295904 avail=3000000->2995904 delta_kb=-4096 delta_mb=-4 trimmed=0 mglru=0x0003:ttl rc=0/0/0",
            ),
        )
        assertEquals(-4, t.lastDeltaMb)
        assertEquals(-4096L, t.lastDeltaKb)
        assertEquals(0, t.lastTrimmedCount)
        assertEquals("0x0003:ttl", t.lastMglruTag)
    }

    @Test
    fun legacySingleAvailRemainsSupported() {
        val t = RamcleanTelemetry.fromLines(
            listOf(
                "ramclean ok free=100000->200000 avail=3072000 rc=0/0",
            ),
        )
        assertEquals("100000->200000", t.lastTransition)
        assertEquals("", t.lastAvailTransition)
        assertEquals("3072000", t.lastAvailKb)
        assertNull(t.lastDeltaMb)
        assertNull(t.lastDeltaKb)
        assertEquals("0/0", t.lastRc)
    }

    @Test
    fun computesDeltaWhenAvailTransitionHasNoExplicitDeltaFields() {
        val t = RamcleanTelemetry.fromLines(
            listOf(
                "ramclean ok free=100000->300000 avail=2048000->2310144 rc=0/0/0",
            ),
        )
        assertEquals("2048000->2310144", t.lastAvailTransition)
        assertEquals("2310144", t.lastAvailKb)
        assertEquals(256, t.lastDeltaMb)
        assertEquals(262144L, t.lastDeltaKb)
    }

    @Test
    fun bridgePreservesPartialAndNoopWhenExitZero() {
        val partialLines = FusaoBridge.parseRamcleanNowResult(
            ShellRunResult(
                isSuccess = true,
                exitCode = 0,
                output = listOf(
                    "ramclean partial free=300000->500000 avail=3000000->3204800 delta_kb=+204800 delta_mb=+200 cached=2500000->2200000 trimmed=2 frozen_skip=5 mglru=0x0003:pulse rc=0/0/0",
                    "MemAvailable: 2929 MB -> 3129 MB (+200 MB) | Free: 292 -> 488 MB | trim=2 procs (frozen_skip=5) | MGLRU=0x0003:pulse (drop_caches=1)",
                ),
            ),
        )
        val partial = RamcleanTelemetry.fromLines(partialLines)
        assertEquals("partial", partial.lastStatus)
        assertEquals(200, partial.lastDeltaMb)
        assertEquals(2, partial.lastTrimmedCount)

        val noopLines = FusaoBridge.parseRamcleanNowResult(
            ShellRunResult(
                isSuccess = true,
                exitCode = 0,
                output = listOf(
                    "ramclean noop free=5000000->5000000 avail=6000000->6000000 delta_kb=0 delta_mb=0 trimmed=0 frozen_skip=4 mglru=0x0003:ttl rc=0/0/0",
                ),
            ),
        )
        val noop = RamcleanTelemetry.fromLines(noopLines)
        assertEquals("noop", noop.lastStatus)
        assertEquals(0, noop.lastDeltaMb)
        assertEquals(0, noop.lastTrimmedCount)
    }

    @Test
    fun bridgeReportsFailWhenCommandExitsNonZero() {
        val failLines = FusaoBridge.parseRamcleanNowResult(
            ShellRunResult(
                isSuccess = false,
                exitCode = 1,
                output = listOf("Permission denied"),
            ),
        )
        val failed = RamcleanTelemetry.fromLines(failLines)
        assertEquals("fail", failed.lastStatus)
    }
}
