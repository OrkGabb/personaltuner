package com.orkgabb.fusao.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orkgabb.fusao.AppIconCache
import com.orkgabb.fusao.R
import kotlin.math.cos
import kotlin.math.sin

private val TELEMETRY_TS_REGEX = Regex(
    "^([0-9]{4}-[0-9]{2}-[0-9]{2}\\s+[0-9]{2}:[0-9]{2}:[0-9]{2}|[0-9]{2}:[0-9]{2}:[0-9]{2})\\s+"
)

/**
 * Paleta oficial FUSAO Obsidian // Super AMOLED M54.
 * Superfícies profundas com hierarquia tonal limpa, bordas sutis e acentos reservados ao estado ativo.
 */
object FusaoPalette {
    val VoidBlack = Color(0xFF05070B)
    val AbyssNavy = Color(0xFF090D16)
    val ObsidianSurface = Color(0xFF0E1420)
    val CryoSlate = Color(0xFF141C2B)
    val ElevatedHud = Color(0xFF1B263B)
    val GlassOverlay = Color(0xDD0C121E)
    val GridHairline = Color(0xFF1E293D)
    val BorderSubtle = Color(0xFF253249)

    val CryoCyan = Color(0xFF22D3EE)
    val CryoTurquoise = Color(0xFF06B6D4)
    val CryoCyanDim = Color(0xFF0E7490)

    val PlasmaViolet = Color(0xFF8B5CF6)
    val ElectricOrchid = Color(0xFFA855F7)
    val PlasmaDeep = Color(0xFF4C1D95)

    val IgnisAmber = Color(0xFFF59E0B)
    val SolarGold = Color(0xFFFBBF24)

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
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        letterSpacing = 0.2.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.2.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 18.5.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.5.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.5.sp,
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
 * Fundo AMOLED limpo com iluminação atmosférica orgânica de baixa intensidade.
 * Sem grades ruidosas para preservar contraste e clareza visual.
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
    val animSecondary by animateColorAsState(
        targetValue = secondaryColor,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "ambientSecondary",
    )
    val breathTransition = rememberInfiniteTransition(label = "ambientBreath")
    val glowAlpha by breathTransition.animateFloat(
        initialValue = 0.09f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowAlpha",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FusaoPalette.VoidBlack)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            animAccent.copy(alpha = glowAlpha),
                            animSecondary.copy(alpha = glowAlpha * 0.35f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.78f, size.height * 0.06f),
                        radius = size.width * 0.9f,
                    )
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            animSecondary.copy(alpha = 0.06f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.18f, size.height * 0.90f),
                        radius = size.width * 0.7f,
                    )
                )
            },
        content = content,
    )
}

/**
 * Painel de superfície moderno com cantos arredondados suaves, animação de tamanho ao expandir
 * detalhes internos e realce luminoso discreto quando destacado.
 */
@Composable
fun TacticalPanel(
    modifier: Modifier = Modifier,
    accentColor: Color = FusaoPalette.CryoCyan,
    containerColor: Color = FusaoPalette.ObsidianSurface,
    elevated: Boolean = false,
    highlighted: Boolean = false,
    @Suppress("UNUSED_PARAMETER") chamferTopStart: Dp = 0.dp,
    @Suppress("UNUSED_PARAMETER") chamferTopEnd: Dp = 0.dp,
    @Suppress("UNUSED_PARAMETER") chamferBottomEnd: Dp = 0.dp,
    @Suppress("UNUSED_PARAMETER") chamferBottomStart: Dp = 0.dp,
    cornerRadius: Dp = 18.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val borderColor by animateColorAsState(
        targetValue = when {
            highlighted -> accentColor.copy(alpha = 0.42f)
            elevated -> FusaoPalette.BorderSubtle.copy(alpha = 0.75f)
            else -> FusaoPalette.GridHairline.copy(alpha = 0.95f)
        },
        animationSpec = tween(durationMillis = 260),
        label = "panelBorder",
    )
    val bgTop by animateColorAsState(
        targetValue = if (highlighted) {
            Color(
                red = (containerColor.red * 0.90f + accentColor.red * 0.10f).coerceIn(0f, 1f),
                green = (containerColor.green * 0.90f + accentColor.green * 0.10f).coerceIn(0f, 1f),
                blue = (containerColor.blue * 0.90f + accentColor.blue * 0.10f).coerceIn(0f, 1f),
                alpha = 0.96f,
            )
        } else {
            containerColor.copy(alpha = 0.94f)
        },
        animationSpec = tween(durationMillis = 280),
        label = "panelBgTop",
    )

    Box(
        modifier = modifier
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
            )
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        bgTop,
                        FusaoPalette.AbyssNavy.copy(alpha = 0.97f),
                    )
                )
            )
            .border(
                width = if (highlighted) 1.15.dp else 1.dp,
                color = borderColor,
                shape = shape,
            )
            .padding(contentPadding),
        content = content,
    )
}

/**
 * Cabeçalho limpo de seção com pílula de acento sutil, título e metadado opcional.
 */
@Composable
fun HudSectionHeader(
    code: String = "",
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
                    .height(18.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (code.isNotEmpty()) {
                        Text(
                            text = code.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
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
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
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
 * Rótulo compacto de categoria/seção reutilizado entre todas as abas.
 */
@Composable
fun FusaoSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = FusaoPalette.TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * Divisor interno discreto para listas agrupadas dentro de um único painel.
 */
@Composable
fun FusaoHairlineDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        thickness = 1.dp,
        color = FusaoPalette.GridHairline.copy(alpha = 0.72f),
        modifier = modifier,
    )
}

/**
 * Pílula/Badge de status com forma encapsulada e transições suaves de cor.
 */
@Composable
fun TelemetryPill(
    text: String,
    color: Color = FusaoPalette.CryoCyan,
    filled: Boolean = false,
    pulsingDot: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(
        targetValue = if (filled) color.copy(alpha = 0.16f) else FusaoPalette.CryoSlate.copy(alpha = 0.75f),
        animationSpec = tween(durationMillis = 220),
        label = "pillBg",
    )
    val border by animateColorAsState(
        targetValue = color.copy(alpha = if (filled) 0.48f else 0.25f),
        animationSpec = tween(durationMillis = 220),
        label = "pillBorder",
    )
    val fg by animateColorAsState(
        targetValue = if (filled) color else FusaoPalette.TextSecondary,
        animationSpec = tween(durationMillis = 220),
        label = "pillFg",
    )

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .border(width = 1.dp, color = border, shape = CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (pulsingDot) {
            StatusPulseDot(color = color, active = true, size = 6.dp)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * LED indicador com onda de pulso contínua suave enquanto ativo.
 */
@Composable
fun StatusPulseDot(
    color: Color,
    active: Boolean,
    size: Dp = 8.dp,
) {
    val pulseTransition = rememberInfiniteTransition(label = "dotPulse")
    val ringScale by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 2.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dotRingScale",
    )
    val ringAlpha by pulseTransition.animateFloat(
        initialValue = 0.50f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dotRingAlpha",
    )

    Box(
        modifier = Modifier.size(size * 2.1f),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Box(
                modifier = Modifier
                    .size(size)
                    .graphicsLayer {
                        scaleX = ringScale
                        scaleY = ringScale
                        alpha = ringAlpha
                    }
                    .background(color, CircleShape)
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
 * Espectro animado compacto (4 barras) para indicar DSP/áudio ativo em tempo real.
 */
@Composable
fun LiveSpectrumBars(
    active: Boolean,
    color: Color = FusaoPalette.BioEmerald,
    modifier: Modifier = Modifier,
) {
    val animColor by animateColorAsState(
        targetValue = if (active) color else FusaoPalette.TextMuted.copy(alpha = 0.45f),
        animationSpec = tween(220),
        label = "specColor",
    )
    val t = rememberInfiniteTransition(label = "spectrum")
    val b1 by t.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bar1",
    )
    val b2 by t.animateFloat(
        initialValue = 0.90f,
        targetValue = 0.30f,
        animationSpec = infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bar2",
    )
    val b3 by t.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.00f,
        animationSpec = infiniteRepeatable(tween(370, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bar3",
    )
    val b4 by t.animateFloat(
        initialValue = 0.75f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(480, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bar4",
    )

    val levels = if (active) listOf(b1, b2, b3, b4) else listOf(0.32f, 0.56f, 0.40f, 0.28f)
    Row(
        modifier = modifier.height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        levels.forEach { ratio ->
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(14.dp * ratio)
                    .clip(CircleShape)
                    .background(animColor)
            )
        }
    }
}

/**
 * Switch tátil com física de mola (spring), cápsula moderna e escala ao pressionar.
 */
@Composable
fun CyberSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    isProcessing: Boolean = false,
    accentColor: Color = FusaoPalette.CryoCyan,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled && !isProcessing) 0.93f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 550f),
        label = "switchScale",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 520f),
        label = "cyberSwitchOffset",
    )
    val trackColor by animateColorAsState(
        targetValue = when {
            !enabled -> FusaoPalette.AbyssNavy.copy(alpha = 0.55f)
            checked -> accentColor.copy(alpha = 0.24f)
            else -> FusaoPalette.CryoSlate
        },
        animationSpec = tween(durationMillis = 200),
        label = "cyberSwitchTrack",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> FusaoPalette.BorderSubtle.copy(alpha = 0.25f)
            checked -> accentColor.copy(alpha = 0.85f)
            else -> FusaoPalette.BorderSubtle.copy(alpha = 0.7f)
        },
        animationSpec = tween(durationMillis = 200),
        label = "cyberSwitchBorder",
    )
    val thumbColor by animateColorAsState(
        targetValue = when {
            !enabled -> FusaoPalette.TextMuted.copy(alpha = 0.35f)
            checked -> accentColor
            else -> FusaoPalette.TextSecondary.copy(alpha = 0.85f)
        },
        animationSpec = tween(durationMillis = 200),
        label = "cyberSwitchThumb",
    )

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .width(48.dp)
            .height(26.dp)
            .clip(CircleShape)
            .background(trackColor)
            .border(1.2.dp, borderColor, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && !isProcessing,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(20.dp)
                .clip(CircleShape)
                .background(thumbColor),
            contentAlignment = Alignment.Center,
        ) {
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(11.dp),
                    strokeWidth = 1.8.dp,
                    color = if (checked) FusaoPalette.VoidBlack else accentColor,
                )
            }
        }
    }
}

/**
 * Linha unificada de switch para painéis limpos sem caixas duplicadas.
 */
@Composable
fun FusaoSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    isProcessing: Boolean = false,
    accentColor: Color = FusaoPalette.CryoCyan,
    trailingBadge: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled && !isProcessing,
                onClick = { onCheckedChange(!checked) },
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) FusaoPalette.TextPrimary else FusaoPalette.TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (trailingBadge != null) {
                    trailingBadge()
                }
            }
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = FusaoPalette.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        CyberSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            isProcessing = isProcessing,
            accentColor = accentColor,
        )
    }
}

/**
 * Botão moderno com cantos arredondados suaves, resposta tátil ao pressionar e animações de estado.
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
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && !isLoading) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 560f),
        label = "btnScale",
    )
    val shape = remember { RoundedCornerShape(12.dp) }

    val bgColor by animateColorAsState(
        targetValue = when {
            !enabled -> FusaoPalette.CryoSlate.copy(alpha = 0.35f)
            filled -> accentColor.copy(alpha = 0.22f)
            else -> FusaoPalette.CryoSlate.copy(alpha = 0.82f)
        },
        animationSpec = tween(200),
        label = "btnBg",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> FusaoPalette.BorderSubtle.copy(alpha = 0.22f)
            filled -> accentColor.copy(alpha = 0.75f)
            else -> FusaoPalette.BorderSubtle.copy(alpha = 0.7f)
        },
        animationSpec = tween(200),
        label = "btnBorder",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> FusaoPalette.TextMuted
            filled -> accentColor
            else -> FusaoPalette.TextPrimary
        },
        animationSpec = tween(200),
        label = "btnFg",
    )

    Surface(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && !isLoading,
                onClick = onClick,
            ),
        color = bgColor,
        shape = shape,
        border = BorderStroke(if (filled) 1.2.dp else 1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.8.dp,
                    color = accentColor,
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (filled) accentColor.copy(alpha = 0.85f) else FusaoPalette.TextSecondary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

data class SegmentedItem<T>(
    val value: T,
    val label: String,
    val subtitle: String? = null,
    val accent: Color? = null,
)

/**
 * Barra segmentada unificada em trilho único para substituir múltiplos botões soltos e bordas repetidas.
 */
@Composable
fun <T> FusaoSegmentedControl(
    items: List<SegmentedItem<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loadingValue: T? = null,
    defaultAccent: Color = FusaoPalette.CryoCyan,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(FusaoPalette.VoidBlack.copy(alpha = 0.65f))
            .border(1.dp, FusaoPalette.GridHairline, RoundedCornerShape(13.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val isSelected = item.value == selected
            val isLoading = loadingValue == item.value
            val accent = item.accent ?: defaultAccent
            val interactionSource = remember { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()

            val scale by animateFloatAsState(
                targetValue = if (pressed && enabled && !isLoading) 0.96f else 1f,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 560f),
                label = "segScale",
            )
            val bg by animateColorAsState(
                targetValue = when {
                    isSelected -> accent.copy(alpha = 0.20f)
                    else -> Color.Transparent
                },
                animationSpec = tween(180),
                label = "segBg",
            )
            val border by animateColorAsState(
                targetValue = if (isSelected) accent.copy(alpha = 0.65f) else Color.Transparent,
                animationSpec = tween(180),
                label = "segBorder",
            )
            val textColor by animateColorAsState(
                targetValue = when {
                    !enabled -> FusaoPalette.TextMuted
                    isSelected -> accent
                    else -> FusaoPalette.TextSecondary
                },
                animationSpec = tween(180),
                label = "segText",
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(RoundedCornerShape(9.dp))
                    .background(bg)
                    .border(1.dp, border, RoundedCornerShape(9.dp))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = enabled && !isLoading,
                        onClick = { onSelect(item.value) },
                    )
                    .padding(horizontal = 6.dp, vertical = if (item.subtitle != null) 7.dp else 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(11.dp),
                            strokeWidth = 1.7.dp,
                            color = accent,
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.5.sp),
                            color = textColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!item.subtitle.isNullOrEmpty()) {
                            Text(
                                text = item.subtitle,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = if (isSelected) accent.copy(alpha = 0.85f) else FusaoPalette.TextMuted,
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
}

/**
 * Linha expansível (accordion) com chevron animado para manter blocos secundários recolhidos por padrão.
 */
@Composable
fun FusaoCollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    accentColor: Color = FusaoPalette.CryoCyan,
    summaryTag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val chevronRot by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 460f),
        label = "chevronRot",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onToggle)
                .padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!summaryTag.isNullOrEmpty()) {
                    Text(
                        text = summaryTag,
                        style = MaterialTheme.typography.labelSmall,
                        color = FusaoPalette.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Canvas(
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = chevronRot }
            ) {
                val p = Path().apply {
                    moveTo(size.width * 0.22f, size.height * 0.36f)
                    lineTo(size.width * 0.50f, size.height * 0.66f)
                    lineTo(size.width * 0.78f, size.height * 0.36f)
                }
                drawPath(
                    path = p,
                    color = accentColor,
                    style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(180)) + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut(tween(140)) + shrinkVertically(spring(stiffness = Spring.StiffnessMedium)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content,
            )
        }
    }
}

/**
 * Núcleo vivo do Reator FUSAO-M54 para a tela principal de Status.
 * Animação orbital contínua suave e halo reativo ao estado em jogo.
 */
@Composable
fun ReactorCoreEmblem(
    profile: String,
    inGame: Boolean,
    daemonsAlive: Int,
    modifier: Modifier = Modifier,
) {
    val accent by animateColorAsState(
        targetValue = FusaoPalette.profileAccent(profile),
        animationSpec = tween(400),
        label = "reactorAccent",
    )
    val secondary by animateColorAsState(
        targetValue = FusaoPalette.profileSecondary(profile),
        animationSpec = tween(400),
        label = "reactorSecondary",
    )
    val progressRatio by animateFloatAsState(
        targetValue = (daemonsAlive.coerceIn(0, 3) / 3f),
        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
        label = "daemonArc",
    )

    val inf = rememberInfiniteTransition(label = "reactorOrbit")
    val orbitAngle by inf.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (inGame) 5500 else 11000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orbitAngle",
    )
    val haloBreath by inf.animateFloat(
        initialValue = if (inGame) 0.22f else 0.12f,
        targetValue = if (inGame) 0.38f else 0.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (inGame) 1600 else 3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "haloBreath",
    )

    Box(
        modifier = modifier
            .size(84.dp)
            .drawWithCache {
                val strokeOuter = 2.dp.toPx()
                val strokeInner = 3.5.dp.toPx()
                val pad = 5.dp.toPx()
                val arcSize = Size(size.width - pad * 2, size.height - pad * 2)
                val sweepBrush = Brush.sweepGradient(
                    colors = listOf(accent, secondary, accent.copy(alpha = 0.2f), accent)
                )
                onDrawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = haloBreath),
                                secondary.copy(alpha = haloBreath * 0.35f),
                                Color.Transparent,
                            )
                        ),
                        radius = size.minDimension * 0.52f,
                    )
                    drawArc(
                        color = FusaoPalette.GridHairline,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(pad, pad),
                        size = arcSize,
                        style = Stroke(width = strokeOuter),
                    )
                    rotate(degrees = orbitAngle) {
                        drawArc(
                            brush = sweepBrush,
                            startAngle = -90f,
                            sweepAngle = 360f * progressRatio.coerceAtLeast(0.18f),
                            useCenter = false,
                            topLeft = Offset(pad, pad),
                            size = arcSize,
                            style = Stroke(width = strokeInner, cap = StrokeCap.Round),
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_fusao_emblem),
            contentDescription = "Emblema FUSAO-M54",
            modifier = Modifier
                .size(62.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * Renderiza o ícone real do aplicativo via PackageManager ou brasão hexagonal tático.
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
            .clip(RoundedCornerShape(12.dp))
            .background(FusaoPalette.ElevatedHud.copy(alpha = 0.72f))
            .border(
                width = 1.dp,
                color = accentColor.copy(alpha = 0.30f),
                shape = RoundedCornerShape(12.dp),
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
                val r = this.size.minDimension * 0.35f
                val path = Path()
                for (i in 0 until 6) {
                    val rad = Math.toRadians((i * 60 - 30).toDouble())
                    val x = center.x + r * cos(rad).toFloat()
                    val y = center.y + r * sin(rad).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path = path, color = accentColor.copy(alpha = 0.14f))
                drawPath(
                    path = path,
                    color = accentColor.copy(alpha = 0.45f),
                    style = Stroke(width = 1.3.dp.toPx()),
                )
            }
            Text(
                text = initials,
                style = MaterialTheme.typography.labelMedium,
                color = accentColor,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

enum class FusaoNavIcon { STATUS, FLAGS, BLOCK, GAMES, PIXELS, MISC, LOG, REFRESH }

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
            FusaoNavIcon.BLOCK -> {
                val shield = Path().apply {
                    moveTo(w * 0.5f, h * 0.07f)
                    lineTo(w * 0.86f, h * 0.22f)
                    lineTo(w * 0.86f, h * 0.52f)
                    cubicTo(w * 0.86f, h * 0.74f, w * 0.68f, h * 0.88f, w * 0.5f, h * 0.94f)
                    cubicTo(w * 0.32f, h * 0.88f, w * 0.14f, h * 0.74f, w * 0.14f, h * 0.52f)
                    lineTo(w * 0.14f, h * 0.22f)
                    close()
                }
                drawPath(shield, color = color, style = stroke)
                drawLine(
                    color = color,
                    start = Offset(w * 0.34f, h * 0.36f),
                    end = Offset(w * 0.66f, h * 0.66f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }
            FusaoNavIcon.GAMES -> {
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
            FusaoNavIcon.PIXELS -> {
                val cell = w * 0.24f
                val gap = w * 0.07f
                val origin = (w - (3f * cell + 2f * gap)) / 2f
                for (row in 0 until 3) {
                    for (col in 0 until 3) {
                        drawRect(
                            color = if ((row + col) % 2 == 0) color else color.copy(alpha = 0.3f),
                            topLeft = Offset(origin + col * (cell + gap), origin + row * (cell + gap)),
                            size = Size(cell, cell),
                        )
                    }
                }
            }
            FusaoNavIcon.MISC -> {
                val xs = listOf(w * 0.20f, w * 0.40f, w * 0.60f, w * 0.80f)
                val tops = listOf(h * 0.44f, h * 0.16f, h * 0.30f, h * 0.52f)
                xs.forEachIndexed { idx, x ->
                    drawLine(
                        color = color,
                        start = Offset(x, tops[idx]),
                        end = Offset(x, h * 0.84f),
                        strokeWidth = 2.0.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
            FusaoNavIcon.LOG -> {
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
            FusaoNavIcon.REFRESH -> {
                val pad = w * 0.16f
                drawArc(
                    color = color,
                    startAngle = -50f,
                    sweepAngle = 275f,
                    useCenter = false,
                    topLeft = Offset(pad, pad),
                    size = Size(w - pad * 2f, h - pad * 2f),
                    style = stroke,
                )
                val arrow = Path().apply {
                    moveTo(w * 0.70f, h * 0.10f)
                    lineTo(w * 0.86f, h * 0.26f)
                    lineTo(w * 0.65f, h * 0.34f)
                }
                drawPath(arrow, color = color, style = stroke)
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
        line.contains("CONTENDA") || line.contains("recuado") || line.contains("paused") || line.contains("(warm)") ->
            FusaoPalette.IgnisAmber
        line.contains(" ok") || line.contains(": enter") || line.contains(": on") || line.contains("apply game") ->
            FusaoPalette.MintPulse
        line.startsWith("---") || line.startsWith("#") ->
            FusaoPalette.CryoCyan
        else -> FusaoPalette.TextCode
    }

    val tsMatch = TELEMETRY_TS_REGEX.find(line)
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
