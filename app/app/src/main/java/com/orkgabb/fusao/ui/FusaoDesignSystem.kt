package com.orkgabb.fusao.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orkgabb.fusao.AppIconCache
import com.orkgabb.fusao.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Paleta oficial Cyber-Cryo // Reactor Core M54.
 * Projetada especificamente para o painel Super AMOLED Plus do Galaxy M54 (1080x2400),
 * combinando preto profundo com camadas tonais de obsidiana, ciano criogênico,
 * violeta elétrico e âmbar de ignição.
 */
object FusaoPalette {
    val VoidBlack = Color(0xFF04060A)
    val AbyssNavy = Color(0xFF070B13)
    val ObsidianSurface = Color(0xFF0B101C)
    val CryoSlate = Color(0xFF101829)
    val ElevatedHud = Color(0xFF162138)
    val GlassOverlay = Color(0xD90A0F1D)
    val GridHairline = Color(0xFF1C2A46)
    val BorderSubtle = Color(0xFF233454)

    val CryoCyan = Color(0xFF00F0FF)
    val CryoTurquoise = Color(0xFF06B6D4)
    val CryoCyanDim = Color(0xFF0E7490)

    val PlasmaViolet = Color(0xFF8B5CF6)
    val ElectricOrchid = Color(0xFFA855F7)
    val PlasmaDeep = Color(0xFF4C1D95)

    val IgnisAmber = Color(0xFFFF9F1C)
    val SolarGold = Color(0xFFFFB703)

    val BioEmerald = Color(0xFF10B981)
    val MintPulse = Color(0xFF34D399)

    val CrimsonCore = Color(0xFFF43F5E)
    val CrimsonSoft = Color(0xFFFB7185)

    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
    val TextCode = Color(0xFFE2E8F0)

    fun profileAccent(profile: String): Color = when (profile) {
        "game" -> IgnisAmber
        "battery" -> BioEmerald
        "balanced" -> CryoCyan
        else -> CryoCyan
    }

    fun profileSecondary(profile: String): Color = when (profile) {
        "game" -> PlasmaViolet
        "battery" -> CryoTurquoise
        "balanced" -> PlasmaViolet
        else -> PlasmaViolet
    }
}

private val FusaoColorScheme = darkColorScheme(
    primary = FusaoPalette.CryoCyan,
    onPrimary = FusaoPalette.VoidBlack,
    primaryContainer = FusaoPalette.ElevatedHud,
    onPrimaryContainer = FusaoPalette.CryoCyan,
    secondary = FusaoPalette.PlasmaViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1E1638),
    onSecondaryContainer = FusaoPalette.ElectricOrchid,
    tertiary = FusaoPalette.IgnisAmber,
    onTertiary = FusaoPalette.VoidBlack,
    tertiaryContainer = Color(0xFF2B1C09),
    onTertiaryContainer = FusaoPalette.SolarGold,
    background = FusaoPalette.VoidBlack,
    onBackground = FusaoPalette.TextPrimary,
    surface = FusaoPalette.ObsidianSurface,
    onSurface = FusaoPalette.TextPrimary,
    surfaceVariant = FusaoPalette.CryoSlate,
    onSurfaceVariant = FusaoPalette.TextSecondary,
    error = FusaoPalette.CrimsonCore,
    onError = Color.White,
    outline = FusaoPalette.BorderSubtle,
)

private val FusaoTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = 1.2.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.8.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.6.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.15.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.9.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.8.sp,
    ),
)

@Composable
fun FusaoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FusaoColorScheme,
        typography = FusaoTypography,
        content = content,
    )
}

/**
 * Shape chanfrado técnico a 45° para painéis, botões e badges do FUSAO.
 */
fun chamferedHudShape(
    topStartPx: Float = 0f,
    topEndPx: Float = 18f,
    bottomEndPx: Float = 0f,
    bottomStartPx: Float = 18f,
) = GenericShape { size: Size, _: LayoutDirection ->
    val w = size.width
    val h = size.height
    val ts = min(topStartPx, min(w, h) * 0.35f)
    val te = min(topEndPx, min(w, h) * 0.35f)
    val be = min(bottomEndPx, min(w, h) * 0.35f)
    val bs = min(bottomStartPx, min(w, h) * 0.35f)

    moveTo(ts, 0f)
    lineTo(w - te, 0f)
    if (te > 0f) lineTo(w, te)
    lineTo(w, h - be)
    if (be > 0f) lineTo(w - be, h)
    lineTo(bs, h)
    if (bs > 0f) lineTo(0f, h - bs)
    lineTo(0f, ts)
    if (ts > 0f) close() else close()
}

/**
 * Fundo tático AMOLED com malha técnica de baixa opacidade e iluminação radial localizada.
 * Usa `drawWithCache` para evitar realocação de objetos durante recomposições.
 */
@Composable
fun FusaoAmbientCanvas(
    accentColor: Color = FusaoPalette.CryoCyan,
    secondaryColor: Color = FusaoPalette.PlasmaViolet,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val animAccent by animateColorAsState(
        targetValue = accentColor,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "ambientAccent",
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FusaoPalette.VoidBlack)
            .drawWithCache {
                val step = 36.dp.toPx()
                val topGlow = Brush.radialGradient(
                    colors = listOf(
                        animAccent.copy(alpha = 0.11f),
                        secondaryColor.copy(alpha = 0.05f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.82f, size.height * 0.08f),
                    radius = size.width * 0.85f,
                )
                val bottomGlow = Brush.radialGradient(
                    colors = listOf(
                        secondaryColor.copy(alpha = 0.09f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.15f, size.height * 0.92f),
                    radius = size.width * 0.75f,
                )
                val gridColor = FusaoPalette.GridHairline.copy(alpha = 0.28f)
                onDrawBehind {
                    drawRect(brush = topGlow)
                    drawRect(brush = bottomGlow)
                    var x = 0f
                    while (x < size.width) {
                        drawLine(
                            color = gridColor,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1f,
                        )
                        x += step
                    }
                    var y = 0f
                    while (y < size.height) {
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f,
                        )
                        y += step
                    }
                }
            },
        content = content,
    )
}

/**
 * Painel modular tático com cantos chanfrados, retículas de precisão em Canvas
 * e acabamento metálico/AMOLED.
 */
@Composable
fun TacticalPanel(
    modifier: Modifier = Modifier,
    accentColor: Color = FusaoPalette.CryoCyan,
    containerColor: Color = FusaoPalette.ObsidianSurface,
    elevated: Boolean = false,
    highlighted: Boolean = false,
    chamferTopStart: Dp = 4.dp,
    chamferTopEnd: Dp = 14.dp,
    chamferBottomEnd: Dp = 4.dp,
    chamferBottomStart: Dp = 14.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val shape = remember(chamferTopStart, chamferTopEnd, chamferBottomEnd, chamferBottomStart, density) {
        with(density) {
            chamferedHudShape(
                topStartPx = chamferTopStart.toPx(),
                topEndPx = chamferTopEnd.toPx(),
                bottomEndPx = chamferBottomEnd.toPx(),
                bottomStartPx = chamferBottomStart.toPx(),
            )
        }
    }
    val borderAlpha = if (highlighted) 0.62f else if (elevated) 0.34f else 0.22f
    val topHighlightAlpha = if (highlighted) 0.16f else if (elevated) 0.08f else 0.04f

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        containerColor.copy(alpha = 0.96f),
                        FusaoPalette.AbyssNavy.copy(alpha = 0.98f),
                    )
                )
            )
            .border(
                width = if (highlighted) 1.3.dp else 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        accentColor.copy(alpha = borderAlpha),
                        FusaoPalette.BorderSubtle.copy(alpha = 0.35f),
                        accentColor.copy(alpha = borderAlpha * 0.65f),
                    )
                ),
                shape = shape,
            )
            .drawWithCache {
                val glowBrush = Brush.horizontalGradient(
                    colors = listOf(
                        accentColor.copy(alpha = topHighlightAlpha),
                        Color.Transparent,
                        accentColor.copy(alpha = topHighlightAlpha * 0.5f),
                    )
                )
                val tickLen = 10.dp.toPx()
                val strokePx = 2.dp.toPx()
                val tickColor = accentColor.copy(alpha = if (highlighted) 0.85f else 0.45f)
                onDrawWithContent {
                    drawRect(brush = glowBrush, size = Size(size.width, 36.dp.toPx()))
                    drawContent()
                    // Marcas de calibração técnica nos cantos retos
                    drawLine(
                        color = tickColor,
                        start = Offset(0f, 0f),
                        end = Offset(tickLen, 0f),
                        strokeWidth = strokePx,
                    )
                    drawLine(
                        color = tickColor,
                        start = Offset(0f, 0f),
                        end = Offset(0f, tickLen),
                        strokeWidth = strokePx,
                    )
                    drawLine(
                        color = tickColor,
                        start = Offset(size.width - tickLen, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = strokePx,
                    )
                    drawLine(
                        color = tickColor,
                        start = Offset(size.width, size.height - tickLen),
                        end = Offset(size.width, size.height),
                        strokeWidth = strokePx,
                    )
                }
            }
            .padding(contentPadding),
        content = content,
    )
}

/**
 * Cabeçalho técnico de seção com marcador luminoso, título em caixa alta e metadado opcional.
 */
@Composable
fun HudSectionHeader(
    code: String,
    title: String,
    subtitle: String? = null,
    accentColor: Color = FusaoPalette.CryoCyan,
    trailing: (@Composable () -> Unit)? = null,
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
                    .width(3.dp)
                    .height(22.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(accentColor, FusaoPalette.PlasmaViolet)
                        ),
                        shape = RoundedCornerShape(2.dp),
                    )
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = code.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = FusaoPalette.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = FusaoPalette.TextSecondary,
                    )
                }
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
    }
}

/**
 * Pill / Etiqueta de telemetria compacta.
 */
@Composable
fun TelemetryPill(
    text: String,
    color: Color = FusaoPalette.CryoCyan,
    filled: Boolean = false,
    pulsingDot: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (filled) color.copy(alpha = 0.18f) else FusaoPalette.CryoSlate.copy(alpha = 0.85f))
            .border(
                width = 1.dp,
                color = color.copy(alpha = if (filled) 0.55f else 0.32f),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (pulsingDot) {
            StatusPulseDot(color = color, active = true, size = 6.dp)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (filled) color else FusaoPalette.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Indicador LED com halo luminoso sutil para estados vivos/ativos.
 */
@Composable
fun StatusPulseDot(
    color: Color,
    active: Boolean,
    size: Dp = 8.dp,
) {
    Box(
        modifier = Modifier.size(size * 1.9f),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color.copy(alpha = 0.25f), CircleShape)
            )
        }
        Box(
            modifier = Modifier
                .size(size)
                .background(
                    color = if (active) color else FusaoPalette.TextMuted.copy(alpha = 0.45f),
                    shape = CircleShape,
                )
        )
    }
}

/**
 * Switch personalizado de inspiração hardware/tática para os controles do FUSAO.
 */
@Composable
fun CyberSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    isProcessing: Boolean = false,
    accentColor: Color = FusaoPalette.CryoCyan,
) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 24.dp else 2.dp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "cyberSwitchOffset",
    )
    val trackColor by animateColorAsState(
        targetValue = when {
            !enabled -> FusaoPalette.AbyssNavy.copy(alpha = 0.6f)
            checked -> accentColor.copy(alpha = 0.22f)
            else -> FusaoPalette.AbyssNavy
        },
        animationSpec = tween(durationMillis = 220),
        label = "cyberSwitchTrack",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> FusaoPalette.BorderSubtle.copy(alpha = 0.3f)
            checked -> accentColor.copy(alpha = 0.85f)
            else -> FusaoPalette.BorderSubtle.copy(alpha = 0.7f)
        },
        animationSpec = tween(durationMillis = 220),
        label = "cyberSwitchBorder",
    )
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .width(52.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(trackColor)
            .border(1.2.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && !isProcessing,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Marca textual ON / OFF sutil no trilho
        Text(
            text = if (checked) "ON" else "OFF",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
            color = if (checked) accentColor.copy(alpha = 0.85f) else FusaoPalette.TextMuted.copy(alpha = 0.65f),
            modifier = Modifier
                .align(if (checked) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 6.dp),
        )
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(24.dp, 22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    when {
                        !enabled -> FusaoPalette.TextMuted.copy(alpha = 0.35f)
                        checked -> accentColor
                        else -> FusaoPalette.TextSecondary.copy(alpha = 0.65f)
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.8.dp,
                    color = if (checked) FusaoPalette.VoidBlack else accentColor,
                )
            } else {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(10.dp)
                        .background(
                            color = if (checked) FusaoPalette.VoidBlack.copy(alpha = 0.75f)
                            else FusaoPalette.VoidBlack.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(1.5.dp),
                        )
                )
            }
        }
    }
}

/**
 * Botão de comando tático chanfrado com suporte a estado de carregamento e variante sólida/contorno.
 */
@Composable
fun TacticalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    isLoading: Boolean = false,
    accentColor: Color = FusaoPalette.CryoCyan,
    subtitle: String? = null,
) {
    val density = LocalDensity.current
    val shape = remember(density) {
        with(density) {
            chamferedHudShape(
                topStartPx = 3.dp.toPx(),
                topEndPx = 10.dp.toPx(),
                bottomEndPx = 3.dp.toPx(),
                bottomStartPx = 10.dp.toPx(),
            )
        }
    }
    val bgBrush = when {
        !enabled -> Brush.horizontalGradient(
            listOf(
                FusaoPalette.CryoSlate.copy(alpha = 0.4f),
                FusaoPalette.ObsidianSurface.copy(alpha = 0.4f),
            )
        )
        filled -> Brush.horizontalGradient(
            listOf(
                accentColor.copy(alpha = 0.28f),
                FusaoPalette.PlasmaViolet.copy(alpha = 0.22f),
            )
        )
        else -> Brush.horizontalGradient(
            listOf(
                FusaoPalette.CryoSlate.copy(alpha = 0.85f),
                FusaoPalette.ObsidianSurface.copy(alpha = 0.9f),
            )
        )
    }
    val borderColor = when {
        !enabled -> FusaoPalette.BorderSubtle.copy(alpha = 0.3f)
        filled -> accentColor.copy(alpha = 0.85f)
        else -> accentColor.copy(alpha = 0.42f)
    }
    val contentColor = when {
        !enabled -> FusaoPalette.TextMuted
        filled -> accentColor
        else -> FusaoPalette.TextPrimary
    }

    Surface(
        modifier = modifier
            .clip(shape)
            .clickable(enabled = enabled && !isLoading, onClick = onClick),
        color = Color.Transparent,
        shape = shape,
        border = BorderStroke(if (filled) 1.3.dp else 1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .background(bgBrush)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = accentColor,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = FusaoPalette.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Núcleo visual do Reator FUSAO-M54 para a tela principal de Status.
 * Combina os assets gerados (`bg_status_reactor` e `img_fusao_emblem`) com anéis
 * de telemetria desenhados nativamente em Canvas.
 */
@Composable
fun ReactorCoreEmblem(
    profile: String,
    inGame: Boolean,
    daemonsAlive: Int,
    modifier: Modifier = Modifier,
) {
    val accent = FusaoPalette.profileAccent(profile)
    val secondary = FusaoPalette.profileSecondary(profile)
    val progressRatio by animateFloatAsState(
        targetValue = (daemonsAlive.coerceIn(0, 3) / 3f),
        animationSpec = tween(durationMillis = 600),
        label = "daemonArc",
    )

    val transition = rememberInfiniteTransition(label = "reactorPulse")
    val slowAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (inGame) 14000 else 28000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "reactorRotation",
    )

    Box(
        modifier = modifier.size(104.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeOuter = 2.5.dp.toPx()
            val strokeInner = 4.dp.toPx()
            val pad = 6.dp.toPx()
            val arcSize = Size(size.width - pad * 2, size.height - pad * 2)

            // Halo radial de energia
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = if (inGame) 0.30f else 0.16f),
                        secondary.copy(alpha = 0.08f),
                        Color.Transparent,
                    )
                ),
                radius = size.minDimension * 0.5f,
            )

            // Anel base segmentado
            drawArc(
                color = FusaoPalette.GridHairline,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(pad, pad),
                size = arcSize,
                style = Stroke(width = strokeOuter),
            )

            // Arco de liveness dos 3 daemons (fusaod, watch, observer)
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(accent, secondary, accent)
                ),
                startAngle = -90f,
                sweepAngle = 360f * progressRatio.coerceAtLeast(0.08f),
                useCenter = false,
                topLeft = Offset(pad, pad),
                size = arcSize,
                style = Stroke(width = strokeInner, cap = StrokeCap.Round),
            )

            // Retícula orbital giratória leve
            rotate(degrees = slowAngle) {
                val r = size.minDimension * 0.47f
                val cx = center.x
                val cy = center.y
                for (i in 0 until 6) {
                    val rad = Math.toRadians((i * 60).toDouble())
                    val x1 = cx + (r - 4.dp.toPx()) * cos(rad).toFloat()
                    val y1 = cy + (r - 4.dp.toPx()) * sin(rad).toFloat()
                    val x2 = cx + (r + 2.dp.toPx()) * cos(rad).toFloat()
                    val y2 = cy + (r + 2.dp.toPx()) * sin(rad).toFloat()
                    drawLine(
                        color = accent.copy(alpha = 0.7f),
                        start = Offset(x1, y1),
                        end = Offset(x2, y2),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }

        Image(
            painter = painterResource(id = R.drawable.img_fusao_emblem),
            contentDescription = "Emblema FUSAO-M54",
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * Anel de contagem regressiva de 20 segundos para a operação `fusao preload record 20`.
 */
@Composable
fun PreloadCountdownGauge(
    remainingSec: Int,
    totalSec: Int = 20,
    modifier: Modifier = Modifier,
) {
    val progress = (remainingSec.coerceIn(0, totalSec).toFloat() / totalSec.toFloat())
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 400),
        label = "recordCountdown",
    )

    Box(
        modifier = modifier.size(72.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2f + 2.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(
                color = FusaoPalette.GridHairline,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(FusaoPalette.IgnisAmber, FusaoPalette.CryoCyan, FusaoPalette.PlasmaViolet)
                ),
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${remainingSec}s",
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                color = FusaoPalette.IgnisAmber,
            )
            Text(
                text = "REC",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                color = FusaoPalette.CryoCyan,
            )
        }
    }
}

/**
 * Renderiza o ícone real do aplicativo via PackageManager (sem permissões extras)
 * ou um brasão hexagonal tático com monograma quando o pacote não está instalado.
 */
@Composable
fun AppIconBadge(
    pkg: String,
    label: String,
    size: Dp = 44.dp,
    accentColor: Color = FusaoPalette.CryoCyan,
) {
    val ctx = LocalContext.current
    var iconBitmap by remember(pkg) { mutableStateOf<ImageBitmap?>(AppIconCache.peek(pkg)) }

    LaunchedEffect(pkg) {
        if (iconBitmap == null && pkg.isNotEmpty()) {
            iconBitmap = AppIconCache.getOrLoad(ctx, pkg)
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(11.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        FusaoPalette.ElevatedHud,
                        FusaoPalette.AbyssNavy,
                    )
                )
            )
            .border(
                width = 1.dp,
                color = accentColor.copy(alpha = 0.38f),
                shape = RoundedCornerShape(11.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = iconBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = label,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            val initials = remember(label, pkg) {
                val clean = label.substringAfterLast('.').trim()
                clean.take(2).uppercase().ifEmpty { "GM" }
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = this.size.minDimension * 0.36f
                val path = Path()
                for (i in 0 until 6) {
                    val rad = Math.toRadians((i * 60 - 30).toDouble())
                    val x = center.x + r * cos(rad).toFloat()
                    val y = center.y + r * sin(rad).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(
                    path = path,
                    color = accentColor.copy(alpha = 0.16f),
                )
                drawPath(
                    path = path,
                    color = accentColor.copy(alpha = 0.55f),
                    style = Stroke(width = 1.4.dp.toPx()),
                )
            }
            Text(
                text = initials,
                style = MaterialTheme.typography.labelMedium,
                color = accentColor,
            )
        }
    }
}

/**
 * Ícones vetoriais desenhados nativamente em Canvas para as 5 áreas de navegação
 * e subsistemas técnicos (zero dependência de material-icons-extended pesado).
 */
enum class FusaoNavIcon { STATUS, FLAGS, PRELOAD, GAMES, LOG }

@Composable
fun FusaoVectorGlyph(
    icon: FusaoNavIcon,
    color: Color,
    modifier: Modifier = Modifier.size(20.dp),
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        val w = size.width
        val h = size.height
        when (icon) {
            FusaoNavIcon.STATUS -> {
                // Hexágono de núcleo + pulso central
                val path = Path().apply {
                    moveTo(w * 0.5f, h * 0.08f)
                    lineTo(w * 0.9f, h * 0.29f)
                    lineTo(w * 0.9f, h * 0.71f)
                    lineTo(w * 0.5f, h * 0.92f)
                    lineTo(w * 0.1f, h * 0.71f)
                    lineTo(w * 0.1f, h * 0.29f)
                    close()
                }
                drawPath(path, color = color, style = stroke)
                drawCircle(color = color, radius = w * 0.16f, center = center)
            }
            FusaoNavIcon.FLAGS -> {
                // Três trilhos de matriz com chaves seletoras
                val ys = listOf(h * 0.24f, h * 0.50f, h * 0.76f)
                val knobs = listOf(w * 0.72f, w * 0.32f, w * 0.66f)
                ys.forEachIndexed { idx, y ->
                    drawLine(
                        color = color.copy(alpha = 0.55f),
                        start = Offset(w * 0.12f, y),
                        end = Offset(w * 0.88f, y),
                        strokeWidth = 1.8.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawCircle(
                        color = color,
                        radius = w * 0.11f,
                        center = Offset(knobs[idx], y),
                    )
                }
            }
            FusaoNavIcon.PRELOAD -> {
                // Bloco de memória RAM/WILLNEED com condutos de alta velocidade
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.22f, h * 0.22f),
                    size = Size(w * 0.56f, h * 0.56f),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                    style = stroke,
                )
                // Pinos superiores/inferiores/laterais
                for (f in listOf(0.36f, 0.64f)) {
                    drawLine(color, Offset(w * f, h * 0.06f), Offset(w * f, h * 0.22f), stroke.width)
                    drawLine(color, Offset(w * f, h * 0.78f), Offset(w * f, h * 0.94f), stroke.width)
                    drawLine(color, Offset(w * 0.06f, h * f), Offset(w * 0.22f, h * f), stroke.width)
                    drawLine(color, Offset(w * 0.78f, h * f), Offset(w * 0.94f, h * f), stroke.width)
                }
                drawRect(
                    color = color.copy(alpha = 0.7f),
                    topLeft = Offset(w * 0.38f, h * 0.38f),
                    size = Size(w * 0.24f, h * 0.24f),
                )
            }
            FusaoNavIcon.GAMES -> {
                // Mira / Retícula tática de alvo de jogo
                drawCircle(
                    color = color,
                    radius = w * 0.36f,
                    center = center,
                    style = stroke,
                )
                drawLine(color, Offset(w * 0.5f, h * 0.05f), Offset(w * 0.5f, h * 0.26f), stroke.width)
                drawLine(color, Offset(w * 0.5f, h * 0.74f), Offset(w * 0.5f, h * 0.95f), stroke.width)
                drawLine(color, Offset(w * 0.05f, h * 0.5f), Offset(w * 0.26f, h * 0.5f), stroke.width)
                drawLine(color, Offset(w * 0.74f, h * 0.5f), Offset(w * 0.95f, h * 0.5f), stroke.width)
                drawCircle(color = color, radius = w * 0.08f, center = center)
            }
            FusaoNavIcon.LOG -> {
                // Terminal / Prompt de telemetria
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.1f, h * 0.14f),
                    size = Size(w * 0.8f, h * 0.72f),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    style = stroke,
                )
                val chevron = Path().apply {
                    moveTo(w * 0.26f, h * 0.36f)
                    lineTo(w * 0.42f, h * 0.50f)
                    lineTo(w * 0.26f, h * 0.64f)
                }
                drawPath(chevron, color = color, style = stroke)
                drawLine(
                    color = color,
                    start = Offset(w * 0.48f, h * 0.64f),
                    end = Offset(w * 0.72f, h * 0.64f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/**
 * Formata uma linha de log ou gancho com realce semântico de sintaxe
 * sem alterar nem suprimir nenhum caractere do conteúdo original.
 */
fun highlightTelemetryLine(raw: String): AnnotatedString = buildAnnotatedString {
    val line = raw
    val lower = line.lowercase()
    val baseColor = when {
        line.contains("FALHOU") || line.contains("verify-fail") || lower.contains("erro") || lower.contains("error") ->
            FusaoPalette.CrimsonSoft
        line.contains("CONTENDA") || line.contains("recuado") || line.contains("paused") ->
            FusaoPalette.IgnisAmber
        line.contains(" ok") || line.contains(": enter") || line.contains(": on") || line.contains("apply game") ->
            FusaoPalette.MintPulse
        line.startsWith("---") || line.startsWith("#") ->
            FusaoPalette.CryoCyan
        else -> FusaoPalette.TextCode
    }

    // Destaca timestamp inicial caso exista (ex.: "2026-10-13 13:01:01 ")
    val tsRegex = Regex("^([0-9]{4}-[0-9]{2}-[0-9]{2}\\s+[0-9]{2}:[0-9]{2}:[0-9]{2}|[0-9]{2}:[0-9]{2}:[0-9]{2})\\s+")
    val tsMatch = tsRegex.find(line)
    var cursor = 0
    if (tsMatch != null) {
        withStyle(SpanStyle(color = FusaoPalette.TextMuted)) {
            append(line.substring(0, tsMatch.range.last + 1))
        }
        cursor = tsMatch.range.last + 1
    }

    val rest = line.substring(cursor)
    val colonIdx = rest.indexOf(':')
    if (colonIdx in 1..22 && !rest.startsWith("---")) {
        withStyle(SpanStyle(color = FusaoPalette.CryoCyan, fontWeight = FontWeight.Bold)) {
            append(rest.substring(0, colonIdx + 1))
        }
        withStyle(SpanStyle(color = baseColor)) {
            append(rest.substring(colonIdx + 1))
        }
    } else {
        withStyle(SpanStyle(color = baseColor)) {
            append(rest)
        }
    }
}
