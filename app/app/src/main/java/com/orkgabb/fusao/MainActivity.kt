package com.orkgabb.fusao

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.graphicsLayer
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
import com.orkgabb.fusao.block.BlockStore
import com.orkgabb.fusao.block.BlockTab
import com.orkgabb.fusao.misc.MiscTab
import com.orkgabb.fusao.misc.SpeakerBoostStore
import com.orkgabb.fusao.misc.SpeakerOutputRoute
import com.orkgabb.fusao.pixels.SmartPixelsStore
import com.orkgabb.fusao.pixels.SmartPixelsTab
import com.orkgabb.fusao.ui.AppIconBadge
import com.orkgabb.fusao.ui.CyberSwitch
import com.orkgabb.fusao.ui.FusaoAmbientCanvas
import com.orkgabb.fusao.ui.FusaoCollapsibleSection
import com.orkgabb.fusao.ui.FusaoHairlineDivider
import com.orkgabb.fusao.ui.FusaoNavIcon
import com.orkgabb.fusao.ui.FusaoPalette
import com.orkgabb.fusao.ui.FusaoSectionLabel
import com.orkgabb.fusao.ui.FusaoSegmentedControl
import com.orkgabb.fusao.ui.FusaoTheme
import com.orkgabb.fusao.ui.FusaoVectorGlyph
import com.orkgabb.fusao.ui.HudSectionHeader
import com.orkgabb.fusao.ui.ReactorCoreEmblem
import com.orkgabb.fusao.ui.SegmentedItem
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
        desc = "Pisos de CPU/GPU/RAM em jogo (desliga sozinho com FAS)",
        accent = FusaoPalette.CryoCyan,
    ),
    FlagSpec(
        cmd = "framework",
        title = "framework",
        desc = "Tela 120 Hz em jogo, jobs e standby por perfil",
        accent = FusaoPalette.PlasmaViolet,
    ),
    FlagSpec(
        cmd = "perf",
        title = "perf",
        desc = "Prioridade de CPU e I/O para o jogo ativo",
        accent = FusaoPalette.IgnisAmber,
    ),
    FlagSpec(
        cmd = "dnd",
        title = "dnd",
        desc = "Não Perturbe automático ao entrar em jogos",
        accent = FusaoPalette.CryoTurquoise,
    ),
    FlagSpec(
        cmd = "ramclean",
        title = "ramclean",
        desc = "Flag legada (use Limpeza manual na aba Status)",
        accent = FusaoPalette.BioEmerald,
    ),
    FlagSpec(
        cmd = "gos",
        title = "gos",
        desc = "Encerra o Game Optimizing Service da Samsung",
        accent = FusaoPalette.CrimsonCore,
    ),
    FlagSpec(
        cmd = "fas",
        title = "fas",
        desc = "Controla frequência de CPU frame a frame (fas-rs)",
        accent = FusaoPalette.MintPulse,
    ),
)

/** Rótulos dos modos do fas-rs (margem de fps abaixo do alvo). */
private val FAS_MODE_LABELS = mapOf(
    "powersave" to "Economia",
    "balance" to "Equilíbrio",
    "performance" to "Desempenho",
    "fast" to "Rápido",
)

private data class DockTabSpec(
    val label: String,
    val icon: FusaoNavIcon,
    val badge: String?,
)

class MainActivity : ComponentActivity() {

    private val vm: FusaoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        (application as FusaoApp).prewarmRoot()
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
        val ctx = LocalContext.current
        val pixelsStore = remember(ctx) { SmartPixelsStore.get(ctx) }
        val pixels by pixelsStore.state.collectAsStateWithLifecycle()
        val pixelsBadge = if (pixels.active) "${pixels.config.litPercent}%" else null
        val blockConfig by remember(ctx) { BlockStore.get(ctx) }.config.collectAsStateWithLifecycle()
        val blockBadge = if (blockConfig.enabled) "${blockConfig.rules.size}" else null
        val speakerUi by remember(ctx) { SpeakerBoostStore.get(ctx) }.state.collectAsStateWithLifecycle()
        val miscBadge = when {
            !speakerUi.config.enabled -> null
            speakerUi.runtime.route == SpeakerOutputRoute.BLUETOOTH -> "BT"
            speakerUi.runtime.effectsAttached -> "SPK"
            else -> "ON"
        }

        val effectiveProfile = state.status.effectiveProfile
        LaunchedEffect(pixelsStore, effectiveProfile) {
            pixelsStore.setEffectiveProfile(effectiveProfile)
        }
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
                        accentColor = profileAccent,
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
                        pixelsBadge = pixelsBadge,
                        miscBadge = miscBadge,
                        blockBadge = blockBadge,
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

                    SystemBannersArea(state)

                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            val forward = targetState > initialState
                            (slideInHorizontally(
                                animationSpec = tween(230, easing = FastOutSlowInEasing),
                                initialOffsetX = { full -> if (forward) full / 8 else -full / 8 },
                            ) + fadeIn(tween(210))) togetherWith (slideOutHorizontally(
                                animationSpec = tween(170, easing = FastOutSlowInEasing),
                                targetOffsetX = { full -> if (forward) -full / 10 else full / 10 },
                            ) + fadeOut(tween(140)))
                        },
                        modifier = Modifier.weight(1f),
                        label = "fusaoTabTransition",
                    ) { currentTab ->
                        when (currentTab) {
                            0 -> StatusTab(state)
                            1 -> FlagsTab(state)
                            2 -> BlockTab(
                                rooted = state.rooted,
                                moduleInstalled = state.moduleInstalled,
                                grantBusy = state.isBusy(Busy.BLOCK),
                                anyBusy = state.anyBusy(),
                                onGrant = { vm.blockGrant() },
                                onRevoke = { vm.blockRevoke() },
                            )
                            3 -> GamesTab(state)
                            4 -> SmartPixelsTab()
                            5 -> MiscTab(
                                rooted = state.rooted,
                                moduleInstalled = state.moduleInstalled,
                                anyBusy = state.anyBusy(),
                                onActionNotice = { vm.showNotice(it) },
                            )
                            6 -> LogTab(state)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun FusaoHeaderBar(
        state: FusaoUiState,
        accentColor: Color,
        onRefresh: () -> Unit,
    ) {
        val rawVersion = state.moduleVersion
        val cleanVersion = remember(rawVersion) {
            if (rawVersion.isEmpty() || rawVersion == "—") {
                "M54"
            } else {
                rawVersion
                    .substringBefore("|")
                    .replace("version=", "")
                    .substringBefore(" ")
                    .trim()
                    .removeSuffix("-fusao")
                    .ifEmpty { "M54" }
            }
        }
        val hasStagedUpdate = remember(rawVersion) {
            rawVersion.contains("staged:") || rawVersion.contains("|")
        }
        val isRefreshing = state.isBusy(Busy.REFRESH)
        val spinTransition = rememberInfiniteTransition(label = "refreshSpin")
        val spinAngle by spinTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 850, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "refreshAngle",
        )

        val statusDotColor = when {
            !state.rooted -> FusaoPalette.CrimsonCore
            !state.moduleInstalled -> FusaoPalette.IgnisAmber
            state.status.inGame -> FusaoPalette.IgnisAmber
            else -> FusaoPalette.BioEmerald
        }
        val statusLabel = when {
            !state.ready -> "Verificando sistema..."
            !state.rooted -> "Sem root · leitura"
            !state.moduleInstalled -> "Módulo não instalado"
            state.status.inGame -> "Em jogo · ${state.labelFor(state.status.gameActive)}"
            else -> "Módulo ativo"
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            FusaoPalette.VoidBlack.copy(alpha = 0.96f),
                            FusaoPalette.VoidBlack.copy(alpha = 0.78f),
                            Color.Transparent,
                        )
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
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
                            .size(38.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(FusaoPalette.CryoSlate)
                            .border(
                                width = 1.dp,
                                color = accentColor.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(11.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_fusao_crest),
                            contentDescription = "FUSAO",
                            modifier = Modifier.size(28.dp),
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
                                filled = false,
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
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            StatusPulseDot(
                                color = statusDotColor,
                                active = state.rooted && state.moduleInstalled,
                                size = 5.5.dp,
                            )
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = FusaoPalette.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    color = FusaoPalette.CryoSlate.copy(alpha = 0.85f),
                    shape = CircleShape,
                    border = BorderStroke(
                        1.dp,
                        if (isRefreshing) FusaoPalette.CryoCyan.copy(alpha = 0.65f) else FusaoPalette.BorderSubtle,
                    ),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(enabled = !state.anyBusy(), onClick = onRefresh),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FusaoVectorGlyph(
                            icon = FusaoNavIcon.REFRESH,
                            color = if (state.anyBusy() && !isRefreshing) FusaoPalette.TextMuted else FusaoPalette.CryoCyan,
                            modifier = Modifier
                                .size(14.dp)
                                .graphicsLayer {
                                    rotationZ = if (isRefreshing) spinAngle else 0f
                                },
                        )
                        Text(
                            text = if (isRefreshing) "..." else "Atualizar",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.anyBusy() && !isRefreshing) FusaoPalette.TextMuted else FusaoPalette.TextPrimary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
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
        pixelsBadge: String?,
        miscBadge: String?,
        blockBadge: String?,
        accentColor: Color,
    ) {
        val tabs = remember(activeFlags, flagsTotal, gamesCount, inGame, pixelsBadge, miscBadge, blockBadge) {
            listOf(
                DockTabSpec("Status", FusaoNavIcon.STATUS, if (inGame) "LIVE" else null),
                DockTabSpec("Flags", FusaoNavIcon.FLAGS, "$activeFlags/$flagsTotal"),
                DockTabSpec("Bloqueio", FusaoNavIcon.BLOCK, blockBadge),
                DockTabSpec("Jogos", FusaoNavIcon.GAMES, if (gamesCount > 0) "$gamesCount" else null),
                DockTabSpec("Pixels", FusaoNavIcon.PIXELS, pixelsBadge),
                DockTabSpec("Misc", FusaoNavIcon.MISC, miscBadge),
                DockTabSpec("Log", FusaoNavIcon.LOG, null),
            )
        }
        val dockScroll = rememberScrollState()
        LaunchedEffect(selectedTab) {
            val approxStepPx = (selectedTab - 2).coerceAtLeast(0) * 180
            dockScroll.animateScrollTo(approxStepPx.coerceAtMost(dockScroll.maxValue))
        }

        Surface(
            color = FusaoPalette.VoidBlack.copy(alpha = 0.96f),
            border = BorderStroke(1.dp, FusaoPalette.GridHairline.copy(alpha = 0.8f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .horizontalScroll(dockScroll)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { idx, spec ->
                    val selected = selectedTab == idx
                    val interactionSource = remember { MutableInteractionSource() }
                    val pressed by interactionSource.collectIsPressedAsState()

                    val scale by animateFloatAsState(
                        targetValue = when {
                            pressed -> 0.92f
                            selected -> 1.04f
                            else -> 1f
                        },
                        animationSpec = spring(dampingRatio = 0.65f, stiffness = 520f),
                        label = "dockItemScale",
                    )
                    val indicatorWidth by animateDpAsState(
                        targetValue = if (selected) 20.dp else 0.dp,
                        animationSpec = spring(dampingRatio = 0.7f, stiffness = 480f),
                        label = "dockIndicator",
                    )
                    val bgColor by animateColorAsState(
                        targetValue = if (selected) accentColor.copy(alpha = 0.12f) else Color.Transparent,
                        animationSpec = tween(200),
                        label = "dockBg",
                    )
                    val itemColor by animateColorAsState(
                        targetValue = if (selected) accentColor else FusaoPalette.TextSecondary,
                        animationSpec = tween(200),
                        label = "dockTint",
                    )

                    Box(
                        modifier = Modifier
                            .width(66.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                            .clip(RoundedCornerShape(12.dp))
                            .background(bgColor)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = { onSelectTab(idx) },
                            )
                            .padding(vertical = 6.dp, horizontal = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(indicatorWidth)
                                    .height(2.5.dp)
                                    .clip(CircleShape)
                                    .background(if (selected) accentColor else Color.Transparent)
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                modifier = Modifier.height(19.dp),
                            ) {
                                FusaoVectorGlyph(
                                    icon = spec.icon,
                                    color = itemColor,
                                    modifier = Modifier.size(17.dp),
                                )
                                if (spec.badge != null) {
                                    Text(
                                        text = spec.badge,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 8.sp,
                                            lineHeight = 10.sp,
                                            letterSpacing = 0.2.sp,
                                        ),
                                        color = if (selected) FusaoPalette.VoidBlack else accentColor,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(if (selected) accentColor else FusaoPalette.ElevatedHud)
                                            .padding(horizontal = 4.dp, vertical = 1.dp),
                                    )
                                }
                            }
                            Text(
                                text = spec.label,
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
        val pulse = rememberInfiniteTransition(label = "bootPulse")
        val emblemScale by pulse.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "emblemPulse",
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_fusao_emblem),
                    contentDescription = null,
                    modifier = Modifier
                        .size(76.dp)
                        .graphicsLayer {
                            scaleX = emblemScale
                            scaleY = emblemScale
                        }
                        .clip(CircleShape),
                )
                CircularProgressIndicator(
                    color = FusaoPalette.CryoCyan,
                    strokeWidth = 2.4.dp,
                    modifier = Modifier.size(26.dp),
                )
                Text(
                    text = "Carregando estado do módulo...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FusaoPalette.TextSecondary,
                )
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
                    text = MODULE_ABSENT_NOTICE,
                    accentColor = FusaoPalette.IgnisAmber,
                    tag = "MOD AUSENTE",
                )
            }

            AnimatedVisibility(
                visible = state.notice.isNotEmpty(),
                enter = fadeIn(tween(180)) + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
                exit = fadeOut(tween(140)) + shrinkVertically(spring(stiffness = Spring.StiffnessMedium)),
            ) {
                val isError = state.notice.contains("ausente", ignoreCase = true) ||
                    state.notice.contains("Sem root", ignoreCase = true) ||
                    state.notice.contains("inválido", ignoreCase = true) ||
                    state.notice.contains("FALHOU", ignoreCase = true)
                Banner(
                    text = state.notice,
                    accentColor = if (isError) FusaoPalette.CrimsonCore else FusaoPalette.CryoCyan,
                    tag = if (isError) "ALERTA" else "AVISO",
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
            cornerRadius = 14.dp,
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TelemetryPill(
                        text = tag,
                        color = accentColor,
                        filled = true,
                    )
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
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
                            .padding(start = 8.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.14f))
                            .clickable { onClose() }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }

    // =========================================================================
    // 1. ÁREA DE STATUS — COCKPIT LIMPO E REATIVO
    // =========================================================================
    @Composable
    private fun StatusTab(state: FusaoUiState) {
        val st = state.status
        val busy = state.anyBusy()
        val accent = FusaoPalette.profileAccent(st.profile)

        val profileItems = remember {
            listOf(
                SegmentedItem("game", "Game", accent = FusaoPalette.IgnisAmber),
                SegmentedItem("balanced", "Balanced", accent = FusaoPalette.CryoCyan),
                SegmentedItem("battery", "Battery", accent = FusaoPalette.BioEmerald),
            )
        }
        val loadingProfile = if (state.isBusy(Busy.FLAG) && state.activeTarget.startsWith("profile:")) {
            state.activeTarget.removePrefix("profile:")
        } else {
            null
        }

        val currentScaleMode = when {
            st.display.startsWith("720") -> "720p"
            st.display.startsWith("810") -> "810p"
            st.display.startsWith("900") -> "900p"
            st.display.startsWith("1080") -> "reset"
            else -> ""
        }
        val scaleItems = remember {
            listOf(
                SegmentedItem("720p", "720p"),
                SegmentedItem("810p", "810p"),
                SegmentedItem("900p", "900p"),
                SegmentedItem("reset", "Stock", accent = FusaoPalette.BioEmerald),
            )
        }
        val loadingScale = if (state.isBusy(Busy.FLAG) && state.activeTarget.startsWith("display:")) {
            state.activeTarget.removePrefix("display:")
        } else {
            null
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // HERO CARD: Núcleo vivo + Seletor segmentado de perfil + Jogo em foco (quando ativo)
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
                            alpha = 0.22f,
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            FusaoPalette.VoidBlack.copy(alpha = 0.45f),
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
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = when (st.profile) {
                                                "game" -> "Perfil Game"
                                                "battery" -> "Perfil Battery"
                                                "balanced" -> "Perfil Balanced"
                                                else -> "Perfil ${st.profile.ifEmpty { "—" }}"
                                            },
                                            style = MaterialTheme.typography.headlineMedium,
                                            color = FusaoPalette.TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (st.profileState.isNotEmpty()) {
                                            TelemetryPill(
                                                text = when (st.profileState) {
                                                    "applied" -> "APLICADO"
                                                    "partial" -> "PARCIAL"
                                                    "pending" -> "AGUARDANDO"
                                                    "failed" -> "FALHOU"
                                                    else -> st.profileState.uppercase()
                                                },
                                                color = when (st.profileState) {
                                                    "applied" -> FusaoPalette.BioEmerald
                                                    "partial" -> FusaoPalette.IgnisAmber
                                                    "failed" -> FusaoPalette.CrimsonCore
                                                    else -> FusaoPalette.CryoCyan
                                                },
                                                filled = st.profileState == "applied" || st.profileState == "failed",
                                            )
                                        }
                                    }
                                    Text(
                                        text = when {
                                            st.profileEffective.isNotEmpty() && st.profileEffective != st.profile ->
                                                "Em jogo aplica ${st.profileEffective.uppercase()} automaticamente"
                                            st.profile == "game" && st.fas && st.fasSupported ->
                                                "Máximo desempenho · CPU gerenciada pelo FAS"
                                            st.profile == "game" -> "Foco total em desempenho"
                                            st.profile == "battery" -> "Economia · tela adaptativa · vira GAME em jogo"
                                            st.profile == "balanced" -> "Equilíbrio entre fluidez e autonomia"
                                            else -> "Lendo perfil do módulo..."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = FusaoPalette.TextSecondary,
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                ReactorCoreEmblem(
                                    profile = st.profile,
                                    inGame = st.inGame,
                                    daemonsAlive = st.runningDaemonsCount,
                                )
                            }

                            FusaoSegmentedControl(
                                items = profileItems,
                                selected = st.profile,
                                onSelect = { vm.setProfile(it) },
                                enabled = state.rooted && !busy,
                                loadingValue = loadingProfile,
                                defaultAccent = accent,
                            )

                            AnimatedVisibility(
                                visible = st.inGame,
                                enter = fadeIn(tween(200)) + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
                                exit = fadeOut(tween(150)) + shrinkVertically(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(FusaoPalette.VoidBlack.copy(alpha = 0.68f))
                                        .border(
                                            1.dp,
                                            FusaoPalette.IgnisAmber.copy(alpha = 0.48f),
                                            RoundedCornerShape(12.dp),
                                        )
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    AppIconBadge(
                                        pkg = st.gameActive,
                                        label = state.labelFor(st.gameActive),
                                        size = 38.dp,
                                        accentColor = FusaoPalette.IgnisAmber,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = state.labelFor(st.gameActive),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = FusaoPalette.TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "Sessão de jogo ativa",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = FusaoPalette.IgnisAmber,
                                        )
                                    }
                                    TelemetryPill(
                                        text = "EM FOCO",
                                        color = FusaoPalette.IgnisAmber,
                                        filled = true,
                                        pulsingDot = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // PAINEL UNIFICADO: TELA, TÉRMICA E ESCALA RÁPIDA
            item(key = "status_display_thermal") {
                val thermalColor = when {
                    st.thermal.contains("severe", true) || st.thermal.contains("critical", true) -> FusaoPalette.CrimsonCore
                    st.thermal.contains("moderate", true) -> FusaoPalette.IgnisAmber
                    st.thermal.contains("light", true) || st.thermal.contains("none", true) -> FusaoPalette.BioEmerald
                    else -> FusaoPalette.CryoCyan
                }

                TacticalPanel(
                    modifier = Modifier.fillMaxWidth(),
                    accentColor = FusaoPalette.CryoCyan,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Surface(
                                modifier = Modifier.weight(1f),
                                color = FusaoPalette.VoidBlack.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, FusaoPalette.GridHairline),
                            ) {
                                Column(
                                    modifier = Modifier.padding(11.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    FusaoSectionLabel("TELA")
                                    Text(
                                        text = st.display.ifEmpty { "—" },
                                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                                        color = FusaoPalette.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "Escala reversível",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = FusaoPalette.TextMuted,
                                        maxLines = 1,
                                    )
                                }
                            }

                            Surface(
                                modifier = Modifier.weight(1f),
                                color = FusaoPalette.VoidBlack.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, FusaoPalette.GridHairline),
                            ) {
                                Column(
                                    modifier = Modifier.padding(11.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    FusaoSectionLabel("TÉRMICA")
                                    Text(
                                        text = st.thermal.ifEmpty { "—" },
                                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                                        color = thermalColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "Teto 78 °C · Bat 45 °C",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = FusaoPalette.TextMuted,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }

                        FusaoSegmentedControl(
                            items = scaleItems,
                            selected = currentScaleMode,
                            onSelect = { vm.setDisplay(it) },
                            enabled = state.rooted && !busy,
                            loadingValue = loadingScale,
                            defaultAccent = FusaoPalette.CryoCyan,
                        )
                    }
                }
            }

            // LIMPEZA DE RAM COMPACTA (SEM BLOCO GIGANTE DE TERMINAL!)
            item(key = "status_ramclean") {
                RamcleanPanel(state)
            }

            // SERVIÇOS DO MÓDULO (barra horizontal limpa de 3 daemons)
            item(key = "status_daemons") {
                DaemonsCompactPanel(st)
            }
        }
    }

    @Composable
    private fun DaemonsCompactPanel(st: FusaoStatus) {
        var showDesc by rememberSaveable { mutableStateOf(false) }
        val services = listOf(
            Triple("fusaod", st.fusaodRunning, "Pisos de CPU/GPU/RAM quando o jogo está aberto"),
            Triple("watch", st.watchRunning, "Aplica o perfil certo ao entrar e sair dos jogos"),
            Triple("observer", st.observerRunning, "Detecta abertura do jogo na hora (fallback: 5 s)"),
        )

        TacticalPanel(
            modifier = Modifier.fillMaxWidth(),
            accentColor = FusaoPalette.PlasmaViolet,
            contentPadding = PaddingValues(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HudSectionHeader(
                    title = "Serviços",
                    accentColor = FusaoPalette.PlasmaViolet,
                    trailing = {
                        TelemetryPill(
                            text = "${st.runningDaemonsCount}/3 ATIVOS",
                            color = if (st.runningDaemonsCount == 3) FusaoPalette.BioEmerald else FusaoPalette.CryoCyan,
                            filled = st.runningDaemonsCount > 0,
                            pulsingDot = st.runningDaemonsCount == 3,
                        )
                    },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    services.forEach { (name, running, _) ->
                        val dotColor = if (running) FusaoPalette.BioEmerald else FusaoPalette.TextMuted
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = FusaoPalette.VoidBlack.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (running) FusaoPalette.BioEmerald.copy(alpha = 0.35f) else FusaoPalette.GridHairline,
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                StatusPulseDot(color = dotColor, active = running, size = 6.dp)
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                                    color = if (running) FusaoPalette.TextPrimary else FusaoPalette.TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                FusaoCollapsibleSection(
                    title = "O que cada serviço faz",
                    expanded = showDesc,
                    onToggle = { showDesc = !showDesc },
                    accentColor = FusaoPalette.TextSecondary,
                ) {
                    services.forEach { (name, running, desc) ->
                        Text(
                            text = "$name (${if (running) "ativo" else "parado"}): $desc",
                            style = MaterialTheme.typography.bodySmall,
                            color = FusaoPalette.TextSecondary,
                        )
                    }
                }
            }
        }
    }

    // =========================================================================
    // 2. ÁREA DE FLAGS — LISTA AGRUPADA E LIMPA
    // =========================================================================
    @Composable
    private fun FlagsTab(state: FusaoUiState) {
        val st = state.status
        val busy = state.anyBusy()
        val specs = remember(st.fasSupported, st.ramclean) {
            FLAG_SPECS.filter { (it.cmd != "fas" || st.fasSupported) && (it.cmd != "ramclean" || st.ramclean) }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "flags_card") {
                TacticalPanel(
                    modifier = Modifier.fillMaxWidth(),
                    accentColor = FusaoPalette.CryoCyan,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudSectionHeader(
                            title = "Controles do Módulo",
                            subtitle = "Ativa ou pausa recursos em execução",
                            accentColor = FusaoPalette.CryoCyan,
                            trailing = {
                                TelemetryPill(
                                    text = "${st.activeFlagsCount}/${st.flagsTotal}",
                                    color = FusaoPalette.CryoCyan,
                                    filled = true,
                                )
                            },
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        specs.forEachIndexed { idx, spec ->
                            val checked = st.flag(spec.cmd)
                            val isThisFlagBusy = state.isBusy(Busy.FLAG) && state.activeTarget == spec.cmd
                            val available = state.rooted && state.moduleInstalled

                            val specialTag: Pair<String, Color>? = when {
                                isThisFlagBusy -> "APLICANDO" to FusaoPalette.CryoCyan
                                !available -> "SEM ROOT" to FusaoPalette.TextMuted
                                spec.cmd == "fas" && checked && !st.fasBinOk -> "AUSENTE" to FusaoPalette.CrimsonCore
                                spec.cmd == "fas" && checked && st.fasState == "working" -> "CONTROLANDO" to spec.accent
                                spec.cmd == "fas" && checked && st.fasState == "waiting" -> "AGUARDANDO" to spec.accent
                                spec.cmd == "dnd" && checked && st.inGame -> "NO JOGO" to spec.accent
                                spec.cmd == "gos" && !checked -> "PAUSADO" to FusaoPalette.IgnisAmber
                                else -> null
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        enabled = state.rooted && !busy,
                                        onClick = { vm.toggleFlag(spec.cmd, !checked) },
                                    )
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(if (checked) spec.accent else FusaoPalette.TextMuted.copy(alpha = 0.4f))
                                        )
                                        Text(
                                            text = spec.title,
                                            style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                                            color = FusaoPalette.TextPrimary,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                        if (specialTag != null) {
                                            TelemetryPill(
                                                text = specialTag.first,
                                                color = specialTag.second,
                                                filled = true,
                                            )
                                        }
                                    }
                                    Text(
                                        text = spec.desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = FusaoPalette.TextSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
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

                            if (idx < specs.lastIndex) {
                                FusaoHairlineDivider()
                            }
                        }
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

    /** Painel do FAS (fas-rs): estado em jogo, trava de GPU saturada e modo. */
    @Composable
    private fun FasPanel(state: FusaoUiState) {
        val st = state.status
        val busy = state.anyBusy()
        val accent = FusaoPalette.MintPulse
        val working = st.fas && st.fasState == "working"

        val loadingMode = if (state.isBusy(Busy.FLAG) && state.activeTarget.startsWith("fas_mode:")) {
            state.activeTarget.removePrefix("fas_mode:")
        } else {
            null
        }

        TacticalPanel(
            modifier = Modifier.fillMaxWidth(),
            accentColor = if (st.fas) accent else FusaoPalette.BorderSubtle,
            highlighted = working,
            contentPadding = PaddingValues(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HudSectionHeader(
                    title = "Modo do FAS",
                    subtitle = "Ajusta margem de FPS da CPU frame a frame",
                    accentColor = accent,
                    trailing = {
                        TelemetryPill(
                            text = when {
                                !st.fas -> "DESLIGADO"
                                !st.fasBinOk -> "AUSENTE"
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
                            TelemetryPill(
                                text = "GPU NO LIMITE",
                                color = FusaoPalette.IgnisAmber,
                                filled = true,
                            )
                        }
                    }
                }

                FAS_MODES.chunked(2).forEach { rowModes ->
                    val segItems = remember(rowModes) {
                        rowModes.map { mode ->
                            SegmentedItem(
                                value = mode,
                                label = FAS_MODE_LABELS[mode] ?: mode,
                            )
                        }
                    }
                    FusaoSegmentedControl(
                        items = segItems,
                        selected = st.fasMode,
                        onSelect = { vm.setFasMode(it) },
                        enabled = state.rooted && !busy,
                        loadingValue = loadingMode,
                        defaultAccent = accent,
                    )
                }

                Text(
                    text = "Teto de FPS por jogo fica na aba Jogos (toque no selo FAS do jogo).",
                    style = MaterialTheme.typography.bodySmall,
                    color = FusaoPalette.TextMuted,
                )
            }
        }
    }

    /**
     * Limpeza de RAM compacta (`fusao ramclean now`), sem bloco gigante de linhas brutas por padrão.
     */
    @Composable
    private fun RamcleanPanel(state: FusaoUiState) {
        val busy = state.anyBusy()
        val ramcleanTelemetry = remember(state.ramcleanInfo) {
            RamcleanTelemetry.fromLines(state.ramcleanInfo)
        }
        var showRawDetails by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(state.rooted, state.moduleInstalled) {
            if (state.rooted && state.moduleInstalled) {
                vm.loadRamcleanIfEmpty()
            }
        }

        val deltaVal = ramcleanTelemetry.lastDeltaMb
        val deltaAccent = when {
            deltaVal != null && deltaVal > 0 -> FusaoPalette.BioEmerald
            deltaVal != null && deltaVal < 0 -> FusaoPalette.IgnisAmber
            else -> FusaoPalette.TextSecondary
        }

        TacticalPanel(
            modifier = Modifier.fillMaxWidth(),
            accentColor = FusaoPalette.BioEmerald,
            contentPadding = PaddingValues(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HudSectionHeader(
                    title = "Limpeza de RAM",
                    subtitle = "Reclaim MGLRU seletivo · preserva cache ativo",
                    accentColor = FusaoPalette.BioEmerald,
                    trailing = {
                        val badge = when {
                            state.status.ramclean -> "NO JOGO"
                            deltaVal != null -> formatDeltaMb(deltaVal)
                            else -> "MGLRU"
                        }
                        TelemetryPill(
                            text = badge,
                            color = deltaAccent,
                            filled = (deltaVal ?: 0) > 0 || state.status.ramclean,
                        )
                    },
                )

                if (
                    ramcleanTelemetry.lastTransition.isNotEmpty() ||
                    ramcleanTelemetry.lastAvailTransition.isNotEmpty() ||
                    ramcleanTelemetry.lastAvailKb.isNotEmpty()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MetricMiniCard(
                            label = "LIBERADO (Δ)",
                            value = formatDeltaMb(ramcleanTelemetry.lastDeltaMb),
                            accent = deltaAccent,
                            modifier = Modifier.weight(0.95f),
                        )
                        MetricMiniCard(
                            label = "DISPONÍVEL",
                            value = availTransitionMb(ramcleanTelemetry),
                            accent = FusaoPalette.CryoCyan,
                            modifier = Modifier.weight(1.45f),
                        )
                        MetricMiniCard(
                            label = "MGLRU / TRIM",
                            value = mglruTrimSummary(ramcleanTelemetry),
                            accent = FusaoPalette.TextPrimary,
                            modifier = Modifier.weight(1.1f),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TacticalButton(
                        text = "Ler status",
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
                        modifier = Modifier.weight(1.3f),
                    )
                }

                if (state.ramcleanInfo.isNotEmpty()) {
                    FusaoCollapsibleSection(
                        title = "Detalhes técnicos",
                        expanded = showRawDetails,
                        onToggle = { showRawDetails = !showRawDetails },
                        accentColor = FusaoPalette.TextSecondary,
                        summaryTag = "${ramcleanTelemetry.recentEvents.size} registros",
                    ) {
                        Surface(
                            color = FusaoPalette.VoidBlack.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, FusaoPalette.GridHairline),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SelectionContainer {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    state.ramcleanInfo.takeLast(6).forEach { line ->
                                        val highlighted = remember(line) { highlightTelemetryLine(line) }
                                        Text(
                                            text = highlighted,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.5.sp,
                                                lineHeight = 14.5.sp,
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

    private fun kbToMb(kb: String): String =
        kb.trim().toLongOrNull()?.let { "${it / 1024} MB" } ?: "—"

    private fun formatDeltaMb(deltaMb: Int?): String = when {
        deltaMb == null -> "—"
        deltaMb > 0 -> "+$deltaMb MB"
        else -> "$deltaMb MB"
    }

    private fun availTransitionMb(telemetry: RamcleanTelemetry): String {
        val parts = telemetry.lastAvailTransition.split("->").mapNotNull { it.trim().toLongOrNull() }
        if (parts.size == 2) return "${parts[0] / 1024} → ${parts[1] / 1024} MB"
        return kbToMb(telemetry.lastAvailKb)
    }

    private fun mglruTrimSummary(telemetry: RamcleanTelemetry): String {
        val mode = telemetry.lastMglruTag.substringAfter(':', telemetry.lastMglruTag).takeIf { it.isNotEmpty() }
        val trimCount = telemetry.lastTrimmedCount
        return when {
            mode != null && trimCount != null -> "$mode ($trimCount)"
            mode != null -> mode
            trimCount != null -> "$trimCount trim"
            else -> "—"
        }
    }

    @Composable
    private fun MetricMiniCard(
        label: String,
        value: String,
        accent: Color,
        modifier: Modifier = Modifier,
    ) {
        val valueFontSize = if (value.length > 12) 10.5.sp else 12.5.sp
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(FusaoPalette.VoidBlack.copy(alpha = 0.55f))
                .border(1.dp, FusaoPalette.GridHairline, RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
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

    // =========================================================================
    // 4. ÁREA DE JOGOS — INSTALADOS NO TOPO + BUSCA FLUIDA
    // =========================================================================
    @Composable
    private fun GamesTab(state: FusaoUiState) {
        val ctx = LocalContext.current
        var pickerOpen by rememberSaveable { mutableStateOf(false) }
        var filterQuery by rememberSaveable { mutableStateOf("") }
        var showAllCatalog by rememberSaveable { mutableStateOf(false) }
        val busy = state.anyBusy()

        LaunchedEffect(state.rooted) {
            if (state.rooted && state.games.isEmpty()) {
                vm.loadGames()
            }
        }

        val installedGamesCount = remember(state.games, state.appLabelsMap) {
            state.games.count { it in state.appLabelsMap }
        }
        val effectiveShowAll = showAllCatalog || installedGamesCount == 0

        val filteredGames = remember(state.games, state.appLabelsMap, filterQuery, effectiveShowAll) {
            val q = filterQuery.trim()
            val base = state.games.filter { pkg ->
                val installed = pkg in state.appLabelsMap
                val scopeMatch = effectiveShowAll || installed
                if (!scopeMatch) return@filter false
                if (q.isEmpty()) true
                else {
                    val label = state.labelFor(pkg)
                    label.contains(q, ignoreCase = true) || pkg.contains(q, ignoreCase = true)
                }
            }
            base.sortedWith(
                compareByDescending<String> { it == state.status.gameActive }
                    .thenByDescending { it in state.appLabelsMap }
                    .thenBy { state.labelFor(it).lowercase() }
            )
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
                        subtitle = if (installedGamesCount > 0) {
                            "$installedGamesCount instalados · ${state.games.size} no catálogo"
                        } else {
                            "Apps que ativam o FUSAO ao abrir"
                        },
                        accentColor = FusaoPalette.CryoCyan,
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TacticalButton(
                                    text = "Atualizar",
                                    onClick = { vm.loadGames() },
                                    enabled = state.rooted && !busy,
                                    isLoading = state.isBusy(Busy.GAMELIST) && state.activeTarget == "gamelist_load",
                                    accentColor = FusaoPalette.CryoCyan,
                                )
                                TacticalButton(
                                    text = "+ App",
                                    onClick = {
                                        vm.loadApps(ctx)
                                        pickerOpen = true
                                    },
                                    enabled = state.rooted && !busy,
                                    filled = true,
                                    accentColor = FusaoPalette.PlasmaViolet,
                                )
                            }
                        },
                    )

                    if (installedGamesCount > 0) {
                        val scopeItems = remember(installedGamesCount, state.games.size) {
                            listOf(
                                SegmentedItem(false, "Instalados ($installedGamesCount)"),
                                SegmentedItem(true, "Catálogo (${state.games.size})"),
                            )
                        }
                        FusaoSegmentedControl(
                            items = scopeItems,
                            selected = showAllCatalog,
                            onSelect = { showAllCatalog = it },
                            defaultAccent = FusaoPalette.CryoCyan,
                        )
                    }

                    if (filteredGames.size > 4 || filterQuery.isNotEmpty()) {
                        OutlinedTextField(
                            value = filterQuery,
                            onValueChange = { filterQuery = it },
                            placeholder = {
                                Text(
                                    "Filtrar por nome ou pacote...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FusaoPalette.TextMuted,
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = FusaoPalette.TextPrimary),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = FusaoPalette.CryoCyan,
                                unfocusedBorderColor = FusaoPalette.GridHairline,
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
                            .padding(vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FusaoVectorGlyph(
                            icon = FusaoNavIcon.GAMES,
                            color = FusaoPalette.TextMuted,
                            modifier = Modifier.size(28.dp),
                        )
                        Text(
                            text = if (state.games.isEmpty()) {
                                "Nenhum jogo na lista"
                            } else {
                                "Nenhum jogo encontrado para \"$filterQuery\""
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = FusaoPalette.TextSecondary,
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

                        Surface(
                            color = if (isCurrentlyRunning) FusaoPalette.CryoSlate else FusaoPalette.ObsidianSurface.copy(alpha = 0.92f),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(
                                1.dp,
                                when {
                                    isCurrentlyRunning -> FusaoPalette.IgnisAmber.copy(alpha = 0.65f)
                                    isInstalled -> FusaoPalette.GridHairline
                                    else -> FusaoPalette.GridHairline.copy(alpha = 0.55f)
                                },
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    AppIconBadge(
                                        pkg = pkg,
                                        label = label,
                                        size = 42.dp,
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
                                                color = if (isInstalled) FusaoPalette.TextPrimary else FusaoPalette.TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            if (isCurrentlyRunning) {
                                                TelemetryPill(
                                                    text = "ATIVO",
                                                    color = FusaoPalette.IgnisAmber,
                                                    filled = true,
                                                    pulsingDot = true,
                                                )
                                            }
                                        }
                                        if (label != pkg) {
                                            Text(
                                                text = pkg,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = FusaoPalette.TextMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        } else if (!isInstalled && state.apps.isNotEmpty()) {
                                            Text(
                                                text = "Não instalado",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = FusaoPalette.TextMuted,
                                                maxLines = 1,
                                            )
                                        }
                                        if (state.status.fas && state.status.fasSupported) {
                                            val fasCap = fasCapOf(state.fasTargets[pkg])
                                            val isFasBusy = state.isBusy(Busy.FLAG) && state.activeTarget == "fas_target:$pkg"
                                            val fasOff = fasCap == FAS_CAP_OFF
                                            TelemetryPill(
                                                text = if (isFasBusy) "FAS · …" else "FAS · ${fasCapLabel(fasCap)}",
                                                color = if (fasOff) FusaoPalette.TextMuted else FusaoPalette.MintPulse,
                                                filled = fasCap != null && !fasOff,
                                                modifier = Modifier
                                                    .clip(CircleShape)
                                                    .clickable(enabled = state.rooted && !busy) { vm.cycleFasCap(pkg) },
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(FusaoPalette.CrimsonCore.copy(alpha = 0.12f))
                                        .border(
                                            1.dp,
                                            FusaoPalette.CrimsonCore.copy(alpha = 0.35f),
                                            CircleShape,
                                        )
                                        .clickable(enabled = state.rooted && !busy) { vm.gameDel(pkg) }
                                        .padding(horizontal = 11.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isDeletingThis) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(13.dp),
                                            strokeWidth = 2.dp,
                                            color = FusaoPalette.CrimsonCore,
                                        )
                                    } else {
                                        Text(
                                            text = "Remover",
                                            style = MaterialTheme.typography.labelSmall,
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
                        subtitle = "Escolha um app instalado",
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
                        placeholder = { Text("Buscar app...") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = FusaoPalette.TextPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = FusaoPalette.CryoCyan,
                            unfocusedBorderColor = FusaoPalette.GridHairline,
                            cursorColor = FusaoPalette.CryoCyan,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp, max = 380.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(FusaoPalette.VoidBlack.copy(alpha = 0.7f))
                            .border(1.dp, FusaoPalette.GridHairline, RoundedCornerShape(12.dp))
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
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(FusaoPalette.CryoSlate.copy(alpha = 0.6f))
                                            .clickable { onPick(app.pkg) }
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        AppIconBadge(
                                            pkg = app.pkg,
                                            label = app.label,
                                            size = 38.dp,
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
                                                color = FusaoPalette.TextMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        TelemetryPill(
                                            text = "+",
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
        var severityFilter by rememberSaveable { mutableIntStateOf(0) }

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

        val logSourceItems = remember {
            listOf(
                SegmentedItem(false, "fusao.log", accent = FusaoPalette.CryoCyan),
                SegmentedItem(true, "fusaod.log", accent = FusaoPalette.PlasmaViolet),
            )
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
                        title = if (state.logFusaod) "fusaod.log" else "fusao.log",
                        subtitle = "Telemetria e diagnóstico",
                        accentColor = FusaoPalette.CryoCyan,
                        trailing = {
                            TacticalButton(
                                text = "Bugreport",
                                onClick = { vm.bugreport() },
                                enabled = state.rooted && !busy,
                                isLoading = state.isBusy(Busy.BUGREPORT),
                                accentColor = FusaoPalette.IgnisAmber,
                            )
                        },
                    )

                    FusaoSegmentedControl(
                        items = logSourceItems,
                        selected = state.logFusaod,
                        onSelect = { vm.loadLog(it) },
                        enabled = state.rooted && !busy,
                        loadingValue = if (state.isBusy(Busy.LOG)) (state.activeTarget == "fusaod.log") else null,
                        defaultAccent = FusaoPalette.CryoCyan,
                    )

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
                                    .clip(CircleShape)
                                    .background(
                                        if (activeLimit) FusaoPalette.CryoCyan.copy(alpha = 0.2f)
                                        else FusaoPalette.VoidBlack.copy(alpha = 0.55f)
                                    )
                                    .border(
                                        1.dp,
                                        if (activeLimit) FusaoPalette.CryoCyan else FusaoPalette.GridHairline,
                                        CircleShape,
                                    )
                                    .clickable(enabled = state.rooted && !busy) {
                                        vm.loadLog(state.logFusaod, limit)
                                    }
                                    .padding(horizontal = 9.dp, vertical = 4.dp)
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
                            1 to "EVENTOS",
                            2 to "ERROS",
                        ).forEach { (sKey, sLabel) ->
                            val sel = severityFilter == sKey
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(
                                        if (sel) FusaoPalette.PlasmaViolet.copy(alpha = 0.22f)
                                        else FusaoPalette.VoidBlack.copy(alpha = 0.55f)
                                    )
                                    .border(
                                        1.dp,
                                        if (sel) FusaoPalette.PlasmaViolet else FusaoPalette.GridHairline,
                                        CircleShape,
                                    )
                                    .clickable { severityFilter = sKey }
                                    .padding(horizontal = 9.dp, vertical = 4.dp)
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
                                    .clip(CircleShape)
                                    .background(FusaoPalette.BioEmerald.copy(alpha = 0.16f))
                                    .border(1.dp, FusaoPalette.BioEmerald.copy(alpha = 0.45f), CircleShape)
                                    .clickable {
                                        val joined = displayedLines.joinToString("\n") { it.second }
                                        clipboard.setText(AnnotatedString(joined))
                                        vm.showNotice("Log copiado (${displayedLines.size} linhas)")
                                    }
                                    .padding(horizontal = 9.dp, vertical = 4.dp)
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
                                "Filtrar linhas (${displayedLines.size}/${state.logLines.size})...",
                                style = MaterialTheme.typography.bodySmall,
                                color = FusaoPalette.TextMuted,
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = FusaoPalette.TextPrimary,
                            fontFamily = FontFamily.Monospace,
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = FusaoPalette.CryoCyan,
                            unfocusedBorderColor = FusaoPalette.GridHairline,
                            focusedContainerColor = FusaoPalette.VoidBlack.copy(alpha = 0.65f),
                            unfocusedContainerColor = FusaoPalette.VoidBlack.copy(alpha = 0.45f),
                            cursorColor = FusaoPalette.CryoCyan,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(FusaoPalette.VoidBlack.copy(alpha = 0.90f))
                    .border(1.dp, FusaoPalette.GridHairline, RoundedCornerShape(16.dp))
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
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (state.logLines.isEmpty()) {
                                "Nenhuma linha carregada. Selecione fusao.log ou fusaod.log."
                            } else {
                                "Nenhuma linha corresponde ao filtro."
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
