package com.orkgabb.fusao.misc

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class SpeakerBoostRuntime(
    val running: Boolean = false,
    val route: SpeakerOutputRoute = SpeakerOutputRoute.UNKNOWN,
    val routeDeviceName: String = "",
    val effectsAttached: Boolean = false,
    val activePlayback: Boolean = false,
    val volumePercent: Int = 0,
    val effectiveBassDb: Float = 0f,
    val effectiveLoudnessMb: Int = 0,
    val ampTempC: Int? = null,
    val ampExcursionMaxMm: Float? = null,
    val lastError: String = "",
)

data class SpeakerBoostUiState(
    val config: SpeakerBoostConfig = SpeakerBoostConfig(),
    val runtime: SpeakerBoostRuntime = SpeakerBoostRuntime(),
)

/**
 * Persistência + estado reativo do Speaker Boost (apenas alto-falante interno).
 */
class SpeakerBoostStore private constructor(private val appContext: Context) {

    private val prefs: SharedPreferences =
        appContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(SpeakerBoostUiState(config = load()))
    val state: StateFlow<SpeakerBoostUiState> = _state.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        updateConfig { it.copy(enabled = enabled) }
        SpeakerBoostService.sync(appContext, _state.value.config.enabled)
    }

    fun selectPreset(preset: SpeakerPreset) {
        updateConfig { cur ->
            if (preset == SpeakerPreset.CUSTOM) {
                cur.copy(preset = SpeakerPreset.CUSTOM)
            } else {
                cur.copy(
                    preset = preset,
                    bassBoostDb = preset.bassBoostDb,
                    loudnessMb = preset.loudnessMb,
                )
            }
        }
        SpeakerBoostService.refreshIfRunning(appContext, _state.value.config.enabled)
    }

    fun setCustomTuning(bassBoostDb: Float, loudnessMb: Int) {
        updateConfig { cur ->
            cur.copy(
                preset = SpeakerPreset.CUSTOM,
                bassBoostDb = bassBoostDb,
                loudnessMb = loudnessMb,
            )
        }
        SpeakerBoostService.refreshIfRunning(appContext, _state.value.config.enabled)
    }

    fun setAdaptiveVolume(enabled: Boolean) {
        updateConfig { it.copy(adaptiveVolume = enabled) }
        SpeakerBoostService.refreshIfRunning(appContext, _state.value.config.enabled)
    }

    fun setStartOnBoot(enabled: Boolean) {
        updateConfig { it.copy(startOnBoot = enabled) }
    }

    internal fun updateRuntime(transform: (SpeakerBoostRuntime) -> SpeakerBoostRuntime) {
        _state.update { it.copy(runtime = transform(it.runtime)) }
    }

    internal fun readAmpTelemetry(): Pair<Int?, Float?> {
        val temp = runCatching {
            File("/sys/class/audio/amp/curr_temperature_0").takeIf { it.canRead() }
                ?.readText()?.trim()?.toIntOrNull()
        }.getOrNull()
        val exc = runCatching {
            File("/sys/class/audio/amp/excursion_max_0").takeIf { it.canRead() }
                ?.readText()?.trim()?.toIntOrNull()?.let { it / 1000f }
        }.getOrNull()
        return temp to exc
    }

    private fun updateConfig(transform: (SpeakerBoostConfig) -> SpeakerBoostConfig) {
        val next = transform(_state.value.config).clamped()
        save(next)
        _state.update { cur ->
            val rt = if (!next.enabled) {
                SpeakerBoostRuntime(running = false, route = cur.runtime.route)
            } else {
                cur.runtime
            }
            cur.copy(config = next, runtime = rt)
        }
        SpeakerBoostTileService.requestUpdate(appContext)
    }

    private fun load(): SpeakerBoostConfig {
        val preset = SpeakerPreset.fromKey(prefs.getString(KEY_PRESET, SpeakerPreset.BALANCED.key))
        return SpeakerBoostConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            preset = preset,
            adaptiveVolume = prefs.getBoolean(KEY_ADAPTIVE, true),
            bassBoostDb = prefs.getFloat(KEY_BASS_DB, preset.bassBoostDb),
            loudnessMb = prefs.getInt(KEY_LOUDNESS_MB, preset.loudnessMb),
            startOnBoot = prefs.getBoolean(KEY_BOOT, false),
        ).clamped()
    }

    private fun save(cfg: SpeakerBoostConfig) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, cfg.enabled)
            .putString(KEY_PRESET, cfg.preset.key)
            .putBoolean(KEY_ADAPTIVE, cfg.adaptiveVolume)
            .putFloat(KEY_BASS_DB, cfg.bassBoostDb)
            .putInt(KEY_LOUDNESS_MB, cfg.loudnessMb)
            .putBoolean(KEY_BOOT, cfg.startOnBoot)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "fusao_speaker_boost"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PRESET = "preset"
        private const val KEY_ADAPTIVE = "adaptive_volume"
        private const val KEY_BASS_DB = "bass_boost_db"
        private const val KEY_LOUDNESS_MB = "loudness_mb"
        private const val KEY_BOOT = "start_on_boot"

        @Volatile
        private var instance: SpeakerBoostStore? = null

        fun get(context: Context): SpeakerBoostStore =
            instance ?: synchronized(this) {
                instance ?: SpeakerBoostStore(context.applicationContext).also { instance = it }
            }
    }
}
