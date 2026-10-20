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

/** Por que a máscara está (ou não) ligada. */
enum class PixelsActivation {
    /** Chave manual desligada e o automático não se aplica agora. */
    OFF,

    /** Chave manual ligada (vale em qualquer perfil). */
    MANUAL,

    /** Chave manual desligada, automático ligado e perfil em vigor do módulo == battery. */
    AUTO_BATTERY,
}

/** Opção de intervalo de movimento oferecida na UI. */
data class ShiftPreset(val millis: Long, val label: String)

/**
 * Configuração do Smart Pixels. Modelo único (SSOT) lido pela UI, pelo serviço de
 * acessibilidade que desenha a máscara e pelo bloco das Configurações rápidas.
 * Toda instância que sai de [SmartPixelsStore] já passou por [normalized].
 */
data class SmartPixelsConfig(
    /** Chave manual da aba Pixels e do bloco das Configurações rápidas. */
    val enabled: Boolean = false,
    /** Liga a máscara sozinho enquanto o perfil em vigor do módulo for battery. */
    val autoBattery: Boolean = DEFAULT_AUTO_BATTERY,
    val litPercent: Int = DEFAULT_LIT_PERCENT,
    val cellPx: Int = DEFAULT_CELL_PX,
    val pattern: PixelPattern = PixelPattern.UNIFORM,
    val shiftMillis: Long = DEFAULT_SHIFT_MILLIS,
) {
    /** 100% aceso = nada a apagar: o serviço nem cria a janela. */
    val masksNothing: Boolean get() = litPercent >= MAX_LIT_PERCENT

    /**
     * Regra única de visibilidade: chave manual OU (automático E perfil em vigor == battery).
     * [effectiveProfile] é o perfil que o módulo aplicou de fato (battery vira game em jogo).
     */
    fun activation(effectiveProfile: String): PixelsActivation = when {
        enabled -> PixelsActivation.MANUAL
        autoBattery && effectiveProfile == PROFILE_BATTERY -> PixelsActivation.AUTO_BATTERY
        else -> PixelsActivation.OFF
    }

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

        /** Acima de 3 px a grade fica visível e atrapalha ler a tela. */
        const val MIN_CELL_PX: Int = 1
        const val MAX_CELL_PX: Int = 3
        const val DEFAULT_CELL_PX: Int = 2

        const val DEFAULT_AUTO_BATTERY: Boolean = true

        /** Perfis que o módulo publica; só battery liga o automático. */
        const val PROFILE_GAME: String = "game"
        const val PROFILE_BALANCED: String = "balanced"
        const val PROFILE_BATTERY: String = "battery"
        val PROFILES: Set<String> = setOf(PROFILE_GAME, PROFILE_BALANCED, PROFILE_BATTERY)

        /** Aceita só um perfil conhecido (sem espaço, minúsculo); qualquer outra coisa vira "". */
        fun sanitizeProfile(raw: String?): String {
            val clean: String = raw?.trim()?.lowercase().orEmpty()
            return if (clean in PROFILES) clean else ""
        }

        /**
         * 0 = parado. Cada passo troca de lugar TODOS os pontos apagados: a troca é visível (o dono viu
         * "piscando sem parar" com 1 s). Mínimo 1 min: contra marca na tela basta, e o olho vê no máximo
         * uma troca por minuto. Valor antigo menor (1 s / 10 s do 1.1.5) sobe para o mínimo ao carregar.
         */
        const val STATIC_SHIFT_MILLIS: Long = 0L
        const val MIN_SHIFT_MILLIS: Long = 60_000L
        const val MAX_SHIFT_MILLIS: Long = 900_000L
        const val DEFAULT_SHIFT_MILLIS: Long = 60_000L

        val SHIFT_PRESETS: List<ShiftPreset> = listOf(
            ShiftPreset(STATIC_SHIFT_MILLIS, "Parado"),
            ShiftPreset(60_000L, "1 min"),
            ShiftPreset(300_000L, "5 min"),
            ShiftPreset(900_000L, "15 min"),
        )

        val CELL_SIZES: List<Int> = (MIN_CELL_PX..MAX_CELL_PX).toList()

        fun normalizeShift(millis: Long): Long = when {
            millis <= STATIC_SHIFT_MILLIS -> STATIC_SHIFT_MILLIS
            else -> millis.coerceIn(MIN_SHIFT_MILLIS, MAX_SHIFT_MILLIS)
        }
    }
}

/**
 * Foto única do Smart Pixels: config do dono + último perfil em vigor recebido do módulo.
 * Publicada por [SmartPixelsStore.state]; UI, serviço e bloco derivam a visibilidade daqui.
 */
data class SmartPixelsSnapshot(
    val config: SmartPixelsConfig = SmartPixelsConfig(),
    /** "" = ainda não recebido; senão game, balanced ou battery. */
    val effectiveProfile: String = "",
) {
    val activation: PixelsActivation get() = config.activation(effectiveProfile)

    /** Ligado por qualquer motivo (a 100% aceso fica ligado sem desenhar nada). */
    val active: Boolean get() = activation != PixelsActivation.OFF

    /** A máscara deve estar desenhada na tela. */
    val drawsMask: Boolean get() = active && !config.masksNothing
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
