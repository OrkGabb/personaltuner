package com.orkgabb.fusao

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orkgabb.fusao.ui.AppIconBadge
import com.orkgabb.fusao.ui.CyberSwitch
import com.orkgabb.fusao.ui.FusaoAmbientCanvas
import com.orkgabb.fusao.ui.FusaoNavIcon
import com.orkgabb.fusao.ui.FusaoPalette
import com.orkgabb.fusao.ui.FusaoTheme
import com.orkgabb.fusao.ui.FusaoVectorGlyph
import com.orkgabb.fusao.ui.HudSectionHeader
import com.orkgabb.fusao.ui.PreloadCountdownGauge
import com.orkgabb.fusao.ui.ReactorCoreEmblem
import com.orkgabb.fusao.ui.StatusPulseDot
import com.orkgabb.fusao.ui.TacticalButton
import com.orkgabb.fusao.ui.TacticalPanel
import com.orkgabb.fusao.ui.TelemetryPill
import com.orkgabb.fusao.ui.highlightTelemetryLine

private data class FlagSpec(
    val cmd: String,
    val title: String,
    val desc: String,
    val accent: Color,
)

private val FLAG_SPECS = listOf(
    FlagSpec(
        cmd = "fusaod",
        title = "fusaod",
        desc = "Pisos de CPU/GPU/memória em jogo (perfis game e balanced; fica fora com o FAS)",
        accent = FusaoPalette.CryoCyan,
    ),
    FlagSpec(
        cmd = "framework",
        title = "framework",
        desc = "Ajustes do Android por perfil: tela (jogo 120 Hz fixo, fora adaptativo 120/60), jobs e standby",
        accent = FusaoPalette.PlasmaViolet,
    ),
    FlagSpec(
        cmd = "perf",
        title = "perf",
        desc = "Prioridade de CPU e de disco do jogo ao entrar",
        accent = FusaoPalette.IgnisAmber,
    ),
    FlagSpec(
        cmd = "dnd",
        title = "dnd",
        desc = "Não Perturbe automático ao entrar no jogo",
        accent = FusaoPalette.CryoTurquoise,
    ),
    FlagSpec(
        cmd = "preload",
        title = "preload",
        desc = "Pré-carrega os arquivos do jogo na RAM ao entrar",
        accent = FusaoPalette.ElectricOrchid,
    ),
    FlagSpec(
        cmd = "ramclean",
        title = "ramclean",
        desc = "Libera RAM ao entrar no jogo, antes do pré-carregamento",
        accent = FusaoPalette.BioEmerald,
    ),
    FlagSpec(
        cmd = "gos",
        title = "gos",
        desc = "Encerra o Game Optimizing Service (GOS) da Samsung",
        accent = FusaoPalette.CrimsonCore,
    ),
    FlagSpec(
        cmd = "fas",
        title = "fas",
        desc = "Controla a CPU frame a frame no jogo (fas-rs)",
        accent = FusaoPalette.MintPulse,
    ),
)

/** Rótulos dos modos do fas-rs (margem de fps abaixo do alvo). */
private val FAS_MODE_LABELS = mapOf(
    "powersave" to "ECONOMIA",
    "balance" to "EQUILÍBRIO",
    "performance" to "DESEMPENHO",
    "fast" to "RÁPIDO",
)

class MainActivity : ComponentActivity() {

    private val vm: FusaoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            FusaoTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                val ctx = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current

                // Revalida root e módulo a cada abertura / retorno ao primeiro plano (ON_START)
                DisposableEffect(lifecycleOwner, ctx) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_START) {
                            vm.bootstrap(ctx)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                FusaoScreen(state)
            }
        }
    }

    @Composable
    private fun FusaoScreen(state: FusaoUiState) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val profileAccent = FusaoPalette.profileAccent(state.status.profile)
        val profileSecondary = FusaoPalette.profileSecondary(state.status.profile)

        FusaoAmbientCanvas(
            accentColor = profileAccent,
            secondaryColor = profileSecondary,
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    FusaoHeaderBar(
                        state = state,
                        onRefresh = { vm.refresh() },
                    )
                },
                bottomBar = {
                    FusaoBottomDock(
                        selectedTab = tab,
                        onSelectTab = { tab = it },
                        activeFlags = state.status.activeFlagsCount,
                        flagsTotal = state.status.flagsTotal,
                        gamesCount = state.games.size,
                        inGame = state.status.inGame,
                        accentColor = profileAccent,
                    )
                },
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (!state.ready) {
                        InitialLoadingView()
                        return@Column
                    }

                    // Banners de estado de sistema (Root, Módulo, Gravação 20s em andamento e Avisos)
                    SystemBannersArea(state)

                    // Conteúdo principal das 5 áreas com transição suave
                    Crossfade(
                        targetState = tab,
                        animationSpec = tween(durationMillis = 180),
                        modifier = Modifier.weight(1f),
                        label = "fusaoTabCrossfade",
                    ) { currentTab ->
                        when (currentTab) {
                            0 -> StatusTab(state)
                            1 -> FlagsTab(state)
                            2 -> PreloadTab(state)
                            3 -> GamesTab(state)
                            4 -> LogTab(state)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun FusaoHeaderBar(
        state: FusaoUiState,
        onRefresh: () -> Unit,
    ) {
        val rawVersion = state.moduleVersion
        val cleanVersion = remember(rawVersion) {
            if (rawVersion.isEmpty() || rawVersion == "—") {
                "M54 SINGLE"
            } else {
                rawVersion
                    .substringBefore("|")
                    .replace("version=", "")
                    .substringBefore(" ")
                    .trim()
                    .ifEmpty { "M54" }
            }
        }
        val hasStagedUpdate = remember(rawVersion) {
            rawVersion.contains("staged:") || rawVersion.contains("|")
        }
        val isRefreshing = state.isBusy(Busy.REFRESH)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            FusaoPalette.VoidBlack.copy(alpha = 0.96f),
                            FusaoPalette.ObsidianSurface.copy(alpha = 0.88f),
                            Color.Transparent,
                        )
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(FusaoPalette.CryoSlate)
                            .border(
                                width = 1.dp,
                                brush = Brush.linearGradient(
                                    listOf(FusaoPalette.CryoCyan, FusaoPalette.PlasmaViolet)
                                ),
                                shape = RoundedCornerShape(10.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_fusao_crest),
                            contentDescription = "Símbolo FUSAO",
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "FUSAO",
                                style = MaterialTheme.typography.titleLarge,
                                color = FusaoPalette.TextPrimary,
                                maxLines = 1,
                                softWrap = false,
                            )
                            TelemetryPill(
                                text = cleanVersion,
                                color = FusaoPalette.CryoCyan,
                                filled = true,
                            )
                            if (hasStagedUpdate) {
                                TelemetryPill(
                                    text = "UPD",
                                    color = FusaoPalette.IgnisAmber,
                                    filled = true,
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            StatusPulseDot(
                                color = when {
                                    !state.rooted -> FusaoPalette.CrimsonCore
                                    !state.moduleInstalled -> FusaoPalette.IgnisAmber
                                    state.status.inGame -> FusaoPalette.IgnisAmber
                                    else -> FusaoPalette.BioEmerald
                                },
                                active = state.rooted && state.moduleInstalled,
                                size = 6.dp,
                            )
                            Text(
                                text = when {
                                    !state.ready -> "VERIFICANDO ROOT..."
                                    !state.rooted -> "SEM ROOT · MODO LEITURA"
                                    !state.moduleInstalled -> "MÓDULO NÃO INSTALADO"
                                    state.status.inGame -> "JOGO ATIVO · ${state.labelFor(state.status.gameActive).uppercase()}"
                                    else -> "SEM JOGO ATIVO"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = FusaoPalette.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                TacticalButton(
                    text = "Atualizar",
                    onClick = onRefresh,
                    enabled = !state.anyBusy(),
                    isLoading = isRefreshing,
                    accentColor = FusaoPalette.CryoCyan,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(
                thickness = 1.dp,
                color = FusaoPalette.GridHairline.copy(alpha = 0.7f),
            )
        }
    }

    @Composable
    private fun FusaoBottomDock(
        selectedTab: Int,
        onSelectTab: (Int) -> Unit,
        activeFlags: Int,
        flagsTotal: Int,
        gamesCount: Int,
        inGame: Boolean,
        accentColor: Color,
    ) {
        val tabs = remember(activeFlags, flagsTotal, gamesCount, inGame) {
            listOf(
                Triple("Status", FusaoNavIcon.STATUS, if (inGame) "LIVE" else null),
                Triple("Flags", FusaoNavIcon.FLAGS, "$activeFlags/$flagsTotal"),
                Triple("Preload", FusaoNavIcon.PRELOAD, null),
                Triple("Jogos", FusaoNavIcon.GAMES, if (gamesCount > 0) "$gamesCount" else null),
                Triple("Log", FusaoNavIcon.LOG, null),
            )
        }

        Surface(
            color = FusaoPalette.VoidBlack.copy(alpha = 0.96f),
            border = BorderStroke(1.dp, FusaoPalette.GridHairline.copy(alpha = 0.85f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { idx, (label, icon, badge) ->
                    val selected = selectedTab == idx
                    val itemColor = if (selected) accentColor else FusaoPalette.TextSecondary
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) accentColor.copy(alpha = 0.13f)
                                else Color.Transparent
                            )
                            .border(
                                width = if (selected) 1.dp else 0.dp,
                                color = if (selected) accentColor.copy(alpha = 0.45f) else Color.Transparent,
                                shape = RoundedCornerShape(10.dp),
                            )
                            .clickable { onSelectTab(idx) }
                            .padding(vertical = 7.dp, horizontal = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                modifier = Modifier.height(20.dp),
                            ) {
                                FusaoVectorGlyph(
                                    icon = icon,
                                    color = itemColor,
                                    modifier = Modifier.size(18.dp),
                                )
                                if (badge != null) {
                                    Text(
                                        text = badge,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 8.sp,
                                            lineHeight = 10.sp,
                                            letterSpacing = 0.2.sp,
                                        ),
                                        color = if (selected) FusaoPalette.VoidBlack else accentColor,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                if (selected) accentColor
                                                else FusaoPalette.ElevatedHud
                                            )
                                            .padding(horizontal = 3.5.dp, vertical = 1.dp),
                                    )
                                }
                            }
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                ),
                                color = itemColor,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun InitialLoadingView() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            TacticalPanel(
                accentColor = FusaoPalette.CryoCyan,
                highlighted = true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_fusao_emblem),
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                    )
                    CircularProgressIndicator(
                        color = FusaoPalette.CryoCyan,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = "Aguarde...",
                        style = MaterialTheme.typography.titleMedium,
                        color = FusaoPalette.TextPrimary,
                    )
                    Text(
                        text = "Validando ponte root (libsu) e estado de /data/adb/fusao/",
                        style = MaterialTheme.typography.bodySmall,
                        color = FusaoPalette.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    @Composable
    private fun SystemBannersArea(state: FusaoUiState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!state.rooted) {
                Banner(
                    text = "Sem root — modo leitura. Refaça o temp-root e reabra o app.",
                    accentColor = FusaoPalette.CrimsonCore,
                    tag = "ROOT OFF",
                )
            } else if (!state.moduleInstalled) {
                Banner(
                    text = "Módulo encore ausente.",
                    accentColor = FusaoPalette.IgnisAmber,
                    tag = "MOD AUSENTE",
                )
            }

            AnimatedVisibility(
                visible = state.isBusy(Busy.RECORD),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Banner(
                    text = "Gravação de Preload ativa (${state.recordRemainingSec}s restantes) — mantenha o jogo em execução.",
                    accentColor = FusaoPalette.IgnisAmber,
                    tag = "REC 20S",
                )
            }

            AnimatedVisibility(
                visible = state.notice.isNotEmpty() && !state.isBusy(Busy.RECORD),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                val isError = state.notice.contains("ausente", ignoreCase = true) ||
                    state.notice.contains("Sem root", ignoreCase = true) ||
                    state.notice.contains("inválido", ignoreCase = true) ||
                    state.notice.contains("FALHOU", ignoreCase = true)
                Banner(
                    text = state.notice,
                    accentColor = if (isError) FusaoPalette.CrimsonCore else FusaoPalette.CryoCyan,
                    tag = if (isError) "ALERTA" else "CLI",
                    onClose = { vm.clearNotice() },
                )
            }
        }
    }

    @Composable
    private fun Banner(
        text: String,
        accentColor: Color = FusaoPalette.CryoCyan,
        tag: String = "INFO",
        onClose: (() -> Unit)? = null,
    ) {
        TacticalPanel(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            accentColor = accentColor,
            containerColor = FusaoPalette.CryoSlate,
            highlighted = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TelemetryPill(
                        text = tag,
                        color = accentColor,
                        filled = true,
                    )
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = FusaoPalette.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (onClose != null) {
                    Text(
                        text = "OK",
                        style = MaterialTheme.typography.labelMedium,
                        color = accentColor,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(accentColor.copy(alpha = 0.14f))
                            .clickable { onClose() }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }

    // =========================================================================
    // 1. ÁREA DE STATUS — APRESENTAÇÃO PRINCIPAL DO PRODUTO
    // =========================================================================
    @Composable
    private fun StatusTab(state: FusaoUiState) {
        val st = state.status
        val busy = state.anyBusy()
        val accent = FusaoPalette.profileAccent(st.profile)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // HERO CARD COM ARTE ORIGINAL + NÚCLEO DO REATOR + SELETOR DE PERFIL
            item(key = "status_hero") {
                TacticalPanel(
                    modifier = Modifier.fillMaxWidth(),
                    accentColor = accent,
                    highlighted = true,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Image(
                            painter = painterResource(id = R.drawable.bg_status_reactor),
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.Crop,
                            alpha = 0.36f,
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            FusaoPalette.VoidBlack.copy(alpha = 0.55f),
                                            FusaoPalette.ObsidianSurface.copy(alpha = 0.88f),
                                            FusaoPalette.AbyssNavy.copy(alpha = 0.96f),
                                        )
                                    )
                                )
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    TelemetryPill(
                                        text = if (st.inGame) "SESSÃO DE JOGO ATIVA" else "SEM JOGO ATIVO",
                                        color = if (st.inGame) FusaoPalette.IgnisAmber else accent,
                                        filled = true,
                                        pulsingDot = true,
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = when (st.profile) {
                                            "game" -> "PERFIL GAME"
                                            "battery" -> "PERFIL BATTERY"
                                            "balanced" -> "PERFIL BALANCED"
                                            else -> "PERFIL ${st.profile.ifEmpty { "—" }.uppercase()}"
                                        },
                                        style = MaterialTheme.typography.headlineMedium.copy(
                                            fontSize = 20.sp,
                                            lineHeight = 25.sp,
                                            letterSpacing = 0.7.sp,
                                        ),
                                        color = FusaoPalette.TextPrimary,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = when (st.profile) {
                                            // alvos reais do tune.sh (degraus do Exynos 1380); battery em jogo vira game
                                            "game" -> if (st.fas && st.fasSupported) {
                                                "CPU com o FAS em jogo · GPU sem piso · MIF ≥845k"
                                            } else {
                                                "P0 ≥1056k · P4 ≥960k · GPU sem piso · MIF ≥845k"
                                            }
                                            "battery" -> "P0 ≤1728k · P4 ≤1440k · GPU sem teto · em jogo: GAME"
                                            "balanced" -> "P0/P4 ≥533k · GPU máx 845 MHz · em jogo: BALANCED"
                                            else -> "Aguardando leitura do perfil em /data/adb/fusao/profile"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = FusaoPalette.TextSecondary,
                                    )
                                    if (st.sysfsSummary.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "LIDO NO APARELHO: ${st.sysfsSummary}",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                            ),
                                            color = when (st.profileState) {
                                                "failed" -> FusaoPalette.CrimsonCore
                                                "partial" -> FusaoPalette.IgnisAmber
                                                "pending" -> FusaoPalette.CryoCyan
                                                else -> FusaoPalette.BioEmerald
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                ReactorCoreEmblem(
                                    profile = st.profile,
                                    inGame = st.inGame,
                                    daemonsAlive = st.runningDaemonsCount,
                                )
                            }

                            // Seletor de Perfil (game, balanced, battery) com confirmação real de readback
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "SELECIONAR PERFIL DE OPERAÇÃO",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = FusaoPalette.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (st.profileState.isNotEmpty()) {
                                        TelemetryPill(
                                            text = when (st.profileState) {
                                                "applied" -> "CONFIRMADO ${st.profileVerify}".trim()
                                                "partial" -> "PARCIAL ${st.profileVerify}".trim()
                                                // piso/teto de CPU segurado pelo QoS da Samsung (boost/hint/térmico): pedido gravado
                                                "pending" -> "AGUARDANDO ${st.profileVerify}".trim()
                                                "failed" -> "FALHA DE READBACK"
                                                else -> st.profileState.uppercase()
                                            },
                                            color = when (st.profileState) {
                                                "applied" -> FusaoPalette.BioEmerald
                                                "partial" -> FusaoPalette.IgnisAmber
                                                "failed" -> FusaoPalette.CrimsonCore
                                                else -> FusaoPalette.CryoCyan
                                            },
                                            filled = st.profileState == "applied",
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    val profiles = listOf(
                                        Triple("game", "GAME", FusaoPalette.IgnisAmber),
                                        Triple("balanced", "BALANCED", FusaoPalette.CryoCyan),
                                        Triple("battery", "BATTERY", FusaoPalette.BioEmerald),
                                    )
                                    profiles.forEach { (pKey, pLabel, pColor) ->
                                        val selected = st.profile == pKey
                                        val confirmed = selected && st.profileState != "failed"
                                        val isTargetBusy = state.isBusy(Busy.FLAG) && state.activeTarget == "profile:$pKey"
                                        val subLabel = when {
                                            !selected -> pLabel
                                            // battery escolhido com jogo da gamelist em foco: o módulo aplica game
                                            st.profileEffective.isNotEmpty() && st.profileEffective != pKey ->
                                                "EM JOGO: ${st.profileEffective.uppercase()}"
                                            st.profileState == "failed" -> "FALHOU"
                                            st.profileState == "partial" -> "PARCIAL"
                                            st.profileState == "pending" -> "AGUARDANDO"
                                            else -> "APLICADO"
                                        }
                                        TacticalButton(
                                            text = pKey,
                                            subtitle = subLabel,
                                            onClick = { vm.setProfile(pKey) },
                                            enabled = state.rooted && !busy && (!selected || st.profileState != "applied"),
                                            filled = confirmed,
                                            isLoading = isTargetBusy,
                                            accentColor = if (selected && st.profileState == "failed") FusaoPalette.CrimsonCore else pColor,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }

                            // Card integrado do Jogo em Execução (game-active.txt)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(FusaoPalette.VoidBlack.copy(alpha = 0.72f))
                                    .border(
                                        width = 1.dp,
                                        color = if (st.inGame) FusaoPalette.IgnisAmber.copy(alpha = 0.55f)
                                        else FusaoPalette.BorderSubtle.copy(alpha = 0.45f),
                                        shape = RoundedCornerShape(10.dp),
                                    )
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        if (st.inGame) {
                                            AppIconBadge(
                                                pkg = st.gameActive,
                                                label = state.labelFor(st.gameActive),
                                                size = 42.dp,
                                                accentColor = FusaoPalette.IgnisAmber,
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(42.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(FusaoPalette.CryoSlate)
                                                    .border(
                                                        1.dp,
                                                        FusaoPalette.BorderSubtle,
                                                        RoundedCornerShape(10.dp),
                                                    ),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                FusaoVectorGlyph(
                                                    icon = FusaoNavIcon.GAMES,
                                                    color = FusaoPalette.TextMuted,
                                                )
                                            }
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "JOGO ATIVO",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (st.inGame) FusaoPalette.IgnisAmber else FusaoPalette.TextMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = if (st.inGame) state.labelFor(st.gameActive) else "— (idle)",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = FusaoPalette.TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (st.inGame) {
                                                Text(
                                                    text = st.gameActive,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = FusaoPalette.TextSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    TelemetryPill(
                                        text = if (st.inGame) "EM FOCO" else "IDLE",
                                        color = if (st.inGame) FusaoPalette.IgnisAmber else FusaoPalette.TextMuted,
                                        filled = st.inGame,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // DISPLAY & ESTADO TÉRMICO (TELEMETRIA REAL DO SISTEMA + ESCALA WM SIZE)
            item(key = "status_display_thermal") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TacticalPanel(
                            modifier = Modifier.weight(1f),
                            accentColor = FusaoPalette.CryoCyan,
                            contentPadding = PaddingValues(14.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "RESOLUÇÃO DA TELA",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = FusaoPalette.CryoCyan,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = st.display.ifEmpty { "—" },
                                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                                    color = FusaoPalette.TextPrimary,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "Muda na escala abaixo",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FusaoPalette.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        TacticalPanel(
                            modifier = Modifier.weight(1f),
                            accentColor = FusaoPalette.IgnisAmber,
                            contentPadding = PaddingValues(14.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "ESTADO TÉRMICO",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = FusaoPalette.IgnisAmber,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = st.thermal.ifEmpty { "—" },
                                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                                    color = FusaoPalette.TextPrimary,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "Proteção: CPU 78 °C · bateria 45 °C",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FusaoPalette.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    // Controles rápidos para downscale reversível via `fusao display`
                    TacticalPanel(
                        modifier = Modifier.fillMaxWidth(),
                        accentColor = FusaoPalette.CryoTurquoise,
                        contentPadding = PaddingValues(12.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "ESCALA DA TELA",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = FusaoPalette.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "atual: ${st.display.ifEmpty { "—" }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = FusaoPalette.CryoCyan,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                listOf(
                                    "720p" to "720p",
                                    "810p" to "810p",
                                    "900p" to "900p",
                                    "reset" to "Stock",
                                ).forEach { (mode, label) ->
                                    val isModeBusy = state.isBusy(Busy.FLAG) && state.activeTarget == "display:$mode"
                                    TacticalButton(
                                        text = label,
                                        onClick = { vm.setDisplay(mode) },
                                        enabled = state.rooted && !busy,
                                        filled = mode == "reset" && st.display.startsWith("1080"),
                                        isLoading = isModeBusy,
                                        accentColor = if (mode == "reset") FusaoPalette.BioEmerald else FusaoPalette.CryoCyan,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // DAEMONS DO MÓDULO (fusaod, watch, observer)
            item(key = "status_daemons") {
                TacticalPanel(
                    modifier = Modifier.fillMaxWidth(),
                    accentColor = FusaoPalette.PlasmaViolet,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HudSectionHeader(
                            title = "Daemons",
                            subtitle = "Processos do módulo em execução",
                            accentColor = FusaoPalette.PlasmaViolet,
                            trailing = {
                                TelemetryPill(
                                    text = "${st.runningDaemonsCount}/3 VIVOS",
                                    color = if (st.runningDaemonsCount == 3) FusaoPalette.BioEmerald else FusaoPalette.CryoCyan,
                                    filled = true,
                                )
                            },
                        )

                        DaemonStatusRow(
                            name = "fusaod",
                            role = "Pisos de CPU/GPU/memória em jogo",
                            running = st.fusaodRunning,
                            flagEnabled = st.fusaod,
                        )
                        DaemonStatusRow(
                            name = "watch",
                            role = "Aplica os ajustes ao entrar e sair de jogos",
                            running = st.watchRunning,
                            flagEnabled = true,
                        )
                        DaemonStatusRow(
                            name = "observer",
                            role = "Avisa na hora que um jogo abre (sem ele: a cada 5 s)",
                            running = st.observerRunning,
                            flagEnabled = true,
                        )
                    }
                }
            }

            // GANCHOS (FOI OU NÃO)
            item(key = "status_hooks") {
                TacticalPanel(
                    modifier = Modifier.fillMaxWidth(),
                    accentColor = FusaoPalette.CryoCyan,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HudSectionHeader(
                            title = "Ganchos",
                            subtitle = "Últimos eventos ao entrar e sair de jogos",
                            accentColor = FusaoPalette.CryoCyan,
                            trailing = {
                                TelemetryPill(
                                    text = "${st.hooks.size} EVENTOS",
                                    color = FusaoPalette.CryoCyan,
                                )
                            },
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(FusaoPalette.VoidBlack.copy(alpha = 0.82f))
                                .border(1.dp, FusaoPalette.GridHairline, RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            if (st.hooks.isEmpty()) {
                                Text(
                                    text = "—",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = FusaoPalette.TextMuted,
                                )
                            } else {
                                SelectionContainer {
                                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        st.hooks.forEachIndexed { idx, h ->
                                            val highlighted = remember(h) { highlightTelemetryLine(h) }
                                            Row(
                                                verticalAlignment = Alignment.Top,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Text(
                                                    text = (idx + 1).toString().padStart(2, '0'),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = FusaoPalette.TextMuted,
                                                )
                                                Text(
                                                    text = highlighted,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 11.5.sp,
                                                        lineHeight = 16.sp,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DaemonStatusRow(
        name: String,
        role: String,
        running: Boolean,
        flagEnabled: Boolean,
    ) {
        val statusText = daemonText(running)
        val dotColor = when {
            running -> FusaoPalette.BioEmerald
            !flagEnabled -> FusaoPalette.TextMuted
            else -> FusaoPalette.IgnisAmber
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(FusaoPalette.VoidBlack.copy(alpha = 0.55f))
                .border(
                    width = 1.dp,
                    color = if (running) FusaoPalette.BioEmerald.copy(alpha = 0.35f)
                    else FusaoPalette.BorderSubtle.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                StatusPulseDot(color = dotColor, active = running)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$name: $statusText",
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                        color = FusaoPalette.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = role,
                        style = MaterialTheme.typography.bodySmall,
                        color = FusaoPalette.TextSecondary,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            TelemetryPill(
                text = statusText.uppercase(),
                color = dotColor,
                filled = running,
            )
        }
    }

    private fun daemonText(running: Boolean): String = if (running) "vivo" else "parado"

    // =========================================================================
    // 2. ÁREA DE FLAGS — OS 7 CONTROLES DE SUBSISTEMA
    // =========================================================================
    @Composable
    private fun FlagsTab(state: FusaoUiState) {
        val st = state.status
        val busy = state.anyBusy()
        // módulo < 2.11.0 não tem FAS: a chave nem aparece (o CLI antigo recusaria `fas on`)
        val specs = remember(st.fasSupported) { FLAG_SPECS.filter { it.cmd != "fas" || st.fasSupported } }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "flags_header") {
                TacticalPanel(
                    modifier = Modifier.fillMaxWidth(),
                    accentColor = FusaoPalette.CryoCyan,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    HudSectionHeader(
                        title = "Controles do Módulo",
                        subtitle = "Liga e desliga cada função do módulo",
                        accentColor = FusaoPalette.CryoCyan,
                        trailing = {
                            TelemetryPill(
                                text = "${st.activeFlagsCount}/${st.flagsTotal} ATIVOS",
                                color = FusaoPalette.CryoCyan,
                                filled = true,
                            )
                        },
                    )
                }
            }

            items(items = specs, key = { it.cmd }) { spec ->
                val checked = st.flag(spec.cmd)
                val isThisFlagBusy = state.isBusy(Busy.FLAG) && state.activeTarget == spec.cmd
                val available = state.rooted && state.moduleInstalled

                val stateBadgeText = when {
                    !available -> "INDISPONÍVEL"
                    isThisFlagBusy -> "PROCESSANDO"
                    spec.cmd == "dnd" && checked && st.inGame -> "ATIVO EM JOGO"
                    spec.cmd == "dnd" && checked -> "ARMADO (PRÓX. JOGO)"
                    spec.cmd == "dnd" && !checked -> "DESARMADO"
                    spec.cmd == "gos" && checked -> "KILLER ATIVO"
                    spec.cmd == "gos" && !checked -> "PAUSADO"
                    spec.cmd == "fas" && checked && !st.fasBinOk -> "SEM BINÁRIO"
                    spec.cmd == "fas" && checked && st.fasState == "working" -> "CONTROLANDO"
                    // fas-rs vivo sem controlar: aquecendo (~10 s após o enter) ou jogo saiu do foco
                    spec.cmd == "fas" && checked && st.fasState == "waiting" -> "AGUARDANDO"
                    spec.cmd == "fas" && checked -> "ARMADO (PRÓX. JOGO)"
                    checked -> "ATIVO"
                    else -> "INATIVO"
                }

                val badgeColor = when {
                    !available -> FusaoPalette.TextMuted
                    isThisFlagBusy -> FusaoPalette.CryoCyan
                    spec.cmd == "fas" && checked && !st.fasBinOk -> FusaoPalette.CrimsonCore
                    checked -> spec.accent
                    spec.cmd == "gos" && !checked -> FusaoPalette.IgnisAmber
                    else -> FusaoPalette.TextMuted
                }

                TacticalPanel(
                    modifier = Modifier.fillMaxWidth(),
                    accentColor = if (checked) spec.accent else FusaoPalette.BorderSubtle,
                    highlighted = checked,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = spec.title,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = FusaoPalette.TextPrimary,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                                TelemetryPill(
                                    text = stateBadgeText,
                                    color = badgeColor,
                                    filled = checked || isThisFlagBusy,
                                )
                            }
                            Text(
                                text = spec.desc,
                                style = MaterialTheme.typography.bodyMedium,
                                color = FusaoPalette.TextSecondary,
                            )
                        }

                        CyberSwitch(
                            checked = checked,
                            onCheckedChange = { vm.toggleFlag(spec.cmd, it) },
                            enabled = state.rooted && !busy,
                            isProcessing = isThisFlagBusy,
                            accentColor = spec.accent,
                        )
                    }
                }
            }

            if (st.fasSupported) {
                item(key = "fas_panel") {
                    FasPanel(state)
                }
            }
        }
    }

    /** Painel do FAS (fas-rs): estado em jogo, trava de GPU saturada e modo. Alvo por jogo fica na aba Jogos. */
    @Composable
    private fun FasPanel(state: FusaoUiState) {
        val st = state.status
        val busy = state.anyBusy()
        val accent = FusaoPalette.MintPulse
        val working = st.fas && st.fasState == "working"

        TacticalPanel(
            modifier = Modifier.fillMaxWidth(),
            accentColor = if (st.fas) accent else FusaoPalette.BorderSubtle,
            highlighted = working,
            contentPadding = PaddingValues(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HudSectionHeader(
                    title = "FAS · fas-rs",
                    subtitle = "A CPU mais baixa que ainda segura o fps do jogo, frame a frame",
                    accentColor = accent,
                    trailing = {
                        TelemetryPill(
                            text = when {
                                !st.fas -> "DESLIGADO"
                                !st.fasBinOk -> "SEM BINÁRIO"
                                working -> "CONTROLANDO"
                                st.fasState == "waiting" -> "AGUARDANDO"
                                else -> "ARMADO"
                            },
                            color = when {
                                !st.fas -> FusaoPalette.TextMuted
                                !st.fasBinOk -> FusaoPalette.CrimsonCore
                                else -> accent
                            },
                            filled = working,
                            pulsingDot = working,
                        )
                    },
                )

                if (working) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = state.labelFor(st.fasGame),
                            style = MaterialTheme.typography.bodyMedium,
                            color = FusaoPalette.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (st.fasGpu == "saturated") {
                            // a trava do fork: GPU >= 95% ocupada = frame lento é da GPU, a CPU não sobe
                            TelemetryPill(
                                text = "GPU SATURADA · SEM BOOST",
                                color = FusaoPalette.IgnisAmber,
                                filled = true,
                            )
                        }
                    }
                }

                Text(
                    text = "MODO · MARGEM ABAIXO DO ALVO",
                    style = MaterialTheme.typography.labelSmall,
                    color = FusaoPalette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FAS_MODES.chunked(2).forEach { rowModes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowModes.forEach { mode ->
                            val selected = st.fasMode == mode
                            TacticalButton(
                                text = mode,
                                subtitle = if (selected) "ATIVO" else FAS_MODE_LABELS[mode],
                                onClick = { vm.setFasMode(mode) },
                                // o selecionado fica habilitado (filled = destaque); tocar de novo só regrava o modo
                                enabled = state.rooted && !busy,
                                filled = selected,
                                isLoading = state.isBusy(Busy.FLAG) && state.activeTarget == "fas_mode:$mode",
                                accentColor = accent,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                Text(
                    text = "Medido no Bleach (gráficos mínimos, limite 45): −10% de bateria e CPU 3,5 °C mais fria " +
                        "com o mesmo fps, em troca de ~1 frame lento a cada 3 s. Limite de fps por jogo: aba Jogos, " +
                        "toque no FAS de cada jogo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FusaoPalette.TextSecondary,
                )
            }
        }
    }

    // =========================================================================
    // 3. ÁREA DE PRELOAD & RAM CLEAN
    // =========================================================================
    @Composable
    private fun PreloadTab(state: FusaoUiState) {
        val busy = state.anyBusy()
        val preloadTelemetry = remember(state.preloadInfo) {
            PreloadTelemetry.fromLines(state.preloadInfo)
        }
        val ramcleanTelemetry = remember(state.ramcleanInfo) {
            RamcleanTelemetry.fromLines(state.ramcleanInfo)
        }

        LaunchedEffect(state.rooted) {
            if (state.rooted) {
                vm.loadPreloadAndRamcleanIfEmpty()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // PAINEL PRINCIPAL: PRELOAD (WILLNEED) + RECORD 20S
            TacticalPanel(
                modifier = Modifier.fillMaxWidth(),
                accentColor = FusaoPalette.ElectricOrchid,
                highlighted = true,
                contentPadding = PaddingValues(0.dp),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Image(
                        painter = painterResource(id = R.drawable.bg_preload_matrix),
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                        alpha = 0.30f,
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        FusaoPalette.VoidBlack.copy(alpha = 0.62f),
                                        FusaoPalette.ObsidianSurface.copy(alpha = 0.92f),
                                        FusaoPalette.AbyssNavy.copy(alpha = 0.98f),
                                    )
                                )
                            )
                    )

                    val eligibleGames = remember(preloadTelemetry.eligiblePkgs, state.games, state.appLabelsMap) {
                        val base = (preloadTelemetry.eligiblePkgs + state.games.filter { it in state.appLabelsMap }).distinct()
                        base
                    }
                    val preloadBadgeColor = when (preloadTelemetry.preloadState) {
                        "executed" -> FusaoPalette.BioEmerald
                        "prepared" -> FusaoPalette.CryoCyan
                        "armed" -> FusaoPalette.ElectricOrchid
                        "failed" -> FusaoPalette.CrimsonCore
                        else -> if (state.status.preload) FusaoPalette.ElectricOrchid else FusaoPalette.TextMuted
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        HudSectionHeader(
                            title = "Preload",
                            subtitle = "Pré-carrega na RAM os arquivos dos jogos da lista",
                            accentColor = FusaoPalette.ElectricOrchid,
                            trailing = {
                                TelemetryPill(
                                    text = preloadTelemetry.statusBadgeLabel,
                                    color = preloadBadgeColor,
                                    filled = preloadTelemetry.preloadState in setOf("executed", "prepared", "armed") || state.status.preload,
                                )
                            },
                        )

                        // Chave de habilitação global da política automática de Preload direto na aba
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(FusaoPalette.VoidBlack.copy(alpha = 0.68f))
                                .border(1.dp, FusaoPalette.BorderSubtle, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(
                                    text = "POLÍTICA GLOBAL DE PRELOAD",
                                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = FusaoPalette.TextPrimary,
                                )
                                Text(
                                    text = if (preloadTelemetry.lastResult.isNotEmpty()) {
                                        "Último resultado: ${preloadTelemetry.lastResult}"
                                    } else {
                                        "Aplica a todos os pacotes elegíveis instalados de gamelist.txt"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FusaoPalette.TextSecondary,
                                )
                            }
                            CyberSwitch(
                                checked = state.status.preload,
                                onCheckedChange = { vm.toggleFlag("preload", it) },
                                enabled = state.rooted && !busy,
                                isProcessing = state.isBusy(Busy.FLAG) && state.activeTarget == "preload",
                                accentColor = FusaoPalette.ElectricOrchid,
                            )
                        }

                        // Resumo estruturado extraído das linhas reais do CLI
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MetricMiniCard(
                                label = "ESTADO REAL",
                                value = preloadTelemetry.preloadState.uppercase(),
                                accent = preloadBadgeColor,
                                modifier = Modifier.weight(1f),
                            )
                            MetricMiniCard(
                                label = "ELEGÍVEIS / PACKS",
                                value = "${maxOf(preloadTelemetry.eligibleCount, eligibleGames.size)} / ${preloadTelemetry.packCount}",
                                accent = FusaoPalette.CryoCyan,
                                modifier = Modifier.weight(1f),
                            )
                            MetricMiniCard(
                                label = "ALVO SELECIONADO",
                                value = when {
                                    state.selectedPreloadPkg.isNotEmpty() -> state.labelFor(state.selectedPreloadPkg)
                                    state.status.inGame -> state.labelFor(state.status.gameActive)
                                    else -> "TODOS (${maxOf(preloadTelemetry.eligibleCount, eligibleGames.size)})"
                                },
                                accent = if (state.status.inGame) FusaoPalette.IgnisAmber else FusaoPalette.ElectricOrchid,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        // Seletor de pacote alvo a partir da gamelist (funciona sem jogo em primeiro plano)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "ALVO DA POLÍTICA (GAMELIST.TXT — INDEPENDENTE DE FOCO)",
                                style = MaterialTheme.typography.labelSmall,
                                color = FusaoPalette.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val allSelected = state.selectedPreloadPkg.isEmpty()
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (allSelected) FusaoPalette.ElectricOrchid.copy(alpha = 0.22f)
                                            else FusaoPalette.VoidBlack.copy(alpha = 0.55f)
                                        )
                                        .border(
                                            1.dp,
                                            if (allSelected) FusaoPalette.ElectricOrchid else FusaoPalette.BorderSubtle,
                                            RoundedCornerShape(6.dp),
                                        )
                                        .clickable { vm.selectPreloadTarget("") }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "Todos elegíveis",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (allSelected) FusaoPalette.ElectricOrchid else FusaoPalette.TextSecondary,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                                eligibleGames.forEach { pkg ->
                                    val sel = state.selectedPreloadPkg == pkg
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                if (sel) FusaoPalette.CryoCyan.copy(alpha = 0.22f)
                                                else FusaoPalette.VoidBlack.copy(alpha = 0.55f)
                                            )
                                            .border(
                                                1.dp,
                                                if (sel) FusaoPalette.CryoCyan else FusaoPalette.BorderSubtle,
                                                RoundedCornerShape(6.dp),
                                            )
                                            .clickable { vm.selectPreloadTarget(pkg) }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = state.labelFor(pkg),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (sel) FusaoPalette.CryoCyan else FusaoPalette.TextSecondary,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                }
                            }
                        }

                        // Medidor ao vivo durante gravação/mapeamento
                        AnimatedVisibility(
                            visible = state.isBusy(Busy.RECORD),
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(FusaoPalette.VoidBlack.copy(alpha = 0.85f))
                                    .border(1.2.dp, FusaoPalette.IgnisAmber, RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                PreloadCountdownGauge(
                                    remainingSec = state.recordRemainingSec,
                                    totalSec = 20,
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = "MAPEANDO PACK DE PRELOAD",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = FusaoPalette.IgnisAmber,
                                    )
                                    Text(
                                        text = "Gerando manifesto .pack em /data/adb/fusao/preload/ para os pacotes elegíveis.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = FusaoPalette.TextPrimary,
                                    )
                                    LinearProgressIndicator(
                                        progress = { (20 - state.recordRemainingSec).coerceIn(0, 20) / 20f },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp)
                                            .height(5.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = FusaoPalette.IgnisAmber,
                                        trackColor = FusaoPalette.GridHairline,
                                    )
                                }
                            }
                        }

                        // Botões de comando do Preload habilitados independentemente de qual app está em foco
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TacticalButton(
                                text = "Atualizar",
                                onClick = { vm.loadPreload() },
                                enabled = state.rooted && !busy,
                                isLoading = state.isBusy(Busy.PRELOAD) && state.activeTarget == "preload_status",
                                accentColor = FusaoPalette.CryoCyan,
                                modifier = Modifier.weight(1f),
                            )
                            TacticalButton(
                                text = "Preparar Packs",
                                onClick = { vm.preloadPrepare() },
                                enabled = state.rooted && !busy,
                                isLoading = state.isBusy(Busy.PRELOAD) && state.activeTarget == "preload_prepare",
                                accentColor = FusaoPalette.CryoTurquoise,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TacticalButton(
                                text = "Preload agora",
                                onClick = { vm.preloadNow() },
                                enabled = state.rooted && !busy,
                                filled = true,
                                isLoading = state.isBusy(Busy.PRELOAD) && state.activeTarget == "preload_now",
                                accentColor = FusaoPalette.ElectricOrchid,
                                modifier = Modifier.weight(1f),
                            )
                            TacticalButton(
                                text = if (state.isBusy(Busy.RECORD)) {
                                    "Mapeando... (${state.recordRemainingSec}s)"
                                } else {
                                    "Record Pack"
                                },
                                onClick = { vm.preloadRecord() },
                                enabled = state.rooted && !busy,
                                filled = state.isBusy(Busy.RECORD),
                                isLoading = state.isBusy(Busy.RECORD),
                                accentColor = FusaoPalette.IgnisAmber,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        // Console de saída real do CLI `fusao preload status` / `prepare` / `now` / `record`
                        TerminalOutputConsole(
                            title = "Resultado do preload",
                            lines = state.preloadInfo,
                        )
                    }
                }
            }

            // PAINEL SECUNDÁRIO: RAM CLEAN (ANTES DO PRELOAD)
            TacticalPanel(
                modifier = Modifier.fillMaxWidth(),
                accentColor = FusaoPalette.BioEmerald,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HudSectionHeader(
                        title = "Limpeza de RAM",
                        subtitle = "Libera memória antes do pré-carregamento",
                        accentColor = FusaoPalette.BioEmerald,
                        trailing = {
                            TelemetryPill(
                                text = if (state.status.ramclean) "HOOK ON" else "HOOK OFF",
                                color = if (state.status.ramclean) FusaoPalette.BioEmerald else FusaoPalette.TextMuted,
                                filled = state.status.ramclean,
                            )
                        },
                    )

                    if (ramcleanTelemetry.lastTransition.isNotEmpty() || ramcleanTelemetry.lastAvailKb.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MetricMiniCard(
                                label = "MEMFREE (KB)",
                                value = ramcleanTelemetry.lastTransition.ifEmpty { "—" },
                                accent = FusaoPalette.BioEmerald,
                                modifier = Modifier.weight(1.35f),
                            )
                            MetricMiniCard(
                                label = "MEMAVAIL (KB)",
                                value = ramcleanTelemetry.lastAvailKb.ifEmpty { "—" },
                                accent = FusaoPalette.CryoCyan,
                                modifier = Modifier.weight(1f),
                            )
                            MetricMiniCard(
                                label = "STATUS RC",
                                value = ramcleanTelemetry.lastRc.ifEmpty { "—" },
                                accent = FusaoPalette.MintPulse,
                                modifier = Modifier.weight(0.65f),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TacticalButton(
                            text = "Atualizar",
                            onClick = { vm.loadRamclean() },
                            enabled = state.rooted && !busy,
                            isLoading = state.isBusy(Busy.RAMCLEAN) && state.activeTarget == "ramclean_status",
                            accentColor = FusaoPalette.CryoCyan,
                            modifier = Modifier.weight(1f),
                        )
                        TacticalButton(
                            text = "Limpar agora",
                            onClick = { vm.ramcleanNow() },
                            enabled = state.rooted && !busy,
                            filled = true,
                            isLoading = state.isBusy(Busy.RAMCLEAN) && state.activeTarget == "ramclean_now",
                            accentColor = FusaoPalette.BioEmerald,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    TerminalOutputConsole(
                        title = "Resultado da limpeza de RAM",
                        lines = state.ramcleanInfo,
                    )
                }
            }
        }
    }

    @Composable
    private fun MetricMiniCard(
        label: String,
        value: String,
        accent: Color,
        modifier: Modifier = Modifier,
    ) {
        val valueFontSize = if (value.length > 11) 11.sp else 13.sp
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(FusaoPalette.VoidBlack.copy(alpha = 0.72f))
                .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .padding(horizontal = 9.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.8.sp),
                color = FusaoPalette.TextMuted,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = valueFontSize,
                ),
                color = accent,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    @Composable
    private fun TerminalOutputConsole(
        title: String,
        lines: List<String>,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(FusaoPalette.VoidBlack.copy(alpha = 0.85f))
                .border(1.dp, FusaoPalette.GridHairline, RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = FusaoPalette.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (lines.isEmpty()) {
                Text(
                    text = "—",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = FusaoPalette.TextMuted,
                )
            } else {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        lines.forEach { l ->
                            val highlighted = remember(l) { highlightTelemetryLine(l) }
                            Text(
                                text = highlighted,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // 4. ÁREA DE JOGOS — GAMELIST + SELETOR DE APLICATIVOS COM ÍCONES REAIS
    // =========================================================================
    @Composable
    private fun GamesTab(state: FusaoUiState) {
        val ctx = LocalContext.current
        var pickerOpen by rememberSaveable { mutableStateOf(false) }
        var filterQuery by rememberSaveable { mutableStateOf("") }
        val busy = state.anyBusy()

        LaunchedEffect(state.rooted) {
            if (state.rooted && state.games.isEmpty()) {
                vm.loadGames()
            }
        }

        val filteredGames = remember(state.games, state.appLabelsMap, filterQuery) {
            if (filterQuery.isBlank()) {
                state.games
            } else {
                val q = filterQuery.trim()
                state.games.filter { pkg ->
                    val label = state.labelFor(pkg)
                    label.contains(q, ignoreCase = true) || pkg.contains(q, ignoreCase = true)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TacticalPanel(
                modifier = Modifier.fillMaxWidth(),
                accentColor = FusaoPalette.CryoCyan,
                contentPadding = PaddingValues(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HudSectionHeader(
                        title = "Jogos Monitorados",
                        subtitle = "Jogos que ativam o FUSAO ao abrir",
                        accentColor = FusaoPalette.CryoCyan,
                        trailing = {
                            TelemetryPill(
                                text = "${state.games.size} ALVOS",
                                color = FusaoPalette.CryoCyan,
                                filled = true,
                            )
                        },
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TacticalButton(
                            text = "Atualizar",
                            onClick = { vm.loadGames() },
                            enabled = state.rooted && !busy,
                            isLoading = state.isBusy(Busy.GAMELIST) && state.activeTarget == "gamelist_load",
                            accentColor = FusaoPalette.CryoCyan,
                            modifier = Modifier.weight(1f),
                        )
                        TacticalButton(
                            text = "Adicionar",
                            onClick = {
                                vm.loadApps(ctx)
                                pickerOpen = true
                            },
                            enabled = state.rooted && !busy,
                            filled = true,
                            accentColor = FusaoPalette.PlasmaViolet,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    if (state.games.size > 4) {
                        OutlinedTextField(
                            value = filterQuery,
                            onValueChange = { filterQuery = it },
                            placeholder = {
                                Text(
                                    "Filtrar na gamelist por nome ou pacote...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FusaoPalette.TextMuted,
                                )
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = FusaoPalette.TextPrimary),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = FusaoPalette.CryoCyan,
                                unfocusedBorderColor = FusaoPalette.BorderSubtle,
                                focusedContainerColor = FusaoPalette.VoidBlack.copy(alpha = 0.65f),
                                unfocusedContainerColor = FusaoPalette.VoidBlack.copy(alpha = 0.45f),
                                cursorColor = FusaoPalette.CryoCyan,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (filteredGames.isEmpty()) {
                TacticalPanel(
                    modifier = Modifier.fillMaxWidth(),
                    accentColor = FusaoPalette.BorderSubtle,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FusaoVectorGlyph(
                            icon = FusaoNavIcon.GAMES,
                            color = FusaoPalette.TextMuted,
                            modifier = Modifier.size(32.dp),
                        )
                        Text(
                            text = if (state.games.isEmpty()) {
                                "Nenhum jogo listado em gamelist.txt"
                            } else {
                                "Nenhum pacote corresponde ao filtro \"$filterQuery\""
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = FusaoPalette.TextSecondary,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = "Use \"Adicionar\" para escolher um aplicativo instalado ou \"Atualizar\" para reler do módulo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = FusaoPalette.TextMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(items = filteredGames, key = { it }) { pkg ->
                        val label = vm.appLabel(pkg)
                        val isInstalled = pkg in state.appLabelsMap
                        val isCurrentlyRunning = state.status.gameActive == pkg
                        val isDeletingThis = state.isBusy(Busy.GAMELIST) && state.activeTarget == "del:$pkg"

                        TacticalPanel(
                            modifier = Modifier.fillMaxWidth(),
                            accentColor = when {
                                isCurrentlyRunning -> FusaoPalette.IgnisAmber
                                isInstalled -> FusaoPalette.CryoCyan
                                else -> FusaoPalette.BorderSubtle
                            },
                            highlighted = isCurrentlyRunning,
                            contentPadding = PaddingValues(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    AppIconBadge(
                                        pkg = pkg,
                                        label = label,
                                        size = 46.dp,
                                        accentColor = if (isCurrentlyRunning) FusaoPalette.IgnisAmber else FusaoPalette.CryoCyan,
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = FusaoPalette.TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            if (isCurrentlyRunning) {
                                                TelemetryPill(
                                                    text = "ATIVO",
                                                    color = FusaoPalette.IgnisAmber,
                                                    filled = true,
                                                )
                                            }
                                        }
                                        Text(
                                            text = pkg,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = FusaoPalette.TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (state.status.fas && state.status.fasSupported) {
                                            // toque avança o limite de fps do jogo: AUTO -> 30 -> 40 -> ... -> 144 -> AUTO
                                            val fasCap = fasCapOf(state.fasTargets[pkg])
                                            val isFasBusy = state.isBusy(Busy.FLAG) && state.activeTarget == "fas_target:$pkg"
                                            TelemetryPill(
                                                text = if (isFasBusy) "FAS · …" else "FAS · ${fasCap?.let { "$it FPS" } ?: "AUTO"}",
                                                color = FusaoPalette.MintPulse,
                                                filled = fasCap != null,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .clickable(enabled = state.rooted && !busy) { vm.cycleFasCap(pkg) },
                                            )
                                        }
                                        if (!isInstalled && state.apps.isNotEmpty()) {
                                            Text(
                                                text = "Registrado em gamelist.txt (não instalado)",
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                                                color = FusaoPalette.TextMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(FusaoPalette.CrimsonCore.copy(alpha = 0.14f))
                                        .border(
                                            1.dp,
                                            FusaoPalette.CrimsonCore.copy(alpha = 0.45f),
                                            RoundedCornerShape(8.dp),
                                        )
                                        .clickable(enabled = state.rooted && !busy) { vm.gameDel(pkg) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isDeletingThis) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = FusaoPalette.CrimsonCore,
                                        )
                                    } else {
                                        Text(
                                            text = "Remover",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = FusaoPalette.CrimsonSoft,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (pickerOpen) {
            AppPicker(
                state = state,
                onPick = { pkg ->
                    pickerOpen = false
                    vm.gameAdd(pkg)
                },
                onClose = { pickerOpen = false },
            )
        }
    }

    @Composable
    private fun AppPicker(
        state: FusaoUiState,
        onPick: (String) -> Unit,
        onClose: () -> Unit,
    ) {
        var query by rememberSaveable { mutableStateOf("") }
        val shown = remember(state.apps, state.games, query) {
            val inList = state.games.toSet()
            val q = query.trim()
            state.apps.filter { a ->
                a.pkg !in inList &&
                    (q.isEmpty() || a.label.contains(q, ignoreCase = true) || a.pkg.contains(q, ignoreCase = true))
            }.take(200)
        }

        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            TacticalPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                accentColor = FusaoPalette.PlasmaViolet,
                containerColor = FusaoPalette.ObsidianSurface,
                highlighted = true,
                contentPadding = PaddingValues(16.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HudSectionHeader(
                        title = "Adicionar jogo",
                        subtitle = "Escolha um app instalado para adicionar à lista",
                        accentColor = FusaoPalette.PlasmaViolet,
                        trailing = {
                            TelemetryPill(
                                text = "${shown.size} APPS",
                                color = FusaoPalette.CryoCyan,
                            )
                        },
                    )

                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Buscar") },
                        placeholder = { Text("Nome do jogo ou com.exemplo.app") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = FusaoPalette.TextPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = FusaoPalette.CryoCyan,
                            unfocusedBorderColor = FusaoPalette.BorderSubtle,
                            focusedLabelColor = FusaoPalette.CryoCyan,
                            unfocusedLabelColor = FusaoPalette.TextSecondary,
                            cursorColor = FusaoPalette.CryoCyan,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp, max = 380.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(FusaoPalette.VoidBlack.copy(alpha = 0.7f))
                            .border(1.dp, FusaoPalette.GridHairline, RoundedCornerShape(8.dp))
                    ) {
                        if (state.isBusy(Busy.APPS)) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(
                                    color = FusaoPalette.CryoCyan,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(24.dp),
                                )
                                Text(
                                    text = "Listando apps...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = FusaoPalette.TextSecondary,
                                )
                            }
                        } else if (shown.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "Nenhum aplicativo encontrado para \"$query\".",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FusaoPalette.TextMuted,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(items = shown, key = { it.pkg }) { app ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(FusaoPalette.CryoSlate.copy(alpha = 0.65f))
                                            .border(
                                                1.dp,
                                                FusaoPalette.BorderSubtle.copy(alpha = 0.4f),
                                                RoundedCornerShape(8.dp),
                                            )
                                            .clickable { onPick(app.pkg) }
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        AppIconBadge(
                                            pkg = app.pkg,
                                            label = app.label,
                                            size = 40.dp,
                                            accentColor = FusaoPalette.PlasmaViolet,
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = app.label,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = FusaoPalette.TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = app.pkg,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = FusaoPalette.TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        TelemetryPill(
                                            text = "+ ADD",
                                            color = FusaoPalette.CryoCyan,
                                            filled = true,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TacticalButton(
                            text = "Fechar",
                            onClick = onClose,
                            accentColor = FusaoPalette.TextSecondary,
                        )
                    }
                }
            }
        }
    }

    // =========================================================================
    // 5. ÁREA DE LOG — FUSAO.LOG, FUSAOD.LOG & BUGREPORT
    // =========================================================================
    @Composable
    private fun LogTab(state: FusaoUiState) {
        val busy = state.anyBusy()
        val clipboard = LocalClipboardManager.current
        var logFilter by rememberSaveable { mutableStateOf("") }
        var severityFilter by rememberSaveable { mutableIntStateOf(0) } // 0=Todos, 1=Eventos OK, 2=Alertas/Contenda

        LaunchedEffect(state.rooted) {
            if (state.rooted && state.logLines.isEmpty()) {
                vm.loadLog(false)
            }
        }

        val displayedLines = remember(state.logLines, logFilter, severityFilter) {
            val q = logFilter.trim()
            state.logLines.mapIndexedNotNull { index, raw ->
                val matchesQuery = q.isEmpty() || raw.contains(q, ignoreCase = true)
                val matchesSeverity = when (severityFilter) {
                    1 -> raw.contains("ok") || raw.contains("enter") || raw.contains("exit") || raw.contains("apply")
                    2 -> raw.contains("FALHOU") || raw.contains("CONTENDA") || raw.contains("verify-fail") ||
                        raw.contains("recuado") || raw.contains("erro", ignoreCase = true)
                    else -> true
                }
                if (matchesQuery && matchesSeverity) (index + 1) to raw else null
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TacticalPanel(
                modifier = Modifier.fillMaxWidth(),
                accentColor = FusaoPalette.CryoCyan,
                contentPadding = PaddingValues(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HudSectionHeader(
                        title = if (state.logFusaod) "fusaod.log (writer)" else "fusao.log (ganchos)",
                        subtitle = "Registro do módulo; o bugreport vai para a pasta Download",
                        accentColor = FusaoPalette.CryoCyan,
                        trailing = {
                            TelemetryPill(
                                text = "${displayedLines.size}/${state.logLines.size} LINHAS",
                                color = FusaoPalette.CryoCyan,
                                filled = true,
                            )
                        },
                    )

                    // Três ações principais preservadas: fusao.log, fusaod.log, Bugreport
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TacticalButton(
                            text = "fusao.log",
                            onClick = { vm.loadLog(false) },
                            enabled = state.rooted && !busy,
                            filled = !state.logFusaod,
                            isLoading = state.isBusy(Busy.LOG) && state.activeTarget == "fusao.log",
                            accentColor = FusaoPalette.CryoCyan,
                            modifier = Modifier.weight(1f),
                        )
                        TacticalButton(
                            text = "fusaod.log",
                            onClick = { vm.loadLog(true) },
                            enabled = state.rooted && !busy,
                            filled = state.logFusaod,
                            isLoading = state.isBusy(Busy.LOG) && state.activeTarget == "fusaod.log",
                            accentColor = FusaoPalette.PlasmaViolet,
                            modifier = Modifier.weight(1f),
                        )
                        TacticalButton(
                            text = "Bugreport",
                            onClick = { vm.bugreport() },
                            enabled = state.rooted && !busy,
                            isLoading = state.isBusy(Busy.BUGREPORT),
                            accentColor = FusaoPalette.IgnisAmber,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // Controles de profundidade (120 / 250 / 500 linhas), filtro de severidade e cópia
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        listOf(120, 250, 500).forEach { limit ->
                            val activeLimit = state.logLineLimit == limit
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (activeLimit) FusaoPalette.CryoCyan.copy(alpha = 0.2f)
                                        else FusaoPalette.VoidBlack.copy(alpha = 0.55f)
                                    )
                                    .border(
                                        1.dp,
                                        if (activeLimit) FusaoPalette.CryoCyan else FusaoPalette.BorderSubtle,
                                        RoundedCornerShape(6.dp),
                                    )
                                    .clickable(enabled = state.rooted && !busy) {
                                        vm.loadLog(state.logFusaod, limit)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "${limit}L",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (activeLimit) FusaoPalette.CryoCyan else FusaoPalette.TextSecondary,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }

                        listOf(
                            0 to "TODOS",
                            1 to "HOOKS/OK",
                            2 to "CONTENDA/ERRO",
                        ).forEach { (sKey, sLabel) ->
                            val sel = severityFilter == sKey
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (sel) FusaoPalette.PlasmaViolet.copy(alpha = 0.22f)
                                        else FusaoPalette.VoidBlack.copy(alpha = 0.55f)
                                    )
                                    .border(
                                        1.dp,
                                        if (sel) FusaoPalette.PlasmaViolet else FusaoPalette.BorderSubtle,
                                        RoundedCornerShape(6.dp),
                                    )
                                    .clickable { severityFilter = sKey }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = sLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (sel) FusaoPalette.ElectricOrchid else FusaoPalette.TextSecondary,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }

                        if (state.logLines.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(FusaoPalette.BioEmerald.copy(alpha = 0.16f))
                                    .border(1.dp, FusaoPalette.BioEmerald.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                                    .clickable {
                                        val joined = displayedLines.joinToString("\n") { it.second }
                                        clipboard.setText(AnnotatedString(joined))
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "COPIAR",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = FusaoPalette.MintPulse,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = logFilter,
                        onValueChange = { logFilter = it },
                        placeholder = {
                            Text(
                                "Filtrar linhas do log (ex.: preload, dnd, p0, verify-fail)...",
                                style = MaterialTheme.typography.bodySmall,
                                color = FusaoPalette.TextMuted,
                            )
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = FusaoPalette.TextPrimary,
                            fontFamily = FontFamily.Monospace,
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = FusaoPalette.CryoCyan,
                            unfocusedBorderColor = FusaoPalette.BorderSubtle,
                            focusedContainerColor = FusaoPalette.VoidBlack.copy(alpha = 0.65f),
                            unfocusedContainerColor = FusaoPalette.VoidBlack.copy(alpha = 0.45f),
                            cursorColor = FusaoPalette.CryoCyan,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // VIEWPORT TÉCNICO DE LOGS (LazyColumn de alta fluidez sem SelectionContainer global)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(FusaoPalette.VoidBlack.copy(alpha = 0.92f))
                    .border(1.dp, FusaoPalette.GridHairline, RoundedCornerShape(10.dp))
            ) {
                if (displayedLines.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        FusaoVectorGlyph(
                            icon = FusaoNavIcon.LOG,
                            color = FusaoPalette.TextMuted,
                            modifier = Modifier.size(30.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (state.logLines.isEmpty()) {
                                "Nenhuma linha carregada. Toque em fusao.log, fusaod.log ou Bugreport."
                            } else {
                                "Nenhuma linha corresponde ao filtro atual."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = FusaoPalette.TextMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        itemsIndexed(
                            items = displayedLines,
                            key = { _, pair -> pair.first },
                        ) { _, (lineNum, rawLine) ->
                            MonoLine(lineNum = lineNum, text = rawLine)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MonoLine(text: String, lineNum: Int? = null) {
        val highlighted = remember(text) { highlightTelemetryLine(text) }
        val gutterColor = remember(text) {
            when {
                text.contains("FALHOU") || text.contains("verify-fail") -> FusaoPalette.CrimsonCore
                text.contains("CONTENDA") || text.contains("recuado") -> FusaoPalette.IgnisAmber
                text.contains(" ok") || text.contains(": enter") || text.contains(": on") -> FusaoPalette.BioEmerald
                else -> FusaoPalette.GridHairline
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.5.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 3.dp, end = 6.dp)
                    .width(2.5.dp)
                    .height(13.dp)
                    .background(gutterColor, RoundedCornerShape(1.dp))
            )
            if (lineNum != null) {
                Text(
                    text = lineNum.toString().padStart(3, '0'),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    ),
                    color = FusaoPalette.TextMuted,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(end = 8.dp, top = 1.dp),
                )
            }
            Text(
                text = highlighted,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                ),
            )
        }
    }
}
