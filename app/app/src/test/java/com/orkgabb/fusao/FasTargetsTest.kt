package com.orkgabb.fusao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Limite de fps por jogo (aba Jogos) <-> `fusao fas target`/`fas list` do módulo 2.12.0. */
class FasTargetsTest {

    @Test
    fun explicitCapIsASingleTargetWithoutTheLadderBelow() {
        // a escada abaixo do limite (30,45,60) prendia o jogo no degrau de baixo (catraca do fas-rs)
        assertEquals("60", fasTargetsForCap(60))
        assertEquals("30", fasTargetsForCap(30))
        assertEquals("144", fasTargetsForCap(144))
        assertEquals("auto", fasTargetsForCap(null))
        assertEquals("off", fasTargetsForCap(FAS_CAP_OFF))
    }

    @Test
    fun capOfReadsAutoOffAndOldLadders() {
        assertNull(fasCapOf(null))
        assertNull(fasCapOf(FAS_AUTO_STEPS))
        assertEquals(FAS_CAP_OFF, fasCapOf(emptyList()))
        assertEquals(60, fasCapOf(listOf(60)))
        // limite gravado por app antigo (escada abaixo + limite) segue lido pelo último degrau
        assertEquals(60, fasCapOf(listOf(30, 45, 60)))
    }

    @Test
    fun cycleReachesOffAndReturnsToAuto() {
        val i = FAS_CAP_PRESETS.indexOf(144)
        assertEquals(FAS_CAP_OFF, FAS_CAP_PRESETS[(i + 1) % FAS_CAP_PRESETS.size])
        val j = FAS_CAP_PRESETS.indexOf(FAS_CAP_OFF)
        assertNull(FAS_CAP_PRESETS[(j + 1) % FAS_CAP_PRESETS.size])
        for (cap in FAS_CAP_PRESETS) {
            assertEquals(cap, fasCapOf(parseFasToml(tomlLine("com.a.b", fasTargetsForCap(cap)))["com.a.b"]))
        }
    }

    @Test
    fun parsesOffAsAnEmptyList() {
        val lines = listOf(
            "[game_list]",
            "\"com.hottagames.nte\" = [30, 45, 60, 90, 120]",
            "# \"com.roblox.client\" = off",
            "# gerado pelo FUSAO (fas.sh)",
        )
        val map = parseFasToml(lines)
        assertEquals(listOf(30, 45, 60, 90, 120), map["com.hottagames.nte"])
        assertTrue(map.getValue("com.roblox.client").isEmpty())
        assertEquals(2, map.size)
    }

    @Test
    fun labels() {
        assertEquals("AUTO", fasCapLabel(null))
        assertEquals("OFF", fasCapLabel(FAS_CAP_OFF))
        assertEquals("45 FPS", fasCapLabel(45))
    }

    /** A linha que o fas.sh gera para o alvo (mesmo formato do `fas list`). */
    private fun tomlLine(pkg: String, targets: String): List<String> = when (targets) {
        "auto" -> listOf("\"$pkg\" = [${FAS_AUTO_STEPS.joinToString(", ")}]")
        "off" -> listOf("# \"$pkg\" = off")
        else -> listOf("\"$pkg\" = [${targets.split(",").joinToString(", ")}]")
    }
}
