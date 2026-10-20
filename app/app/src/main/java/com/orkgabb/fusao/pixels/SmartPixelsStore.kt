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
 * Formato das preferências do Smart Pixels. Leitura pura (mapa -> [SmartPixelsSnapshot]) para
 * o mesmo caminho de carga rodar no aparelho e nos testes JVM.
 */
internal object SmartPixelsPrefs {
    const val NAME: String = "smart_pixels"
    const val KEY_ENABLED: String = "enabled"
    const val KEY_AUTO_BATTERY: String = "auto_battery"
    const val KEY_LIT: String = "lit_percent"
    const val KEY_CELL: String = "cell_px"
    const val KEY_PATTERN: String = "pattern"
    const val KEY_SHIFT: String = "shift_millis"
    const val KEY_PROFILE: String = "effective_profile"

    /** Valores gravados (SharedPreferences.getAll) -> foto normalizada; fora da faixa é recortado. */
    fun decode(values: Map<String, *>): SmartPixelsSnapshot {
        val config = SmartPixelsConfig(
            enabled = values[KEY_ENABLED] as? Boolean ?: false,
            autoBattery = values[KEY_AUTO_BATTERY] as? Boolean ?: SmartPixelsConfig.DEFAULT_AUTO_BATTERY,
            litPercent = (values[KEY_LIT] as? Number)?.toInt() ?: SmartPixelsConfig.DEFAULT_LIT_PERCENT,
            cellPx = (values[KEY_CELL] as? Number)?.toInt() ?: SmartPixelsConfig.DEFAULT_CELL_PX,
            pattern = PixelPattern.fromKey(values[KEY_PATTERN] as? String),
            shiftMillis = (values[KEY_SHIFT] as? Number)?.toLong() ?: SmartPixelsConfig.DEFAULT_SHIFT_MILLIS,
        ).normalized()
        return SmartPixelsSnapshot(
            config = config,
            effectiveProfile = SmartPixelsConfig.sanitizeProfile(values[KEY_PROFILE] as? String),
        )
    }

    /** Foto -> valores a gravar (inverso de [decode] para toda foto normalizada). */
    fun encode(snapshot: SmartPixelsSnapshot): Map<String, Any> {
        val cfg: SmartPixelsConfig = snapshot.config
        return mapOf(
            KEY_ENABLED to cfg.enabled,
            KEY_AUTO_BATTERY to cfg.autoBattery,
            KEY_LIT to cfg.litPercent,
            KEY_CELL to cfg.cellPx,
            KEY_PATTERN to cfg.pattern.key,
            KEY_SHIFT to cfg.shiftMillis,
            KEY_PROFILE to snapshot.effectiveProfile,
        )
    }
}

/**
 * Dono único do Smart Pixels: persiste config + último perfil em vigor do módulo em
 * SharedPreferences e publica a foto em [state]. UI, serviço, bloco e o receptor do
 * perfil rodam no mesmo processo e leem/escrevem só por aqui.
 */
class SmartPixelsStore private constructor(private val app: Context) {

    private val prefs: SharedPreferences = app.getSharedPreferences(SmartPixelsPrefs.NAME, Context.MODE_PRIVATE)
    private val lock: Any = Any()

    private val _state: MutableStateFlow<SmartPixelsSnapshot> = MutableStateFlow(load())
    val state: StateFlow<SmartPixelsSnapshot> = _state.asStateFlow()

    private val _serviceState: MutableStateFlow<SmartPixelsServiceState> =
        MutableStateFlow(SmartPixelsServiceState.STOPPED)
    val serviceState: StateFlow<SmartPixelsServiceState> = _serviceState.asStateFlow()

    val serviceComponent: ComponentName = ComponentName(app, SmartPixelsService::class.java)
    private val tileComponent: ComponentName = ComponentName(app, SmartPixelsTileService::class.java)

    fun update(transform: (SmartPixelsConfig) -> SmartPixelsConfig) =
        commit { it.copy(config = transform(it.config).normalized()) }

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }
    fun setAutoBattery(auto: Boolean) = update { it.copy(autoBattery = auto) }
    fun setLitPercent(percent: Int) = update { it.copy(litPercent = percent) }
    fun setCellPx(px: Int) = update { it.copy(cellPx = px) }
    fun setPattern(pattern: PixelPattern) = update { it.copy(pattern = pattern) }
    fun setShiftMillis(millis: Long) = update { it.copy(shiftMillis = millis) }

    /**
     * Perfil em vigor do módulo (broadcast do root ou leitura do app aberto). Valor
     * desconhecido é ignorado. O serviço reage pela mesma [state] que a aba e o bloco usam.
     */
    fun setEffectiveProfile(raw: String?) {
        val profile: String = SmartPixelsConfig.sanitizeProfile(raw)
        if (profile.isEmpty()) return
        commit { it.copy(effectiveProfile = profile) }
    }

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

    private fun commit(transform: (SmartPixelsSnapshot) -> SmartPixelsSnapshot) {
        val changed: Boolean = synchronized(lock) {
            val current: SmartPixelsSnapshot = _state.value
            val next: SmartPixelsSnapshot = transform(current)
            if (next == current) {
                false
            } else {
                write(next)
                _state.value = next
                true
            }
        }
        if (changed) refreshTile()
    }

    private fun refreshTile() {
        runCatching { TileService.requestListeningState(app, tileComponent) }
    }

    /** Carrega e, se algum valor gravado estava fora da faixa (ex.: ponto de 8 px), regrava o recortado. */
    private fun load(): SmartPixelsSnapshot {
        val stored: Map<String, *> = prefs.all
        val snapshot: SmartPixelsSnapshot = SmartPixelsPrefs.decode(stored)
        val encoded: Map<String, Any> = SmartPixelsPrefs.encode(snapshot)
        if (encoded.any { (key, value) -> stored.containsKey(key) && stored[key] != value }) write(snapshot)
        return snapshot
    }

    private fun write(snapshot: SmartPixelsSnapshot) {
        val editor: SharedPreferences.Editor = prefs.edit()
        for ((key, value) in SmartPixelsPrefs.encode(snapshot)) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
            }
        }
        editor.apply()
    }

    companion object {
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
