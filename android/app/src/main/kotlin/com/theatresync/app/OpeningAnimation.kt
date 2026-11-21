package com.theatresync.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp

/**
 * The Quantari / TheatreSync opening, shown full screen when the app starts fresh. Tap to skip.
 *
 * Drawn by the app itself (not a video): text is rendered at the phone's own resolution and laid out
 * for a tall screen, so it is sharp on any phone. Same timing and look as the TV's Signature Intro
 * (docs/intro/card.html): logo with a gold sheen, then "PRESENTED WITH / TheatreSync / a Quantari
 * Solutions module", then a fade out. Calls [onDone] when it ends.
 *
 * [frameMs] freezes it at one moment (screenshot tests).
 */
@Composable
fun OpeningAnimation(frameMs: Int? = null, onDone: () -> Unit) {
    val clock = remember { Animatable((frameMs ?: 0).toFloat()) }
    LaunchedEffect(frameMs) {
        if (frameMs != null) return@LaunchedEffect
        clock.animateTo(TOTAL_MS.toFloat(), tween(TOTAL_MS, easing = LinearEasing))
        onDone()
    }
    val t = clock.value

    // Timeline (ms). Slow on purpose: the glow builds, blooms around the title, swells once, then fades.
    val glowIn = seg(t, 0, 1800, GENTLE)
    val mark = seg(t, 300, 1500, SOFT)
    val sheen = seg(t, 1400, 1900, GENTLE)
    val eyebrow = seg(t, 2300, 900, SOFT)
    val title = seg(t, 2700, 1200, SOFT)
    val bloom = seg(t, 2900, 1700, GENTLE)            // the title's own glow
    val line = seg(t, 3500, 1400, GENTLE)
    val tag = seg(t, 3900, 900, SOFT)
    val swell = bell(t, 4300, 1500)                   // one slow swell of the whole glow
    val out = 1f - seg(t, 5700, 900, GENTLE)
    val glow = glowIn * (1f + 0.6f * swell)

    BoxWithConstraints(
        Modifier.fillMaxSize().background(BG)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDone)
            .semantics { contentDescription = "TheatreSync, a Quantari Solutions module" }
            .testTag("opening"),
    ) {
        val w = min(maxWidth, 480.dp)
        // Soft gold glow behind everything.
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = out }) {
            drawRect(
                Brush.radialGradient(
                    0f to GOLD.copy(alpha = (0.16f * glow).coerceAtMost(1f)), 0.45f to GOLD.copy(alpha = (0.07f * glow).coerceAtMost(1f)), 1f to Color.Transparent,
                    center = Offset(size.width / 2, size.height * 0.46f),
                    radius = size.minDimension * 0.75f,
                ),
            )
        }
        Column(
            Modifier.align(Alignment.Center).width(w).padding(horizontal = 24.dp).graphicsLayer { alpha = out },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Logo, with a gold light sweeping across it (drawn only on the logo's own pixels).
            Box(
                Modifier.width(w * 0.9f).aspectRatio(952f * LOGO_ROOM / 465f)
                    .graphicsLayer {
                        alpha = mark; scaleX = 0.96f + 0.04f * mark; scaleY = scaleX
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        if (sheen in 0.001f..0.999f) {
                            val band = size.width * 0.35f
                            val x = -band + (size.width + 2 * band) * sheen
                            drawRect(
                                Brush.linearGradient(
                                    listOf(Color.Transparent, Color(0xE6FFF8D6), Color.Transparent),
                                    start = Offset(x - band / 2, 0f), end = Offset(x + band / 2, size.height * 0.4f),
                                ),
                                blendMode = BlendMode.SrcAtop,
                            )
                        }
                        // Soft edges, so the gold swooshes fade out instead of being cut off.
                        val clear = Color.Transparent; val keep = Color.Black
                        drawRect(Brush.horizontalGradient(0f to clear, 0.09f to keep, 0.91f to keep, 1f to clear), blendMode = BlendMode.DstIn)
                        drawRect(Brush.verticalGradient(0f to clear, 0.14f to keep, 0.86f to keep, 1f to clear), blendMode = BlendMode.DstIn)
                    },
            ) {
                Image(painterResource(R.drawable.quantari_wordmark), null, Modifier.fillMaxHeight().fillMaxWidth(1f / LOGO_ROOM).align(Alignment.Center), contentScale = ContentScale.Fit)
            }
            Spacer(Modifier.height(28.dp))
            Text(
                "PRESENTED WITH",
                Modifier.rise(eyebrow),
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.42.em, color = GOLD),
                maxLines = 1, softWrap = false,
            )
            Spacer(Modifier.height(10.dp))
            Box(contentAlignment = Alignment.Center) {
            // Warm halo behind the title that blooms in, then swells with the rest.
            Canvas(Modifier.matchParentSize().graphicsLayer { alpha = out }) {
                val a = (0.13f * bloom * (1f + 0.8f * swell)).coerceAtMost(1f)
                val r = size.width * 0.7f
                // A wide, soft oval: several stops so it fades out with no visible edge.
                scale(scaleX = 1f, scaleY = 0.42f, pivot = center) {
                    drawCircle(
                        Brush.radialGradient(
                            0f to GOLD.copy(alpha = a), 0.35f to GOLD.copy(alpha = a * 0.55f),
                            0.7f to GOLD.copy(alpha = a * 0.15f), 1f to Color.Transparent,
                            center = center, radius = r,
                        ),
                        radius = r, center = center,
                    )
                }
            }
            Text(
                "TheatreSync",
                Modifier.rise(title),
                style = TextStyle(
                    fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 50.sp, letterSpacing = (-0.02).em,
                    brush = Brush.horizontalGradient(listOf(GOLD, Color(0xFFFFE7A3), GOLD)),
                    shadow = Shadow(GOLD.copy(alpha = (0.25f + 0.45f * bloom + 0.25f * swell).coerceAtMost(1f)), blurRadius = 12f + 44f * bloom + 24f * swell),
                ),
                maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
            )
            }
            Spacer(Modifier.height(14.dp))
            Canvas(Modifier.width(w * 0.62f).height(2.dp)) {
                val lw = size.width * line
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, GOLD, Color.Transparent), startX = (size.width - lw) / 2, endX = (size.width + lw) / 2),
                    topLeft = Offset((size.width - lw) / 2, 0f), size = size.copy(width = lw),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "a Quantari Solutions module",
                Modifier.rise(tag),
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = 0.06.em, color = Color(0xFFA9A39A)),
                maxLines = 1, softWrap = false,
            )
        }
    }
}

private const val TOTAL_MS = 6600
/** Extra width around the logo so only the swooshes fade at the sides, never the letters. */
private const val LOGO_ROOM = 1.14f
private val BG = Color(0xFF050507)
private val GOLD = Color(0xFFE8B84A)
private val SOFT: Easing = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)
/** Ease in and out like a sine wave: no sudden starts or stops. */
private val GENTLE: Easing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

/** 0 → 1 → 0 over [dur] ms from [start], smooth at both ends (a slow swell). */
private fun bell(t: Float, start: Int, dur: Int): Float {
    val x = ((t - start) / dur).coerceIn(0f, 1f)
    return (0.5f - 0.5f * kotlin.math.cos(2f * Math.PI.toFloat() * x))
}

/** Progress 0..1 of a step that starts at [start] ms and lasts [dur] ms. */
private fun seg(t: Float, start: Int, dur: Int, easing: Easing = FastOutSlowInEasing): Float =
    easing.transform(((t - start) / dur).coerceIn(0f, 1f))

/** Fade in while rising 24 dp into place. */
private fun Modifier.rise(p: Float) = graphicsLayer { alpha = p; translationY = (1f - p) * 24.dp.toPx() }

/** Instrumented tests drive the app directly; the opening would only get in their way. */
internal val runningUnderTest: Boolean by lazy {
    runCatching { Class.forName("androidx.test.platform.app.InstrumentationRegistry") }.isSuccess
}
