package com.orkgabb.fusao.pixels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orkgabb.fusao.ui.CyberSwitch
import com.orkgabb.fusao.ui.FusaoPalette
import com.orkgabb.fusao.ui.HudSectionHeader
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
    val cfg: SmartPixelsConfig by store.config.collectAsStateWithLifecycle()
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
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "pixels_header") {
            HeaderPanel(
                cfg = cfg,
                access = access,
                masking = serviceState == SmartPixelsServiceState.MASKING,
                onToggle = store::setEnabled,
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
    cfg: SmartPixelsConfig,
    access: AccessState,
    masking: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val pillText: String = when {
        access == AccessState.NO_PERMISSION -> "SEM PERMISSÃO"
        access == AccessState.SERVICE_STOPPED -> "SERVIÇO PARADO"
        !cfg.enabled -> "DESLIGADO"
        cfg.masksNothing -> "100% ACESOS"
        masking -> "NA TELA"
        else -> "LIGANDO"
    }
    val pillColor: Color = when {
        access == AccessState.NO_PERMISSION -> FusaoPalette.CrimsonCore
        access == AccessState.SERVICE_STOPPED -> FusaoPalette.IgnisAmber
        cfg.enabled -> PixelsAccent
        else -> FusaoPalette.TextMuted
    }

    TacticalPanel(
        modifier = Modifier.fillMaxWidth(),
        accentColor = if (cfg.enabled) PixelsAccent else FusaoPalette.BorderSubtle,
        highlighted = masking,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HudSectionHeader(
                title = "Smart Pixels",
                subtitle = "Apaga uma parte dos pixels da tela",
                accentColor = PixelsAccent,
                trailing = {
                    TelemetryPill(
                        text = pillText,
                        color = pillColor,
                        filled = cfg.enabled || access != AccessState.READY,
                        pulsingDot = masking,
                    )
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (cfg.enabled) {
                        "Ligado · ${cfg.litPercent}% dos pixels acesos"
                    } else {
                        "Desligado"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = FusaoPalette.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                )
                CyberSwitch(
                    checked = cfg.enabled,
                    onCheckedChange = onToggle,
                    accentColor = PixelsAccent,
                )
            }
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
                title = if (missing) "Falta a permissão" else "Serviço parado",
                accentColor = accent,
            )
            Text(
                text = if (missing) {
                    "Sem a permissão de acessibilidade a máscara não aparece. Em Acessibilidade, " +
                        "abra \"Aplicativos instalados\" (ou \"Serviços instalados\"), toque em " +
                        "FUSAO Smart Pixels e ligue."
                } else {
                    "A permissão está dada, mas o Android não ligou o serviço. Em Acessibilidade, " +
                        "desligue e ligue de novo o FUSAO Smart Pixels."
                },
                style = MaterialTheme.typography.bodyMedium,
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
    TacticalPanel(
        modifier = Modifier.fillMaxWidth(),
        accentColor = PixelsAccent,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LitSlider(litPercent = cfg.litPercent, onCommit = store::setLitPercent)

            SectionLabel("TAMANHO DO PONTO")
            CELL_ROWS.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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

            SectionLabel("PADRÃO")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PixelPattern.entries.forEach { pattern ->
                    TacticalButton(
                        text = pattern.label,
                        subtitle = if (pattern == PixelPattern.UNIFORM) "pontos espalhados" else "quadrados 2x2",
                        onClick = { store.setPattern(pattern) },
                        filled = cfg.pattern == pattern,
                        accentColor = PixelsAccent,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            SectionLabel("MOVIMENTO · TROCA OS PIXELS APAGADOS A CADA")
            SHIFT_ROWS.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    // completa a linha para os botões terem a mesma largura
                    repeat(SHIFT_COLUMNS - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
            if (cfg.shiftMillis == SmartPixelsConfig.STATIC_SHIFT_MILLIS) {
                Text(
                    text = "Parado: os mesmos pixels ficam sempre apagados e os acesos gastam mais. " +
                        "Com o tempo isso pode marcar a tela.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FusaoPalette.IgnisAmber,
                )
            }
        }
    }
}

/** Slider local enquanto arrasta; só grava (e redesenha a máscara) ao soltar. */
@Composable
private fun LitSlider(litPercent: Int, onCommit: (Int) -> Unit) {
    var dragValue: Float by remember(litPercent) { mutableFloatStateOf(litPercent.toFloat()) }
    val shown: Int = snapLit(dragValue)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        SectionLabel("PIXELS ACESOS")
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
        text = "50% = metade apagada (mais economia e mais privacidade) · 100% = nada apagado",
        style = MaterialTheme.typography.bodySmall,
        color = FusaoPalette.TextSecondary,
    )
}

@Composable
private fun AboutPanel() {
    TacticalPanel(
        modifier = Modifier.fillMaxWidth(),
        accentColor = FusaoPalette.BorderSubtle,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HudSectionHeader(title = "Como funciona", accentColor = PixelsAccent)
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

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = FusaoPalette.TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun snapLit(value: Float): Int {
    val step: Int = SmartPixelsConfig.LIT_STEP_PERCENT
    return ((value / step).roundToInt() * step)
        .coerceIn(SmartPixelsConfig.MIN_LIT_PERCENT, SmartPixelsConfig.MAX_LIT_PERCENT)
}

/** Paradas internas do slider: (100 - 50) / 5 - 1 = 9. */
private val LIT_SLIDER_STEPS: Int =
    (SmartPixelsConfig.MAX_LIT_PERCENT - SmartPixelsConfig.MIN_LIT_PERCENT) / SmartPixelsConfig.LIT_STEP_PERCENT - 1

private const val CELL_COLUMNS: Int = 4
private const val SHIFT_COLUMNS: Int = 3
private val CELL_ROWS: List<List<Int>> = SmartPixelsConfig.CELL_SIZES.chunked(CELL_COLUMNS)
private val SHIFT_ROWS: List<List<ShiftPreset>> = SmartPixelsConfig.SHIFT_PRESETS.chunked(SHIFT_COLUMNS)

private val ABOUT_LINES: List<String> = listOf(
    "Economia: só em tela AMOLED (a do M54 é). Pixel preto é pixel desligado, então o ganho " +
        "acompanha o brilho do que está na tela: em tela clara economiza mais, em tela escura quase nada.",
    "Privacidade: com menos pixels acesos a tela fica mais escura e mais difícil de ler de longe.",
    "Movimento: os pixels apagados se revezam de tempos em tempos, para nenhum pixel gastar " +
        "mais que os outros. A troca para com a tela desligada.",
    "Permissão: usa a acessibilidade só para desenhar a máscara por cima de tudo em preto total " +
        "(janela comum fica limitada a 80%). Não lê a tela e não usa root.",
    "Atalho: bloco \"Smart Pixels\" nas Configurações rápidas (editar os blocos e arrastar).",
)
