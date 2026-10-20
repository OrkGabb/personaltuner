package com.orkgabb.fusao.pixels

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Geometria da máscara. Nada aqui depende do tamanho da tela: a máscara é um bloco
 * de [TILE_CELLS]x[TILE_CELLS] células repetido pela tela inteira (BitmapShader REPEAT),
 * então o bloco tem no máximo 128x128 px e o movimento é só um deslocamento do shader.
 *
 * Cada célula é um quadrado de [SmartPixelsConfig.cellPx] px, apagado (preto opaco)
 * ou aceso (transparente). A fração apagada é exata: (100 - aceso)% de 256 células,
 * arredondado para a célula mais próxima.
 */
object PixelMask {

    const val TILE_CELLS: Int = 16
    const val TILE_AREA: Int = TILE_CELLS * TILE_CELLS

    private const val BLOCK_CELLS: Int = 2
    private const val BLOCKS_PER_SIDE: Int = TILE_CELLS / BLOCK_CELLS
    private const val CHECKER_BLOCKS: Int = BLOCKS_PER_SIDE * BLOCKS_PER_SIDE / 2

    /** Ordem de preenchimento dentro de um bloco 2x2 do xadrez: diagonal primeiro. */
    private val BLOCK_FILL_RANK: IntArray = intArrayOf(0, 2, 3, 1)

    /**
     * Dígito do passo -> dígito de Bayer: percorre o 2x2 em volta, (0,0) (1,0) (1,1) (0,1).
     * Cada passo anda 1 célula ímpar em um eixo; a diagonal (0,0)->(1,1) não serve porque
     * o xadrez de 50% é idêntico a si mesmo deslocado na diagonal.
     */
    private val STEP_DIGIT_TO_BAYER: IntArray = intArrayOf(0, 2, 1, 3)

    /** Matriz de Bayer 16x16 (valores 0..255, cada um uma vez), índice = y * 16 + x. */
    private val BAYER_16: IntArray = bayer(TILE_CELLS)

    /** Matriz de Bayer 8x8 para ordenar os blocos do xadrez. */
    private val BAYER_8: IntArray = bayer(BLOCKS_PER_SIDE)

    /**
     * Sequência de deslocamentos: o passo k desloca a máscara para a célula cujo valor de
     * Bayer é k com os dígitos em base 4 invertidos. No Bayer o dígito alto é o nível fino
     * (vizinho 2x2) e o baixo é o grosso (8 células); sem inverter, passos seguidos andam
     * múltiplos do período do padrão e a tela não muda (75% uniforme ficava parado na maioria dos
     * passos). Invertido, todo passo mexe no nível fino. Em 256 passos cada célula da tela
     * passa por todas as posições do bloco: todo pixel fica apagado a mesma fração do
     * tempo (sem desgaste desigual do AMOLED).
     */
    private val SHIFT_ORDER: IntArray = IntArray(TILE_AREA).also { order ->
        val positionOfValue = IntArray(TILE_AREA)
        for (index in 0 until TILE_AREA) positionOfValue[BAYER_16[index]] = index
        for (step in 0 until TILE_AREA) order[step] = positionOfValue[reverseBase4(step)]
    }

    /** Quantas das 256 células do bloco ficam apagadas. */
    fun darkCellCount(litPercent: Int): Int {
        val darkPercent: Int = SmartPixelsConfig.MAX_LIT_PERCENT -
            litPercent.coerceIn(SmartPixelsConfig.MIN_LIT_PERCENT, SmartPixelsConfig.MAX_LIT_PERCENT)
        return (darkPercent * TILE_AREA + 50) / 100
    }

    /** Célula (x, y) do bloco fica apagada? x e y em 0..15. */
    fun isDark(pattern: PixelPattern, darkCells: Int, x: Int, y: Int): Boolean = when (pattern) {
        PixelPattern.UNIFORM -> BAYER_16[y * TILE_CELLS + x] < darkCells
        PixelPattern.CHECKERBOARD -> checkerRank(x, y) < darkCells
    }

    /** Deslocamento em células do passo [step] (eixo x). */
    fun shiftCellsX(step: Int): Int = SHIFT_ORDER[step and (TILE_AREA - 1)] % TILE_CELLS

    /** Deslocamento em células do passo [step] (eixo y). */
    fun shiftCellsY(step: Int): Int = SHIFT_ORDER[step and (TILE_AREA - 1)] / TILE_CELLS

    /** Lado do bloco em px para o tamanho de célula pedido. */
    fun tileSidePx(cellPx: Int): Int = TILE_CELLS * cellPx

    /**
     * Gera o bloco: preto opaco nas células apagadas, transparente nas acesas. Roda só
     * quando muda aceso/tamanho/padrão (no máximo 128x128 px = 64 KiB).
     */
    fun buildTile(cfg: SmartPixelsConfig): Bitmap {
        val cell: Int = cfg.cellPx
        val side: Int = tileSidePx(cell)
        val darkCells: Int = darkCellCount(cfg.litPercent)
        val pixels = IntArray(side * side)
        for (cy in 0 until TILE_CELLS) {
            for (cx in 0 until TILE_CELLS) {
                if (!isDark(cfg.pattern, darkCells, cx, cy)) continue
                val x0: Int = cx * cell
                val y0: Int = cy * cell
                for (py in y0 until y0 + cell) {
                    val row: Int = py * side
                    for (px in x0 until x0 + cell) pixels[row + px] = Color.BLACK
                }
            }
        }
        val bitmap: Bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, side, 0, 0, side, side)
        return bitmap
    }

    /**
     * Ordem de apagar no xadrez: só os blocos 2x2 "pretos" do tabuleiro (paridade par) entram.
     * Primeiro uma célula em cada bloco (blocos na ordem de Bayer 8x8), depois a segunda, etc.
     * Em 50% todos os blocos pretos ficam cheios: xadrez de quadrados de 2 células.
     */
    private fun checkerRank(x: Int, y: Int): Int {
        val bx: Int = x / BLOCK_CELLS
        val by: Int = y / BLOCK_CELLS
        if ((bx + by) and 1 != 0) return Int.MAX_VALUE
        // Bayer 8x8: os blocos de paridade par são exatamente os de valor < 32
        val blockRank: Int = BAYER_8[by * BLOCKS_PER_SIDE + bx]
        val inBlock: Int = (y % BLOCK_CELLS) * BLOCK_CELLS + (x % BLOCK_CELLS)
        return BLOCK_FILL_RANK[inBlock] * CHECKER_BLOCKS + blockRank
    }

    /** Inverte os 4 dígitos em base 4 de um passo 0..255, já traduzidos para dígitos de Bayer. */
    private fun reverseBase4(value: Int): Int =
        (STEP_DIGIT_TO_BAYER[value and 3] shl 6) or
            (STEP_DIGIT_TO_BAYER[(value shr 2) and 3] shl 4) or
            (STEP_DIGIT_TO_BAYER[(value shr 4) and 3] shl 2) or
            STEP_DIGIT_TO_BAYER[(value shr 6) and 3]

    /** Bayer recursivo: M(2n) = [[4M, 4M+2], [4M+3, 4M+1]]. */
    private fun bayer(size: Int): IntArray {
        var matrix = intArrayOf(0)
        var n = 1
        while (n < size) {
            val next = IntArray(4 * n * n)
            val nn: Int = 2 * n
            for (y in 0 until n) {
                for (x in 0 until n) {
                    val base: Int = 4 * matrix[y * n + x]
                    next[y * nn + x] = base
                    next[y * nn + x + n] = base + 2
                    next[(y + n) * nn + x] = base + 3
                    next[(y + n) * nn + x + n] = base + 1
                }
            }
            matrix = next
            n = nn
        }
        return matrix
    }
}
