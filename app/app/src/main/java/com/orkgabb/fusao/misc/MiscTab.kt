package com.orkgabb.fusao.misc

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orkgabb.fusao.ui.FusaoCollapsibleSection
import com.orkgabb.fusao.ui.FusaoHairlineDivider
import com.orkgabb.fusao.ui.FusaoPalette
import com.orkgabb.fusao.ui.FusaoSectionLabel
import com.orkgabb.fusao.ui.FusaoSegmentedControl
import com.orkgabb.fusao.ui.FusaoSwitchRow
import com.orkgabb.fusao.ui.HudSectionHeader
import com.orkgabb.fusao.ui.LiveSpectrumBars
import com.orkgabb.fusao.ui.SegmentedItem
import com.orkgabb.fusao.ui.TacticalPanel
import com.orkgabb.fusao.ui.TelemetryPill
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val MiscAccent: Color = FusaoPalette.CryoCyan
private val SpeakerAccent: Color = FusaoPalette.BioEmerald

/**
 * Aba Misc: utilidades reais e validadas para o Galaxy M54.
 * O Speaker Boost roda de forma independente de root e atua estritamente no alto-falante interno.
 */
@Composable
fun MiscTab(
    rooted: Boolean,
    moduleInstalled: Boolean,
    anyBusy: Boolean,
    onActionNotice: (String) -> Unit,
) {
    val ctx: Context = LocalContext.current
    val store: SpeakerBoostStore = remember(ctx) { SpeakerBoostStore.get(ctx) }
    val speakerUi: SpeakerBoostUiState by store.state.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    var sysState by remember { mutableStateOf(MiscSystemState()) }
    var sysBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (speakerUi.config.enabled && !speakerUi.runtime.running) {
            SpeakerBoostService.sync(ctx, true)
        } else if (!speakerUi.config.enabled) {
            val eval = SpeakerOutputGuard.evaluate(ctx.getSystemService(AudioManager::class.java))
            store.updateRuntime {
                it.copy(
                    route = eval.route,
                    routeDeviceName = eval.deviceLabel,
                    activePlayback = eval.activePlayback,
                )
            }
        }
    }

    LaunchedEffect(rooted) {
        if (rooted) {
            sysState = MiscSystemRepo.read()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "speaker_boost_panel") {
            SpeakerBoostPanel(
                ui = speakerUi,
                store = store,
            )
        }

        item(key = "misc_system_panel") {
            SystemTweaksPanel(
                rooted = rooted,
                moduleInstalled = moduleInstalled,
                busy = sysBusy || anyBusy,
                state = sysState,
                onSelectHz = { mode ->
                    scope.launch {
                        sysBusy = true
                        val (msg, next) = MiscSystemRepo.setDisplayHz(mode)
                        sysState = next
                        sysBusy = false
                        if (msg.isNotEmpty()) onActionNotice("Refresh rate: $msg")
                    }
                },
                onToggleTouch = { enabled ->
                    scope.launch {
                        sysBusy = true
                        val (ok, next) = MiscSystemRepo.setTouchSensitivity(enabled)
                        sysState = next
                        sysBusy = false
                        onActionNotice(if (ok) "Sensibilidade ao toque ${if (enabled) "ligada" else "desligada"}" else "FALHOU ao gravar sensibilidade")
                    }
                },
                onToggleAnim = { off ->
                    scope.launch {
                        sysBusy = true
                        val (ok, next) = MiscSystemRepo.setAnimationsOff(off)
                        sysState = next
                        sysBusy = false
                        onActionNotice(if (ok) "Animações ${if (off) "desativadas" else "restauradas"}" else "FALHOU ao ajustar animações")
                    }
                },
                onToggleStayOn = { on ->
                    scope.launch {
                        sysBusy = true
                        val (ok, next) = MiscSystemRepo.setStayOnPlugged(on)
                        sysState = next
                        sysBusy = false
                        onActionNotice(if (ok) "Cafeína na tomada ${if (on) "ligada" else "desligada"}" else "FALHOU ao ajustar stay_on")
                    }
                },
            )
        }
    }
}

@Composable
private fun SpeakerBoostPanel(
    ui: SpeakerBoostUiState,
    store: SpeakerBoostStore,
) {
    val cfg = ui.config
    val rt = ui.runtime
    var showFineTuning by rememberSaveable { mutableStateOf(false) }

    val (pillText, pillColor, filled) = when {
        !cfg.enabled -> Triple("DESLIGADO", FusaoPalette.TextMuted, false)
        rt.route == SpeakerOutputRoute.BLUETOOTH -> Triple("BT PAUSADO", FusaoPalette.IgnisAmber, true)
        rt.route == SpeakerOutputRoute.EXTERNAL -> Triple("FONE PAUSADO", FusaoPalette.IgnisAmber, true)
        rt.route == SpeakerOutputRoute.IN_CALL -> Triple("CHAMADA", FusaoPalette.IgnisAmber, true)
        rt.effectsAttached -> Triple("SPEAKER ATIVO", SpeakerAccent, true)
        rt.route == SpeakerOutputRoute.SPEAKER -> Triple("AGUARDANDO MÍDIA", MiscAccent, false)
        else -> Triple("STANDBY", FusaoPalette.TextSecondary, false)
    }

    val panelAccent = when {
        !cfg.enabled -> FusaoPalette.BorderSubtle
        rt.route == SpeakerOutputRoute.BLUETOOTH || rt.route == SpeakerOutputRoute.EXTERNAL -> FusaoPalette.IgnisAmber
        rt.effectsAttached -> SpeakerAccent
        else -> MiscAccent
    }

    val presetItems = remember {
        listOf(SpeakerPreset.SOFT, SpeakerPreset.BALANCED, SpeakerPreset.STRONG).map { p ->
            SegmentedItem(
                value = p,
                label = p.label,
                subtitle = "+${"%.1f".format(p.bassBoostDb)} dB",
            )
        }
    }

    TacticalPanel(
        modifier = Modifier.fillMaxWidth(),
        accentColor = panelAccent,
        highlighted = cfg.enabled,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HudSectionHeader(
                title = "Speaker Boost",
                subtitle = "DSP exclusivo do alto-falante · nunca atua em Bluetooth",
                accentColor = if (cfg.enabled) panelAccent else SpeakerAccent,
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (cfg.enabled) {
                            LiveSpectrumBars(
                                active = rt.effectsAttached,
                                color = SpeakerAccent,
                            )
                        }
                        TelemetryPill(
                            text = pillText,
                            color = pillColor,
                            filled = filled,
                            pulsingDot = rt.effectsAttached,
                        )
                    }
                },
            )

            FusaoSwitchRow(
                title = when {
                    !cfg.enabled -> "Desligado · áudio stock"
                    rt.route == SpeakerOutputRoute.BLUETOOTH ->
                        "Pausado por Bluetooth${rt.routeDeviceName.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()}"
                    rt.route == SpeakerOutputRoute.EXTERNAL ->
                        "Pausado · saída externa conectada"
                    rt.route == SpeakerOutputRoute.IN_CALL ->
                        "Pausado durante chamada/voz"
                    rt.effectsAttached ->
                        "Ativo · +${"%.1f".format(rt.effectiveBassDb)} dB · vol ${rt.volumePercent}%"
                    else ->
                        "Armado no speaker · entra ao tocar áudio"
                },
                checked = cfg.enabled,
                accentColor = SpeakerAccent,
                onCheckedChange = store::setEnabled,
            )

            if (rt.lastError.isNotEmpty()) {
                Text(
                    text = rt.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = FusaoPalette.CrimsonCore,
                )
            }

            if (cfg.enabled && rt.effectsAttached) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SpeakerStatChip(
                        label = "GANHO ATUAL",
                        value = "+${"%.1f".format(rt.effectiveBassDb)} dB",
                        accent = SpeakerAccent,
                        modifier = Modifier.weight(1f),
                    )
                    SpeakerStatChip(
                        label = "LOUDNESS",
                        value = "${rt.effectiveLoudnessMb / 100f} dB",
                        accent = MiscAccent,
                        modifier = Modifier.weight(1f),
                    )
                    SpeakerStatChip(
                        label = rt.ampTempC?.let { "AMP TFA" } ?: "LIMITE M54",
                        value = rt.ampTempC?.let { "$it °C" } ?: "-2.2 dBFS",
                        accent = if ((rt.ampTempC ?: 0) >= 70) FusaoPalette.IgnisAmber else FusaoPalette.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            FusaoSectionLabel("PERFIL M54 (TFA9878)")
            FusaoSegmentedControl(
                items = presetItems,
                selected = cfg.preset,
                onSelect = store::selectPreset,
                defaultAccent = SpeakerAccent,
            )

            FusaoSwitchRow(
                title = "Adaptar ao volume",
                subtitle = "Recua o ganho progressivamente acima de 60%",
                checked = cfg.adaptiveVolume,
                accentColor = SpeakerAccent,
                onCheckedChange = store::setAdaptiveVolume,
            )

            FusaoHairlineDivider()

            FusaoCollapsibleSection(
                title = "Ajuste fino (M54)",
                expanded = showFineTuning,
                onToggle = { showFineTuning = !showFineTuning },
                accentColor = MiscAccent,
                summaryTag = "+${"%.1f".format(cfg.bassBoostDb)} dB · ${cfg.loudnessMb} mB",
            ) {
                BassSlider(
                    bassDb = cfg.bassBoostDb,
                    onCommit = { db -> store.setCustomTuning(db, cfg.loudnessMb) },
                )
                LoudnessSlider(
                    loudnessMb = cfg.loudnessMb,
                    onCommit = { mb -> store.setCustomTuning(cfg.bassBoostDb, mb) },
                )
                FusaoSwitchRow(
                    title = "Religar automaticamente após reiniciar",
                    checked = cfg.startOnBoot,
                    accentColor = MiscAccent,
                    onCheckedChange = store::setStartOnBoot,
                )
                Text(
                    text = "Corte <75 Hz (-2,2 a -4,5 dB), MBC 5 bandas, controle de caixa/metalização + limiter protegem a bobina.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FusaoPalette.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun SystemTweaksPanel(
    rooted: Boolean,
    moduleInstalled: Boolean,
    busy: Boolean,
    state: MiscSystemState,
    onSelectHz: (DisplayHzMode) -> Unit,
    onToggleTouch: (Boolean) -> Unit,
    onToggleAnim: (Boolean) -> Unit,
    onToggleStayOn: (Boolean) -> Unit,
) {
    val hzItems = remember {
        listOf(DisplayHzMode.ADAPTIVE, DisplayHzMode.FIXED_120, DisplayHzMode.FIXED_60).map { mode ->
            SegmentedItem(value = mode, label = mode.label)
        }
    }

    TacticalPanel(
        modifier = Modifier.fillMaxWidth(),
        accentColor = MiscAccent,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HudSectionHeader(
                title = "Sistema & Tela",
                subtitle = if (rooted) "Chaves validadas no M54" else "Requer root",
                accentColor = MiscAccent,
                trailing = {
                    val hzLabel = when {
                        !state.loaded -> "—"
                        state.displayHzMode != DisplayHzMode.UNKNOWN -> state.displayHzMode.label.uppercase()
                        state.peakHzLabel.isNotEmpty() -> "${state.peakHzLabel} HZ"
                        else -> "—"
                    }
                    TelemetryPill(
                        text = hzLabel,
                        color = if (rooted) MiscAccent else FusaoPalette.TextMuted,
                        filled = rooted && state.loaded,
                    )
                },
            )

            FusaoSectionLabel("TAXA DE ATUALIZAÇÃO (HZ)")
            FusaoSegmentedControl(
                items = hzItems,
                selected = state.displayHzMode,
                onSelect = onSelectHz,
                enabled = rooted && moduleInstalled && !busy,
                defaultAccent = MiscAccent,
            )

            FusaoSwitchRow(
                title = "Sensibilidade ao toque (película)",
                checked = state.touchSensitivity,
                enabled = rooted && !busy,
                accentColor = MiscAccent,
                onCheckedChange = onToggleTouch,
            )
            FusaoHairlineDivider()
            FusaoSwitchRow(
                title = "Desativar animações do sistema",
                checked = state.animationsOff,
                enabled = rooted && !busy,
                accentColor = MiscAccent,
                onCheckedChange = onToggleAnim,
            )
            FusaoHairlineDivider()
            FusaoSwitchRow(
                title = "Manter tela ligada na tomada (Cafeína)",
                checked = state.stayOnPlugged,
                enabled = rooted && !busy,
                accentColor = MiscAccent,
                onCheckedChange = onToggleStayOn,
            )
        }
    }
}

@Composable
private fun BassSlider(bassDb: Float, onCommit: (Float) -> Unit) {
    var drag by remember(bassDb) { mutableFloatStateOf(bassDb) }
    val snapped = ((drag * 10f).roundToInt() / 10f).coerceIn(0f, SpeakerBoostConfig.MAX_SAFE_BASS_DB)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        FusaoSectionLabel("GANHO DE GRAVES (120–290 HZ)")
        TelemetryPill(text = "+${"%.1f".format(snapped)} dB", color = SpeakerAccent, filled = true)
    }
    Slider(
        value = drag,
        onValueChange = { drag = it },
        onValueChangeFinished = { onCommit(snapped) },
        valueRange = 0f..SpeakerBoostConfig.MAX_SAFE_BASS_DB,
        colors = SliderDefaults.colors(
            thumbColor = SpeakerAccent,
            activeTrackColor = SpeakerAccent,
            inactiveTrackColor = FusaoPalette.BorderSubtle,
        ),
    )
}

@Composable
private fun LoudnessSlider(loudnessMb: Int, onCommit: (Int) -> Unit) {
    var drag by remember(loudnessMb) { mutableIntStateOf(loudnessMb) }
    val snapped = ((drag / 50f).roundToInt() * 50).coerceIn(0, SpeakerBoostConfig.MAX_SAFE_LOUDNESS_MB)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        FusaoSectionLabel("LOUDNESS / PRESENÇA MÁX.")
        TelemetryPill(text = "+${"%.1f".format(snapped / 100f)} dB", color = MiscAccent, filled = true)
    }
    Slider(
        value = drag.toFloat(),
        onValueChange = { drag = it.roundToInt() },
        onValueChangeFinished = { onCommit(snapped) },
        valueRange = 0f..SpeakerBoostConfig.MAX_SAFE_LOUDNESS_MB.toFloat(),
        steps = 17,
        colors = SliderDefaults.colors(
            thumbColor = MiscAccent,
            activeTrackColor = MiscAccent,
            inactiveTrackColor = FusaoPalette.BorderSubtle,
        ),
    )
}

@Composable
private fun SpeakerStatChip(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = FusaoPalette.VoidBlack.copy(alpha = 0.58f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, FusaoPalette.GridHairline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = FusaoPalette.TextMuted,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                color = accent,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
