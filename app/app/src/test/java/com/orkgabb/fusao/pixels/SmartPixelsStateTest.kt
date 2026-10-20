package com.orkgabb.fusao.pixels

import com.orkgabb.fusao.FusaoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prova pelo resultado: parte dos valores gravados como estão no aparelho (mapa do
 * SharedPreferences.getAll), passa pelo mesmo [SmartPixelsPrefs.decode] que o store usa ao
 * carregar e exige que a foto resultante diga se a máscara aparece ou não.
 */
class SmartPixelsStateTest {

    /** Gravação de um app 1.1.5: ponto de 8 px, sem chave de automático nem perfil. */
    private val storedBy115: Map<String, Any> = mapOf(
        SmartPixelsPrefs.KEY_ENABLED to false,
        SmartPixelsPrefs.KEY_LIT to 60,
        SmartPixelsPrefs.KEY_CELL to 8,
        SmartPixelsPrefs.KEY_PATTERN to PixelPattern.CHECKERBOARD.key,
        SmartPixelsPrefs.KEY_SHIFT to 10_000L,
    )

    @Test
    fun oldEightPixelCellLoadsClampedAndIsRewrittenClamped() {
        val loaded: SmartPixelsSnapshot = SmartPixelsPrefs.decode(storedBy115)
        assertEquals(3, loaded.config.cellPx)
        // o resto do que o dono escolheu sobrevive à carga
        assertEquals(60, loaded.config.litPercent)
        assertEquals(PixelPattern.CHECKERBOARD, loaded.config.pattern)
        // 10 s piscava visível: sobe para o mínimo de 1 min (o resto da escolha fica)
        assertEquals(SmartPixelsConfig.MIN_SHIFT_MILLIS, loaded.config.shiftMillis)
        // o que o store regrava é o recortado, e reler dá a mesma foto
        val rewritten: Map<String, Any> = SmartPixelsPrefs.encode(loaded)
        assertEquals(3, rewritten[SmartPixelsPrefs.KEY_CELL])
        assertEquals(loaded, SmartPixelsPrefs.decode(rewritten))
    }

    @Test
    fun oneSecondMovementFromTheDeviceLoadsAsOneMinute() {
        // aparelho 2026-10-20: shift_millis=1000 gravado pelo 1.1.6 = máscara "piscando sem parar"
        val loaded: SmartPixelsSnapshot = SmartPixelsPrefs.decode(storedBy115 + (SmartPixelsPrefs.KEY_SHIFT to 1_000L))
        assertEquals(60_000L, loaded.config.shiftMillis)
        assertEquals(60_000L, SmartPixelsPrefs.encode(loaded)[SmartPixelsPrefs.KEY_SHIFT])
        assertTrue(SmartPixelsConfig.SHIFT_PRESETS.all { it.millis == 0L || it.millis >= 60_000L })
    }

    @Test
    fun everyOfferedCellSizeIsAtMostThree() {
        assertEquals(listOf(1, 2, 3), SmartPixelsConfig.CELL_SIZES)
        assertEquals(2, SmartPixelsPrefs.decode(emptyMap<String, Any>()).config.cellPx)
    }

    @Test
    fun upgradeFrom115TurnsAutoOnAndBatteryShowsMaskWithSavedLook() {
        val loaded: SmartPixelsSnapshot = SmartPixelsPrefs.decode(
            storedBy115 + (SmartPixelsPrefs.KEY_PROFILE to "battery"),
        )
        assertTrue(loaded.config.autoBattery)
        assertEquals(PixelsActivation.AUTO_BATTERY, loaded.activation)
        assertTrue(loaded.drawsMask)
        assertEquals(60, loaded.config.litPercent)
    }

    @Test
    fun gameSessionHidesAutoMaskAndLeavingBringsItBack() {
        val start: SmartPixelsSnapshot = SmartPixelsPrefs.decode(
            storedBy115 + (SmartPixelsPrefs.KEY_PROFILE to "battery"),
        )
        val seen: List<Boolean> = listOf("battery", "game", "battery").map { profile ->
            start.copy(effectiveProfile = SmartPixelsConfig.sanitizeProfile(profile)).drawsMask
        }
        assertEquals(listOf(true, false, true), seen)
    }

    @Test
    fun manualSwitchWinsInAnyProfileAndAutoOffNeverShowsByItself() {
        val manual = SmartPixelsSnapshot(SmartPixelsConfig(enabled = true, autoBattery = false), "game")
        assertEquals(PixelsActivation.MANUAL, manual.activation)
        assertTrue(manual.drawsMask)

        val autoOff = SmartPixelsSnapshot(SmartPixelsConfig(enabled = false, autoBattery = false), "battery")
        assertEquals(PixelsActivation.OFF, autoOff.activation)
        assertFalse(autoOff.drawsMask)

        val balanced = SmartPixelsSnapshot(SmartPixelsConfig(), "balanced")
        assertFalse(balanced.drawsMask)

        // 100% aceso: ligado, mas sem nada a desenhar
        val full = SmartPixelsSnapshot(SmartPixelsConfig(litPercent = 100), "battery")
        assertTrue(full.active)
        assertFalse(full.drawsMask)
    }

    @Test
    fun appStatusReadInGameFeedsGameProfile() {
        // battery escolhido + jogo em foco: o módulo publica profile_effective=game
        val status: FusaoStatus = FusaoStatus.parse(
            listOf("profile=battery", "profile_effective=game", "profile_state=applied"),
            moduleInstalled = true,
        )
        val snapshot = SmartPixelsSnapshot(
            SmartPixelsConfig(),
            SmartPixelsConfig.sanitizeProfile(status.effectiveProfile),
        )
        assertEquals("game", snapshot.effectiveProfile)
        assertFalse(snapshot.drawsMask)

        // módulo antigo sem profile_effective: vale o escolhido
        val old: FusaoStatus = FusaoStatus.parse(listOf("profile=battery"), moduleInstalled = true)
        assertEquals("battery", old.effectiveProfile)
    }

    @Test
    fun unknownProfilesAreIgnored() {
        assertEquals("battery", SmartPixelsConfig.sanitizeProfile(" BATTERY\n"))
        assertEquals("", SmartPixelsConfig.sanitizeProfile("powersave"))
        assertEquals("", SmartPixelsConfig.sanitizeProfile(null))
        assertEquals("", SmartPixelsPrefs.decode(mapOf(SmartPixelsPrefs.KEY_PROFILE to "lixo")).effectiveProfile)
    }
}
