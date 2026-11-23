package com.orkgabb.fusao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Escala de resolução por jogo do Turbo (`fusao turbo scale`, módulo >= 2.15.0). */
class TurboScaleTest {

    @Test
    fun cycleFollowsPresetOrderAndWrapsToNative() {
        assertEquals("900p", nextTurboScale(null))
        assertEquals("900p", nextTurboScale("1x"))
        assertEquals("810p", nextTurboScale("900p"))
        assertEquals("720p", nextTurboScale("810p"))
        assertEquals("1x", nextTurboScale("720p"))
        assertEquals("900p", nextTurboScale("invalido"))
    }

    @Test
    fun parseReadsValidEntriesAndIgnoresNoiseOrNative() {
        val parsed = parseTurboScales(
            listOf(
                "com.hottagames.nte=810p",
                "com.HoYoverse.hkrpgoversea=720p",
                "com.xd.t3.global=900p",
                "com.roblox.client=1x",
                "# comentario",
                "linha_invalida=480p",
                "",
            ),
        )
        assertEquals(3, parsed.size)
        assertEquals("810p", parsed["com.hottagames.nte"])
        assertEquals("720p", parsed["com.HoYoverse.hkrpgoversea"])
        assertEquals("900p", parsed["com.xd.t3.global"])
        assertTrue("com.roblox.client" !in parsed)
    }

    @Test
    fun normalizeAndLabelsAreStable() {
        assertEquals("1x", turboScaleLabel(null))
        assertEquals("1x", turboScaleLabel("1x"))
        assertEquals("1x", turboScaleLabel("off"))
        assertEquals("900p", turboScaleLabel("900P"))
        assertEquals("810p", turboScaleLabel(" 810p "))
        assertEquals("720p", turboScaleLabel("720p"))
    }

    @Test
    fun launchNoticeIncludesScaleOnlyWhenDownscaled() {
        val scaled = FusaoBridge.formatTurboLaunchResult(
            ShellRunResult(
                isSuccess = true,
                exitCode = 0,
                output = listOf(
                    "turbo ok pkg=com.hottagames.nte ram_delta=+1420MB avail=4720MB mali=5/5 shaders=1 adpf=1 game_mode=1 scale=810p",
                ),
            ),
            "com.hottagames.nte",
        )
        assertEquals("Turbo ativo · 810p · RAM +1420MB (4720MB) · Mali 5/5 · ADPF", scaled)

        val native = FusaoBridge.formatTurboLaunchResult(
            ShellRunResult(
                isSuccess = true,
                exitCode = 0,
                output = listOf(
                    "turbo ok pkg=com.xd.t3.global ram_delta=+800MB avail=4300MB mali=5/5 shaders=2 adpf=1 game_mode=1 scale=1x",
                ),
            ),
            "com.xd.t3.global",
        )
        assertEquals("Turbo ativo · RAM +800MB (4300MB) · Mali 5/5 · ADPF", native)
    }
}
