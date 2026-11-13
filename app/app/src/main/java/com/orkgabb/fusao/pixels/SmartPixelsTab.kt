package com.orkgabb.fusao.pixels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orkgabb.fusao.ui.FusaoCollapsibleSection
import com.orkgabb.fusao.ui.FusaoHairlineDivider
import com.orkgabb.fusao.ui.FusaoPalette
import com.orkgabb.fusao.ui.FusaoSectionLabel
import com.orkgabb.fusao.ui.FusaoSegmentedControl
import com.orkgabb.fusao.ui.FusaoSwitchRow
import com.orkgabb.fusao.ui.HudSectionHeader
import com.orkgabb.fusao.ui.SegmentedItem
import com.orkgabb.fusao.ui.TacticalButton
import com.orkgabb.fusao.ui.TacticalPanel
import com.orkgabb.fusao.ui.TelemetryPill
import kotlin.math.roundToInt

private val PixelsAccent: Color = FusaoPalette.SolarGold

/** Estado da permissão + serviço, na ordem em que o dono precisa resolver. */
private enum class AccessState { NO_PERMISSION, SERVICE_STOPPED, READY }

/**
 * Aba Pixels: controles do Smart Pixels. Lê e escreve só o [SmartPixelsStore]; a máscara
 * em si é desenhada pelo [SmartPixelsService]. Não depende de root.
 */
@Composable
fun SmartPixelsTab() {
    val context = LocalContext.current
    val store: SmartPixelsStore = remember(context) { SmartPixelsStore.get(context) }
    val snapshot: SmartPixelsSnapshot by store.state.collectAsStateWithLifecycle()
    val cfg: SmartPixelsConfig = snapshot.config
    val serviceState: SmartPixelsServiceState by store.serviceState.collectAsStateWithLifecycle()

    // a permissão é dada fora do app: relê ao voltar das configurações
    var granted: Boolean by remember(store) { mutableStateOf(store.accessibilityGranted()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, store) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = store.accessibilityGranted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val access: AccessState = when {
        !granted -> AccessState.NO_PERMISSION
        serviceState == SmartPixelsServiceState.STOPPED -> AccessState.SERVICE_STOPPED
        else -> AccessState.READY
    }
    val openSettings: () -> Unit = remember(store, context) {
        { runCatching { context.startActivity(store.accessibilitySettingsIntent()) } }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "pixels_header") {
            HeaderPanel(
                snapshot = snapshot,
                access = access,
                masking = serviceState == SmartPixelsServiceState.MASKING,
                onToggle = store::setEnabled,
                onToggleAuto = store::setAutoBattery,
            )
        }
        if (access != AccessState.READY) {
            item(key = "pixels_access") {
                AccessPanel(access = access, onOpenSettings = openSettings)
            }
        }
        item(key = "pixels_controls") {
            ControlsPanel(cfg = cfg, store = store)
        }
        item(key = "pixels_about") {
            AboutPanel()
        }
    }
}

@Composable
private fun HeaderPanel(
    snapshot: SmartPixelsSnapshot,
    access: AccessState,
    masking: Boolean,
    onToggle: (Boolean) -> Unit,
    onToggleAuto: (Boolean) -> Unit,
) {
    val cfg: SmartPixelsConfig = snapshot.config
    val active: Boolean = snapshot.active
    val auto: Boolean = snapshot.activation == PixelsActivation.AUTO_BATTERY
    val pillText: String = when {
        access == AccessState.NO_PERMISSION -> "SEM PERMISSÃO"
        access == AccessState.SERVICE_STOPPED -> "SERVIÇO PARADO"
        !active -> "DESLIGADO"
        cfg.masksNothing -> "100% ACESOS"
        masking && auto -> "AUTO · BATTERY"
        masking -> "NA TELA"
        else -> "LIGANDO"
    }
    val pillColor: Color = when {
        access == AccessState.NO_PERMISSION -> FusaoPalette.CrimsonCore
        access == AccessState.SERVICE_STOPPED -> FusaoPalette.IgnisAmber
        active -> PixelsAccent
        else -> FusaoPalette.TextMuted
    }
    val stateText: String = when (snapshot.activation) {
        PixelsActivation.MANUAL -> "Máscara ativa · ${cfg.litPercent}% dos pixels acesos"
        PixelsActivation.AUTO_BATTERY -> "Ativo pelo perfil battery · ${cfg.litPercent}% acesos"
        PixelsActivation.OFF -> "Ativar máscara AMOLED"
    }
    val autoSubtitle: String? = if (cfg.autoBattery) {
        when (snapshot.effectiveProfile) {
            "" -> "Abra o app ou troque de perfil uma vez"
            SmartPixelsConfig.PROFILE_BATTERY -> "No perfil battery; sai sozinha ao entrar em jogo"
            else -> "Perfil atual: ${snapshot.effectiveProfile} · entra ao mudar para battery"
        }
    } else {
        null
    }

    TacticalPanel(
        modifier = Modifier.fillMaxWidth(),
        accentColor = if (active) PixelsAccent else FusaoPalette.BorderSubtle,
        highlighted = masking,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HudSectionHeader(
                title = "Smart Pixels",
                subtitle = "Apaga grade de pixels em telas AMOLED",
                accentColor = PixelsAccent,
                trailing = {
                    TelemetryPill(
                        text = pillText,
                        color = pillColor,
                        filled = active || access != AccessState.READY,
                        pulsingDot = masking,
                    )
                },
            )
            FusaoSwitchRow(
                title = stateText,
                checked = cfg.enabled,
                accentColor = PixelsAccent,
                onCheckedChange = onToggle,
            )
            FusaoHairlineDivider()
            FusaoSwitchRow(
                title = "Ligar sozinho no perfil battery",
                subtitle = autoSubtitle,
                checked = cfg.autoBattery,
                accentColor = PixelsAccent,
                onCheckedChange = onToggleAuto,
            )
        }
    }
}

@Composable
private fun AccessPanel(access: AccessState, onOpenSettings: () -> Unit) {
    val missing: Boolean = access == AccessState.NO_PERMISSION
    val accent: Color = if (missing) FusaoPalette.CrimsonCore else FusaoPalette.IgnisAmber

    TacticalPanel(
        modifier = Modifier.fillMaxWidth(),
        accentColor = accent,
        highlighted = true,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HudSectionHeader(
                title = if (missing) "Permissão de Acessibilidade" else "Serviço parado",
                accentColor = accent,
            )
            Text(
                text = if (missing) {
                    "Em Acessibilidade → Aplicativos instalados, ative o FUSAO Smart Pixels para desenhar sobre a tela."
                } else {
                    "A permissão está concedida, mas o sistema parou o serviço. Desligue e religue em Acessibilidade."
                },
                style = MaterialTheme.typography.bodySmall,
                color = FusaoPalette.TextSecondary,
            )
            TacticalButton(
                text = "Abrir acessibilidade",
                onClick = onOpenSettings,
                filled = true,
                accentColor = accent,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ControlsPanel(cfg: SmartPixelsConfig, store: SmartPixelsStore) {
    val patternItems = remember {
        PixelPattern.entries.map { p ->
            SegmentedItem(
                value = p,
                label = p.label,
                subtitle = if (p == PixelPattern.UNIFORM) "espalhado" else "2x2",
            )
        }
    }

    TacticalPanel(
        modifier = Modifier.fillMaxWidth(),
        accentColor = PixelsAccent,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LitSlider(litPercent = cfg.litPercent, onCommit = store::setLitPercent)

            FusaoHairlineDivider()

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FusaoSectionLabel("PADRÃO")
                FusaoSegmentedControl(
                    items = patternItems,
                    selected = cfg.pattern,
                    onSelect = store::setPattern,
                    defaultAccent = PixelsAccent,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FusaoSectionLabel("TAMANHO DO PONTO")
                CELL_ROWS.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        row.forEach { px ->
                            TacticalButton(
                                text = "$px px",
                                onClick = { store.setCellPx(px) },
                                filled = cfg.cellPx == px,
                                accentColor = PixelsAccent,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FusaoSectionLabel("TROCA ANTI BURN-IN")
                SHIFT_ROWS.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        row.forEach { preset ->
                            TacticalButton(
                                text = preset.label,
                                onClick = { store.setShiftMillis(preset.millis) },
                                filled = cfg.shiftMillis == preset.millis,
                                accentColor = PixelsAccent,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(SHIFT_COLUMNS - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }
                if (cfg.shiftMillis == SmartPixelsConfig.STATIC_SHIFT_MILLIS) {
                    Text(
                        text = "Parado: sem alternância os mesmos subpixels ficam sempre acesos e podem desgastar a tela.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FusaoPalette.IgnisAmber,
                    )
                }
            }
        }
    }
}

/** Slider local enquanto arrasta; só grava (e redesenha a máscara) ao soltar. */
@Composable
private fun LitSlider(litPercent: Int, onCommit: (Int) -> Unit) {
    var dragValue: Float by remember(litPercent) { mutableFloatStateOf(litPercent.toFloat()) }
    val shown: Int = snapLit(dragValue)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FusaoSectionLabel("PIXELS ACESOS")
            TelemetryPill(text = "$shown%", color = PixelsAccent, filled = true)
        }
        Slider(
            value = dragValue,
            onValueChange = { dragValue = it },
            onValueChangeFinished = { onCommit(snapLit(dragValue)) },
            valueRange = SmartPixelsConfig.MIN_LIT_PERCENT.toFloat()..SmartPixelsConfig.MAX_LIT_PERCENT.toFloat(),
            steps = LIT_SLIDER_STEPS,
            colors = SliderDefaults.colors(
                thumbColor = PixelsAccent,
                activeTrackColor = PixelsAccent,
                inactiveTrackColor = FusaoPalette.BorderSubtle,
                activeTickColor = FusaoPalette.VoidBlack.copy(alpha = 0.5f),
                inactiveTickColor = FusaoPalette.TextMuted.copy(alpha = 0.5f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "50% apaga metade (máxima economia/privacidade) · 100% tudo aceso",
            style = MaterialTheme.typography.bodySmall,
            color = FusaoPalette.TextMuted,
        )
    }
}

@Composable
private fun AboutPanel() {
    var expanded by rememberSaveable { mutableStateOf(false) }

    TacticalPanel(
        modifier = Modifier.fillMaxWidth(),
        accentColor = FusaoPalette.BorderSubtle,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        FusaoCollapsibleSection(
            title = "Como funciona · AMOLED & Quick Tile",
            expanded = expanded,
            onToggle = { expanded = !expanded },
            accentColor = PixelsAccent,
        ) {
            ABOUT_LINES.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = FusaoPalette.TextSecondary,
                )
            }
        }
    }
}

private fun snapLit(value: Float): Int {
    val step: Int = SmartPixelsConfig.LIT_STEP_PERCENT
    return ((value / step).roundToInt() * step)
        .coerceIn(SmartPixelsConfig.MIN_LIT_PERCENT, SmartPixelsConfig.MAX_LIT_PERCENT)
}

/** Paradas internas do slider: (100 - 50) / 5 - 1 = 9. */
private val LIT_SLIDER_STEPS: Int =
    (SmartPixelsConfig.MAX_LIT_PERCENT - SmartPixelsConfig.MIN_LIT_PERCENT) / SmartPixelsConfig.LIT_STEP_PERCENT - 1

private const val CELL_COLUMNS: Int = 3
private const val SHIFT_COLUMNS: Int = 4
private val CELL_ROWS: List<List<Int>> = SmartPixelsConfig.CELL_SIZES.chunked(CELL_COLUMNS)
private val SHIFT_ROWS: List<List<ShiftPreset>> = SmartPixelsConfig.SHIFT_PRESETS.chunked(SHIFT_COLUMNS)

private val ABOUT_LINES: List<String> = listOf(
    "Economia: em telas AMOLED (M54), pixel preto é pixel desligado; em telas claras economiza mais.",
    "Privacidade: reduz o brilho percebido e dificulta leitura lateral.",
    "Anti burn-in: alterna os subpixels periodicamente e pausa quando a tela apaga.",
    "Permissão: acessibilidade apenas para sobrepor a máscara preta; sem leitura de conteúdo e sem root.",
    "Atalho rápido: adicione o bloco \"Smart Pixels\" nas Configurações rápidas.",
)
