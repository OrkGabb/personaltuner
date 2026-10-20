package com.orkgabb.fusao.pixels

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.TileService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dono único da [SmartPixelsConfig]: persiste em SharedPreferences e publica em
 * [config]. UI, serviço e bloco rodam no mesmo processo e leem daqui; ninguém
 * mais escreve as preferências.
 */
class SmartPixelsStore private constructor(private val app: Context) {

    private val prefs: SharedPreferences = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock: Any = Any()

    private val _config: MutableStateFlow<SmartPixelsConfig> = MutableStateFlow(read())
    val config: StateFlow<SmartPixelsConfig> = _config.asStateFlow()

    private val _serviceState: MutableStateFlow<SmartPixelsServiceState> =
        MutableStateFlow(SmartPixelsServiceState.STOPPED)
    val serviceState: StateFlow<SmartPixelsServiceState> = _serviceState.asStateFlow()

    val serviceComponent: ComponentName = ComponentName(app, SmartPixelsService::class.java)
    private val tileComponent: ComponentName = ComponentName(app, SmartPixelsTileService::class.java)

    fun update(transform: (SmartPixelsConfig) -> SmartPixelsConfig) {
        val changed: Boolean = synchronized(lock) {
            val current: SmartPixelsConfig = _config.value
            val next: SmartPixelsConfig = transform(current).normalized()
            if (next == current) {
                false
            } else {
                write(next)
                _config.value = next
                true
            }
        }
        if (changed) refreshTile()
    }

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }
    fun setLitPercent(percent: Int) = update { it.copy(litPercent = percent) }
    fun setCellPx(px: Int) = update { it.copy(cellPx = px) }
    fun setPattern(pattern: PixelPattern) = update { it.copy(pattern = pattern) }
    fun setShiftMillis(millis: Long) = update { it.copy(shiftMillis = millis) }

    /** Só o [SmartPixelsService] chama. */
    internal fun reportService(state: SmartPixelsServiceState) {
        if (_serviceState.value == state) return
        _serviceState.value = state
        refreshTile()
    }

    /** A permissão de acessibilidade do FUSAO está ligada nas configurações do sistema? */
    fun accessibilityGranted(): Boolean {
        val enabled: String = Settings.Secure.getString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(SERVICES_SEPARATOR).any { entry ->
            ComponentName.unflattenFromString(entry.trim()) == serviceComponent
        }
    }

    /**
     * Abre a lista de acessibilidade. Os extras de destaque fazem o Android puro rolar até
     * o FUSAO; a One UI ignora e só abre a lista.
     */
    fun accessibilitySettingsIntent(): Intent {
        val key: String = serviceComponent.flattenToString()
        val args = Bundle()
        args.putString(EXTRA_FRAGMENT_ARG_KEY, key)
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_FRAGMENT_ARG_KEY, key)
            .putExtra(EXTRA_SHOW_FRAGMENT_ARGS, args)
    }

    private fun refreshTile() {
        runCatching { TileService.requestListeningState(app, tileComponent) }
    }

    private fun read(): SmartPixelsConfig = SmartPixelsConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        litPercent = prefs.getInt(KEY_LIT, SmartPixelsConfig.DEFAULT_LIT_PERCENT),
        cellPx = prefs.getInt(KEY_CELL, SmartPixelsConfig.DEFAULT_CELL_PX),
        pattern = PixelPattern.fromKey(prefs.getString(KEY_PATTERN, null)),
        shiftMillis = prefs.getLong(KEY_SHIFT, SmartPixelsConfig.DEFAULT_SHIFT_MILLIS),
    ).normalized()

    private fun write(cfg: SmartPixelsConfig) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, cfg.enabled)
            .putInt(KEY_LIT, cfg.litPercent)
            .putInt(KEY_CELL, cfg.cellPx)
            .putString(KEY_PATTERN, cfg.pattern.key)
            .putLong(KEY_SHIFT, cfg.shiftMillis)
            .apply()
    }

    companion object {
        private const val PREFS_NAME: String = "smart_pixels"
        private const val KEY_ENABLED: String = "enabled"
        private const val KEY_LIT: String = "lit_percent"
        private const val KEY_CELL: String = "cell_px"
        private const val KEY_PATTERN: String = "pattern"
        private const val KEY_SHIFT: String = "shift_millis"
        private const val SERVICES_SEPARATOR: Char = ':'
        private const val EXTRA_FRAGMENT_ARG_KEY: String = ":settings:fragment_args_key"
        private const val EXTRA_SHOW_FRAGMENT_ARGS: String = ":settings:show_fragment_args"

        @Volatile
        private var instance: SmartPixelsStore? = null

        fun get(context: Context): SmartPixelsStore =
            instance ?: synchronized(this) {
                instance ?: SmartPixelsStore(context.applicationContext).also { instance = it }
            }
    }
}
