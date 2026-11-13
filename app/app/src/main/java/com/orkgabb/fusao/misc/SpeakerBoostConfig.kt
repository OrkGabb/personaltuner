package com.orkgabb.fusao.misc

import kotlin.math.roundToInt

/**
 * Perfis de ganho calibrados para o alto-falante mono do Galaxy M54 5G (`SM-M546B`, TFA9878 Smart PA).
 *
 * O SoundBooster de fábrica já aplica filtro passa-alta em ~90–120 Hz e corte em 690 Hz;
 * por isso evitamos empurrar subgraves abaixo de ~75 Hz (que desperdiçam excursão da bobina)
 * e concentramos o reforço psicoacústico entre 110 Hz e 300 Hz com limiter ativo.
 */
enum class SpeakerPreset(
    val key: String,
    val label: String,
    val bassBoostDb: Float,
    val loudnessMb: Int,
    val subtitle: String,
) {
    SOFT(
        key = "soft",
        label = "Suave",
        bassBoostDb = 3.0f,
        loudnessMb = 300,
        subtitle = "Corpo +3,0 dB · mínima compressão",
    ),
    BALANCED(
        key = "balanced",
        label = "Equilibrado",
        bassBoostDb = 4.5f,
        loudnessMb = 550,
        subtitle = "Calibrado M54 · punch +4,5 dB + limiter",
    ),
    STRONG(
        key = "strong",
        label = "Forte seguro",
        bassBoostDb = 5.8f,
        loudnessMb = 800,
        subtitle = "Teto seguro TFA9878 · recuo em vol. alto",
    ),
    CUSTOM(
        key = "custom",
        label = "Manual",
        bassBoostDb = 4.5f,
        loudnessMb = 550,
        subtitle = "Ajuste próprio dentro dos limites seguros",
    );

    companion object {
        fun fromKey(key: String?): SpeakerPreset =
            entries.firstOrNull { it.key == key } ?: BALANCED
    }
}

enum class SpeakerOutputRoute {
    SPEAKER,
    BLUETOOTH,
    EXTERNAL,
    IN_CALL,
    UNKNOWN,
}

data class SpeakerBoostConfig(
    val enabled: Boolean = false,
    val preset: SpeakerPreset = SpeakerPreset.BALANCED,
    val adaptiveVolume: Boolean = true,
    val bassBoostDb: Float = SpeakerPreset.BALANCED.bassBoostDb,
    val loudnessMb: Int = SpeakerPreset.BALANCED.loudnessMb,
    val startOnBoot: Boolean = false,
) {
    fun clamped(): SpeakerBoostConfig = copy(
        bassBoostDb = bassBoostDb.coerceIn(MIN_BASS_DB, MAX_SAFE_BASS_DB),
        loudnessMb = loudnessMb.coerceIn(0, MAX_SAFE_LOUDNESS_MB),
    )

    companion object {
        const val MIN_BASS_DB: Float = 0.0f
        const val MAX_SAFE_BASS_DB: Float = 6.0f
        const val MAX_SAFE_LOUDNESS_MB: Int = 900
        const val ADAPTIVE_KNEE: Float = 0.60f
        const val M54_MBC_BAND_COUNT: Int = 5

        /** Frequências superiores de corte das 10 bandas PostEQ do DynamicsProcessing. */
        val M54_BAND_CUTOFFS_HZ: FloatArray = floatArrayOf(
            75f,     // 0–75 Hz: alívio subsônico abaixo da ressonância / HPF do SoundBooster
            120f,    // 75–120 Hz: joelho de entrada
            185f,    // 120–185 Hz: impacto principal do grave
            290f,    // 185–290 Hz: corpo / calor acústico
            460f,    // 290–460 Hz: transição médios-graves
            800f,    // 460–800 Hz: controle de caixa (~690 Hz)
            1600f,   // 800–1600 Hz: inteligibilidade vocal
            3400f,   // 1600–3400 Hz: presença e clareza
            6200f,   // 3400–6200 Hz: suavização de estridência metálica (~4.4–4.6 kHz)
            15000f,  // 6200–15000 Hz: ar e extensão leve de agudos
        )

        /** Curva alvo de DSP (MBC + PostEQ + Limiter + Loudness) para o volume atual ([volumeRatio] em 0f..1f). */
        fun computeDsp(config: SpeakerBoostConfig, volumeRatio: Float): SpeakerDspTarget {
            val safe = config.clamped()
            val vol = volumeRatio.coerceIn(0f, 1f)

            val highVolProgress = if (safe.adaptiveVolume && vol > ADAPTIVE_KNEE) {
                ((vol - ADAPTIVE_KNEE) / (1.0f - ADAPTIVE_KNEE)).coerceIn(0f, 1f)
            } else if (!safe.adaptiveVolume && vol > 0.85f) {
                // Mesmo com adaptação desligada, protege contra saturação acima de 85%
                ((vol - 0.85f) / 0.15f).coerceIn(0f, 1f) * 0.35f
            } else {
                0f
            }

            // No volume alto, preserva grave perceptível sem saturar o DAC/TFA9878
            val bassScale = 1.0f - 0.52f * highVolProgress
            val effectiveBass = safe.bassBoostDb * bassScale
            val strengthFraction = (effectiveBass / MAX_SAFE_BASS_DB).coerceIn(0f, 1f)

            val loudness = if (safe.adaptiveVolume) {
                (safe.loudnessMb * (1.0f - highVolProgress)).roundToInt().coerceIn(0, MAX_SAFE_LOUDNESS_MB)
            } else {
                (safe.loudnessMb * (1.0f - 0.45f * highVolProgress)).roundToInt().coerceIn(0, MAX_SAFE_LOUDNESS_MB)
            }

            val bandsDb = FloatArray(M54_BAND_CUTOFFS_HZ.size)
            bandsDb[0] = -2.2f - 2.3f * strengthFraction            // alívio subsônico < 75 Hz (-2.2..-4.5 dB)
            bandsDb[1] = effectiveBass * 0.32f                       // 75–120 Hz
            bandsDb[2] = effectiveBass                               // 120–185 Hz (pico de punch)
            bandsDb[3] = effectiveBass * 0.78f                       // 185–290 Hz (corpo)
            bandsDb[4] = effectiveBass * 0.28f                       // 290–460 Hz
            bandsDb[5] = -0.6f - 0.8f * strengthFraction             // 460–800 Hz (limpeza adicional de caixa ~690 Hz)
            bandsDb[6] = 0.5f * strengthFraction                     // 800–1600 Hz
            bandsDb[7] = 1.3f * strengthFraction                     // 1600–3400 Hz (clareza vocal)
            bandsDb[8] = -0.4f * strengthFraction - 0.7f * highVolProgress // 3400–6200 Hz (controla ardência ~4.5 kHz)
            bandsDb[9] = 0.7f + 1.0f * strengthFraction * (1.0f - 0.30f * highVolProgress) // 6200–15000 Hz (ar)

            val mbcBands = listOf(
                MbcBandTarget(
                    cutoffHz = 95f,
                    thresholdDb = -18.0f - 2.0f * highVolProgress,
                    ratio = 2.4f,
                    attackMs = 4.0f,
                    releaseMs = 75.0f,
                    kneeWidthDb = 6.0f,
                    postGainDb = 0.0f,
                ),
                MbcBandTarget(
                    cutoffHz = 310f,
                    thresholdDb = -15.5f - 2.5f * highVolProgress,
                    ratio = 1.45f + 0.55f * strengthFraction + 0.45f * highVolProgress,
                    attackMs = 12.0f,
                    releaseMs = 95.0f,
                    kneeWidthDb = 8.0f,
                    postGainDb = 0.25f * strengthFraction * (1.0f - 0.6f * highVolProgress),
                ),
                MbcBandTarget(
                    cutoffHz = 1250f,
                    thresholdDb = -13.0f - 1.5f * highVolProgress,
                    ratio = 1.25f + 0.20f * highVolProgress,
                    attackMs = 15.0f,
                    releaseMs = 110.0f,
                    kneeWidthDb = 8.0f,
                    postGainDb = 0.0f,
                ),
                MbcBandTarget(
                    cutoffHz = 4800f,
                    thresholdDb = -14.0f - 2.5f * highVolProgress,
                    ratio = 1.35f + 0.35f * highVolProgress,
                    attackMs = 6.0f,
                    releaseMs = 80.0f,
                    kneeWidthDb = 7.0f,
                    postGainDb = 0.35f * strengthFraction * (1.0f - 0.5f * highVolProgress),
                ),
                MbcBandTarget(
                    cutoffHz = 20000f,
                    thresholdDb = -15.0f - 2.5f * highVolProgress,
                    ratio = 1.50f + 0.40f * highVolProgress,
                    attackMs = 3.5f,
                    releaseMs = 65.0f,
                    kneeWidthDb = 6.0f,
                    postGainDb = 0.0f,
                ),
            )

            // Headroom negativo compensa parte do pico de EQ para não clipar os estágios Samsung posteriores
            val inputGainDb = -(1.0f + 1.5f * strengthFraction) - 0.8f * highVolProgress
            val limiterThresholdDb = -2.2f - 1.6f * highVolProgress
            val limiterAttackMs = 1.0f + 1.5f * highVolProgress
            val limiterReleaseMs = 60.0f + 60.0f * highVolProgress
            val limiterRatio = 16.0f - 6.0f * highVolProgress

            return SpeakerDspTarget(
                bandCutoffsHz = M54_BAND_CUTOFFS_HZ.copyOf(),
                bandGainsDb = bandsDb,
                mbcBands = mbcBands,
                inputGainDb = inputGainDb,
                loudnessMb = loudness,
                limiterThresholdDb = limiterThresholdDb,
                limiterAttackMs = limiterAttackMs,
                limiterReleaseMs = limiterReleaseMs,
                limiterRatio = limiterRatio,
            )
        }
    }
}

data class MbcBandTarget(
    val cutoffHz: Float,
    val thresholdDb: Float,
    val ratio: Float,
    val attackMs: Float,
    val releaseMs: Float,
    val kneeWidthDb: Float,
    val postGainDb: Float,
)

data class SpeakerDspTarget(
    val bandCutoffsHz: FloatArray,
    val bandGainsDb: FloatArray,
    val mbcBands: List<MbcBandTarget>,
    val inputGainDb: Float,
    val loudnessMb: Int,
    val limiterThresholdDb: Float,
    val limiterAttackMs: Float,
    val limiterReleaseMs: Float,
    val limiterRatio: Float,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpeakerDspTarget) return false
        return bandCutoffsHz.contentEquals(other.bandCutoffsHz) &&
            bandGainsDb.contentEquals(other.bandGainsDb) &&
            mbcBands == other.mbcBands &&
            inputGainDb == other.inputGainDb &&
            loudnessMb == other.loudnessMb &&
            limiterThresholdDb == other.limiterThresholdDb &&
            limiterAttackMs == other.limiterAttackMs &&
            limiterReleaseMs == other.limiterReleaseMs &&
            limiterRatio == other.limiterRatio
    }

    override fun hashCode(): Int {
        var result = bandCutoffsHz.contentHashCode()
        result = 31 * result + bandGainsDb.contentHashCode()
        result = 31 * result + mbcBands.hashCode()
        result = 31 * result + inputGainDb.hashCode()
        result = 31 * result + loudnessMb
        result = 31 * result + limiterThresholdDb.hashCode()
        result = 31 * result + limiterAttackMs.hashCode()
        result = 31 * result + limiterReleaseMs.hashCode()
        result = 31 * result + limiterRatio.hashCode()
        return result
    }
}
