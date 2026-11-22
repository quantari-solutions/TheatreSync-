@file:OptIn(ExperimentalTextApi::class)

package com.theatresync.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants

/**
 * Quantari Solutions look (quantari-solutions.vercel.app):
 * near-black background, faint gold grid, gold gradient accents, Inter, pill buttons with a gold glow.
 */
object Q {
    val Background = Color(0xFF050508)
    val BackgroundAlt = Color(0xFF0A0A12)
    val Card = Color(0xFF0D0D16)
    val Border = Color(0xFF1A1608)
    val BorderAccent = Color(0xFF2A2410)
    val Primary = Color(0xFFF0C44C)
    val PrimaryLight = Color(0xFFFFE9A8)
    val PrimaryDark = Color(0xFFD4A017)
    val Secondary = Color(0xFFB8860B)
    val Text = Color(0xFFF5F6FA)
    val TextMuted = Color(0xFFC5CAD6)
    val TextFaint = Color(0xFF8A8FA0)
    val Success = Color(0xFF2ECF8A)
    val Error = Color(0xFFE05252)
    val Glow = Color(0xFFB89B00)

    /** linear-gradient(120deg, #f0c44c 0%, #ffe9a8 50%, #d4a017 100%) */
    val Gold = Brush.linearGradient(
        0f to Primary, 0.5f to PrimaryLight, 1f to PrimaryDark,
        start = Offset(0f, 0f), end = Offset(600f, 350f),
    )
}

private fun inter(weight: Int) = Font(
    R.font.inter_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700), inter(800))

private val base = Typography()
private val QTypography = Typography(
    displaySmall = base.displaySmall.copy(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp),
    headlineSmall = base.headlineSmall.copy(fontFamily = Inter, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = base.titleLarge.copy(fontFamily = Inter, fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontFamily = Inter),
    bodyMedium = base.bodyMedium.copy(fontFamily = Inter),
    bodySmall = base.bodySmall.copy(fontFamily = Inter),
    labelLarge = base.labelLarge.copy(fontFamily = Inter, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
    labelMedium = base.labelMedium.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
    labelSmall = base.labelSmall.copy(fontFamily = Inter),
)

@Composable
fun QuantariTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Q.Primary,
            onPrimary = Color.Black,
            secondary = Q.Secondary,
            background = Q.Background,
            onBackground = Q.Text,
            surface = Q.Card,
            onSurface = Q.Text,
            onSurfaceVariant = Q.TextMuted,
            outline = Q.BorderAccent,
            error = Q.Error,
        ),
        typography = QTypography,
        content = content,
    )
}

/** Background with the site's faint gold grid and a soft glow at the top. */
@Composable
fun GridBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Q.Background)) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 32.dp.toPx()
            val line = Color(0x12D4A017)
            var x = 0f
            while (x < size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
            var y = 0f
            while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), 1f); y += step }
            drawCircle(
                Brush.radialGradient(listOf(Color(0x33F0C44C), Color.Transparent), center = Offset(size.width * 0.5f, 0f), radius = size.width * 0.9f),
                radius = size.width * 0.9f, center = Offset(size.width * 0.5f, 0f),
            )
        }
        content()
    }
}

@Composable
fun GradientText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, style = style.copy(brush = Q.Gold))
}

/** Small uppercase gold label with the site's little gradient rules either side. */
@Composable
fun Eyebrow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(width = 20.dp, height = 1.dp).background(Brush.horizontalGradient(listOf(Q.Secondary, Q.Primary))))
        Text(text.uppercase(), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelMedium, color = Q.Primary)
        Box(Modifier.size(width = 20.dp, height = 1.dp).background(Brush.horizontalGradient(listOf(Q.Primary, Q.Secondary))))
    }
}

/** Dark glass card with a thin gold-brown border. */
@Composable
fun QCard(modifier: Modifier = Modifier, highlighted: Boolean = false, orbit: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val orbitModifier = if (orbit) Modifier.orbitGlow(18.dp) else Modifier
    Column(
        modifier
            .fillMaxWidth()
            .then(if (highlighted) Modifier.shadow(18.dp, shape, ambientColor = Q.Glow, spotColor = Q.Glow) else Modifier)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Q.Card, Q.BackgroundAlt)))
            .border(BorderStroke(1.dp, if (highlighted) Q.Secondary.copy(alpha = 0.6f) else Q.BorderAccent), shape)
            .then(orbitModifier)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/**
 * "Now playing" light: a soft gold glint that travels slowly around a card's edge while something
 * is playing, matching the gold glows elsewhere. One lap every 5 s; drawn on the border only.
 */
@Composable
fun Modifier.orbitGlow(corner: androidx.compose.ui.unit.Dp): Modifier {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "orbit")
    val angle by transition.animateFloat(
        0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(5_000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "orbit",
    )
    return this.drawWithContent {
        drawContent()
        val stroke = 2.dp.toPx()
        val r = corner.toPx()
        val outer = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, androidx.compose.ui.geometry.CornerRadius(r)))
        }
        val inner = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(stroke, stroke, size.width - stroke, size.height - stroke, androidx.compose.ui.geometry.CornerRadius((r - stroke).coerceAtLeast(0f))))
        }
        val ring = androidx.compose.ui.graphics.Path().apply { op(outer, inner, androidx.compose.ui.graphics.PathOperation.Difference) }
        val glint = Brush.sweepGradient(
            0f to Color.Transparent, 0.70f to Color.Transparent,
            0.86f to Q.Primary.copy(alpha = 0.55f), 0.95f to Color(0xFFFFF0C2), 1f to Color.Transparent,
            center = center,
        )
        clipPath(ring) {
            rotate(angle, pivot = center) { drawCircle(glint, radius = size.maxDimension, center = center) }
        }
    }
}

/** Gold gradient pill button with glow (".btn-gradient" on the site). */
@Composable
fun GoldButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 56.dp,
    shape: Shape = RoundedCornerShape(50),
    leading: String? = null,
) {
    val view = LocalView.current
    Box(
        modifier
            .sizeIn(minHeight = maxOf(height, 48.dp), minWidth = 48.dp)
            .then(if (enabled) Modifier.shadow(14.dp, shape, ambientColor = Q.Glow, spotColor = Q.Glow) else Modifier)
            .clip(shape)
            .background(if (enabled) Q.Gold else Brush.linearGradient(listOf(Color(0xFF1A1A1A), Color(0xFF1A1A18))))
            .clickable(enabled = enabled, role = Role.Button) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            .padding(PaddingValues(horizontal = 22.dp, vertical = 12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            listOfNotNull(leading, text).joinToString("  "),
            color = if (enabled) Color.Black else Q.TextFaint,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp),
            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Outlined pill button. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 48.dp, description: String? = null) {
    val shape = RoundedCornerShape(50)
    val view = LocalView.current
    Box(
        modifier
            // Accessibility: never smaller than the 48 dp minimum touch target.
            .sizeIn(minHeight = maxOf(height, 48.dp), minWidth = 48.dp)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .clip(shape)
            .background(Q.Card.copy(alpha = 0.6f))
            .border(BorderStroke(1.dp, Q.BorderAccent), shape)
            .clickable(role = Role.Button) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        // One line, "…" if it can't fit: a button label must never wrap letter by letter.
        Text(text, color = Q.PrimaryLight, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** Numbered gold badge; shows a tick when done. */
@Composable
fun StepBadge(number: Int, done: Boolean) {
    Box(
        Modifier.size(34.dp).clip(CircleShape)
            .background(if (done) Brush.linearGradient(listOf(Q.Success, Q.Success)) else Q.Gold),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (done) "✓" else number.toString(), color = Color.Black, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun StepHeader(number: Int, title: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.clearAndSetSemantics { }) { StepBadge(number, done) }
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = Q.Text)
    }
}

/** A small rounded status pill, e.g. "● In sync". */
@Composable
fun StatusPill(text: String, color: Color, pulsing: Boolean = false) {
    val alpha = if (pulsing) {
        val t = rememberInfiniteTransition(label = "pulse")
        val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
        a
    } else 1f
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.35f)), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).alpha(alpha).clip(CircleShape).background(color))
        Text(text, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Draggable gold progress bar, like YouTube's: drag anywhere backwards or forwards; the time
 * label follows the finger and the TV jumps there when you let go.
 */
@Composable
fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var dragging by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    // After a drag the bar stays where you let go ("Loading…") until the TV gets there, instead of jumping
    // back to the old spot while the TV buffers (owner). Gives up after 15 s.
    var pending by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Long?>(null) }
    var pendingAt by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val target = pending
    if (target != null && (kotlin.math.abs(positionMs - target) < 3_000 || android.os.SystemClock.elapsedRealtime() - pendingAt > 15_000)) pending = null
    androidx.compose.runtime.LaunchedEffect(target) { if (target != null) { kotlinx.coroutines.delay(15_500); pending = null } }
    val shownMs = pending ?: positionMs
    val fraction = dragging ?: if (durationMs > 0) (shownMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    androidx.compose.material3.Slider(
        value = fraction,
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { f ->
                if (durationMs > 0) {
                    val ms = (f * durationMs).toLong()
                    pending = ms; pendingAt = android.os.SystemClock.elapsedRealtime()
                    onSeek(ms)
                }
            }
            dragging = null
        },
        enabled = durationMs > 0,
        modifier = modifier.fillMaxWidth().height(28.dp),
        colors = androidx.compose.material3.SliderDefaults.colors(
            thumbColor = Q.Primary, activeTrackColor = Q.Primary, inactiveTrackColor = Q.Border,
            disabledThumbColor = Q.Border, disabledActiveTrackColor = Q.Border, disabledInactiveTrackColor = Q.Border,
        ),
    )
    androidx.compose.foundation.layout.Row {
        Text(formatClock(if (dragging != null) (fraction * durationMs).toLong() else shownMs), color = Q.TextMuted, style = MaterialTheme.typography.labelLarge)
        if (pending != null && dragging == null) Text("  ·  Loading…", color = Q.Primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("seek_loading"))
        Spacer1()
        Text(if (durationMs > 0) formatClock(durationMs) else "--:--", color = Q.TextFaint, style = MaterialTheme.typography.labelLarge)
    }
}

/** 1:05:09 or 4:09. */
fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Thin gold progress bar. */
@Composable
fun GoldProgress(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().size(height = 6.dp, width = 0.dp).clip(RoundedCornerShape(50)).background(Q.Border)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxSize().clip(RoundedCornerShape(50)).background(Q.Gold))
    }
}

@Composable
fun RowScope.Spacer1() = Box(Modifier.weight(1f))

@Composable
fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = Q.TextFaint)

@Composable
fun Body(text: String, color: Color = Q.TextMuted) = Text(text, style = MaterialTheme.typography.bodyLarge, color = color)

@Composable
fun Section(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
