package com.orkgabb.fusao.pixels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prova pelo resultado: conta células apagadas de verdade (o que a tela mostra) e o
 * desgaste acumulado por célula da tela ao longo do movimento, não a tabela interna.
 */
class PixelMaskTest {

    private val cells: Int = PixelMask.TILE_CELLS

    /** Células apagadas da tela na posição (sx, sy) com o deslocamento do passo [step]. */
    private fun screenDark(pattern: PixelPattern, darkCells: Int, step: Int, sx: Int, sy: Int): Boolean {
        // o shader desloca o bloco por +shift: a célula da tela sx lê o bloco em sx - shift
        val tx: Int = Math.floorMod(sx - PixelMask.shiftCellsX(step), cells)
        val ty: Int = Math.floorMod(sy - PixelMask.shiftCellsY(step), cells)
        return PixelMask.isDark(pattern, darkCells, tx, ty)
    }

    @Test
    fun litPercentTurnsOffTheExactFractionOfCells() {
        for (pattern in PixelPattern.entries) {
            for (lit in SmartPixelsConfig.MIN_LIT_PERCENT..SmartPixelsConfig.MAX_LIT_PERCENT) {
                val dark: Int = PixelMask.darkCellCount(lit)
                var counted = 0
                for (y in 0 until cells) for (x in 0 until cells) {
                    if (PixelMask.isDark(pattern, dark, x, y)) counted++
                }
                assertEquals("$pattern $lit%", dark, counted)
                val litMeasured: Double = 100.0 * (PixelMask.TILE_AREA - counted) / PixelMask.TILE_AREA
                assertTrue("$pattern $lit% mediu $litMeasured", Math.abs(litMeasured - lit) <= 0.2)
            }
        }
    }

    @Test
    fun hundredPercentLeavesEverythingLit() {
        assertEquals(0, PixelMask.darkCellCount(100))
        assertTrue(SmartPixelsConfig(litPercent = 100).masksNothing)
    }

    @Test
    fun halfUniformIsSingleCellCheckerboard() {
        val dark: Int = PixelMask.darkCellCount(50)
        for (y in 0 until cells) for (x in 0 until cells) {
            assertEquals("($x,$y)", (x + y) % 2 == 0, PixelMask.isDark(PixelPattern.UNIFORM, dark, x, y))
        }
    }

    @Test
    fun halfCheckerboardIsTwoByTwoBlocks() {
        val dark: Int = PixelMask.darkCellCount(50)
        for (y in 0 until cells) for (x in 0 until cells) {
            val expected: Boolean = (x / 2 + y / 2) % 2 == 0
            assertEquals("($x,$y)", expected, PixelMask.isDark(PixelPattern.CHECKERBOARD, dark, x, y))
        }
    }

    @Test
    fun movementWearsEveryScreenCellEqually() {
        for (pattern in PixelPattern.entries) {
            for (lit in 50..95 step 5) {
                val dark: Int = PixelMask.darkCellCount(lit)
                for (sy in 0 until cells) for (sx in 0 until cells) {
                    var darkSteps = 0
                    for (step in 0 until PixelMask.TILE_AREA) {
                        if (screenDark(pattern, dark, step, sx, sy)) darkSteps++
                    }
                    assertEquals("$pattern $lit% célula ($sx,$sy)", dark, darkSteps)
                }
            }
        }
    }

    @Test
    fun everyStepActuallyMovesTheMask() {
        for (pattern in PixelPattern.entries) for (lit in 50..95 step 5) {
            val dark: Int = PixelMask.darkCellCount(lit)
            for (step in 0 until PixelMask.TILE_AREA) {
                var changed = 0
                for (sy in 0 until cells) for (sx in 0 until cells) {
                    if (screenDark(pattern, dark, step, sx, sy) != screenDark(pattern, dark, step + 1, sx, sy)) changed++
                }
                assertTrue("$pattern $lit% passo $step não mudou nada", changed > 0)
            }
        }
    }

    @Test
    fun configClampsToSupportedRange() {
        val cfg = SmartPixelsConfig(litPercent = 10, cellPx = 40, shiftMillis = 5).normalized()
        assertEquals(SmartPixelsConfig.MIN_LIT_PERCENT, cfg.litPercent)
        assertEquals(SmartPixelsConfig.MAX_CELL_PX, cfg.cellPx)
        assertEquals(SmartPixelsConfig.MIN_SHIFT_MILLIS, cfg.shiftMillis)
        assertEquals(0L, SmartPixelsConfig(shiftMillis = -3).normalized().shiftMillis)
        assertEquals(PixelPattern.UNIFORM, PixelPattern.fromKey("lixo"))
        assertEquals(PixelPattern.CHECKERBOARD, PixelPattern.fromKey(PixelPattern.CHECKERBOARD.key))
    }
}
