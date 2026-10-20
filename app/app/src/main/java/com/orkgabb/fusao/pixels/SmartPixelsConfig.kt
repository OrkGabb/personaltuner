package com.orkgabb.fusao.pixels

/**
 * Padrão da máscara. O desenho de cada um vive em [PixelMask]; aqui só identidade e rótulo.
 *
 * - [UNIFORM]: pontos apagados espalhados por igual (dither ordenado de Bayer 16x16).
 * - [CHECKERBOARD]: pontos apagados agrupados em blocos 2x2 dispostos em xadrez.
 */
enum class PixelPattern(val key: String, val label: String) {
    UNIFORM("uniform", "Uniforme"),
    CHECKERBOARD("checkerboard", "Xadrez");

    companion object {
        fun fromKey(key: String?): PixelPattern = entries.firstOrNull { it.key == key } ?: UNIFORM
    }
}

/** Opção de intervalo de movimento oferecida na UI. */
data class ShiftPreset(val millis: Long, val label: String)

/**
 * Configuração do Smart Pixels. Modelo único (SSOT) lido pela UI, pelo serviço de
 * acessibilidade que desenha a máscara e pelo bloco das Configurações rápidas.
 * Toda instância que sai de [SmartPixelsStore] já passou por [normalized].
 */
data class SmartPixelsConfig(
    val enabled: Boolean = false,
    val litPercent: Int = DEFAULT_LIT_PERCENT,
    val cellPx: Int = DEFAULT_CELL_PX,
    val pattern: PixelPattern = PixelPattern.UNIFORM,
    val shiftMillis: Long = DEFAULT_SHIFT_MILLIS,
) {
    /** 100% aceso = nada a apagar: o serviço nem cria a janela. */
    val masksNothing: Boolean get() = litPercent >= MAX_LIT_PERCENT

    /** true quando o desenho do bloco (não o deslocamento) é o mesmo. */
    fun sameTile(other: SmartPixelsConfig?): Boolean =
        other != null &&
            other.litPercent == litPercent &&
            other.cellPx == cellPx &&
            other.pattern == pattern

    fun normalized(): SmartPixelsConfig = copy(
        litPercent = litPercent.coerceIn(MIN_LIT_PERCENT, MAX_LIT_PERCENT),
        cellPx = cellPx.coerceIn(MIN_CELL_PX, MAX_CELL_PX),
        shiftMillis = normalizeShift(shiftMillis),
    )

    companion object {
        const val MIN_LIT_PERCENT: Int = 50
        const val MAX_LIT_PERCENT: Int = 100
        const val DEFAULT_LIT_PERCENT: Int = 50
        const val LIT_STEP_PERCENT: Int = 5

        const val MIN_CELL_PX: Int = 1
        const val MAX_CELL_PX: Int = 8
        const val DEFAULT_CELL_PX: Int = 2

        /** 0 = parado. Abaixo de 80 ms vira 80 ms (troca mais rápida só gasta GPU e pisca). */
        const val STATIC_SHIFT_MILLIS: Long = 0L
        const val MIN_SHIFT_MILLIS: Long = 80L
        const val MAX_SHIFT_MILLIS: Long = 600_000L
        const val DEFAULT_SHIFT_MILLIS: Long = 60_000L

        val SHIFT_PRESETS: List<ShiftPreset> = listOf(
            ShiftPreset(STATIC_SHIFT_MILLIS, "Parado"),
            ShiftPreset(1_000L, "1 s"),
            ShiftPreset(10_000L, "10 s"),
            ShiftPreset(60_000L, "1 min"),
            ShiftPreset(300_000L, "5 min"),
        )

        val CELL_SIZES: List<Int> = (MIN_CELL_PX..MAX_CELL_PX).toList()

        fun normalizeShift(millis: Long): Long = when {
            millis <= STATIC_SHIFT_MILLIS -> STATIC_SHIFT_MILLIS
            else -> millis.coerceIn(MIN_SHIFT_MILLIS, MAX_SHIFT_MILLIS)
        }
    }
}

/** Estado do serviço que desenha a máscara, publicado por ele mesmo em [SmartPixelsStore]. */
enum class SmartPixelsServiceState {
    /** Serviço de acessibilidade não conectado (sem permissão ou parado pelo sistema). */
    STOPPED,

    /** Conectado, sem máscara na tela (desligado ou 100% aceso). */
    IDLE,

    /** Conectado e com a máscara na tela. */
    MASKING,
}
