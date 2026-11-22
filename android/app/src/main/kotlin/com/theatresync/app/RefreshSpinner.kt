package com.theatresync.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pull-to-refresh spinner: a gold refresh arrow that turns with your pull, then, once released, spins
 * exactly [TURNS] whole turns at an even speed and calls [onSpun] (so it always completes full
 * revolutions, unlike Material's spinner, whose arc keeps growing and shrinking).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.RefreshSpinner(state: PullToRefreshState, refreshing: Boolean, onSpun: () -> Unit) {
    val done = rememberUpdatedState(onSpun)
    // Own frame clock (not Android's animation settings, which can make animations end instantly):
    // always the full turns.
    val turn = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(refreshing) {
        if (!refreshing) return@LaunchedEffect
        val total = 360f * TURNS
        turn.floatValue = 0f
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (turn.floatValue < total) {
            val ms = (androidx.compose.runtime.withFrameNanos { it } - start) / 1_000_000f
            turn.floatValue = (ms / (TURN_MS * TURNS) * total).coerceAtMost(total)
        }
        done.value()
    }
    val pull = state.distanceFraction.coerceIn(0f, 1.4f)
    if (!refreshing && pull <= 0f) return
    val drop = if (refreshing) 1f else pull
    Box(
        Modifier.align(Alignment.TopCenter).offset(y = 72.dp * drop - 40.dp).size(40.dp)
            .graphicsLayer {
                alpha = if (refreshing) 1f else pull.coerceAtMost(1f)
                rotationZ = if (refreshing) turn.floatValue else pull * 270f
            }
            .background(Q.Card, CircleShape).border(1.dp, Q.BorderAccent, CircleShape)
            .testTag("refreshSpinner"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = 2.4.dp.toPx()
            val r = size.minDimension / 2 - w
            drawArc(Q.Primary, startAngle = -60f, sweepAngle = 300f, useCenter = false, topLeft = Offset(center.x - r, center.y - r),
                size = androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(w, cap = StrokeCap.Round))
            // Arrowhead at the arc's end (pointing clockwise).
            val a = Math.toRadians(-60.0).toFloat()
            val tip = Offset(center.x + r * cos(a), center.y + r * sin(a))
            val head = 3.6.dp.toPx()
            drawPath(Path().apply {
                moveTo(tip.x + head, tip.y + head * 0.2f); lineTo(tip.x - head * 0.2f, tip.y - head); lineTo(tip.x - head * 0.9f, tip.y + head * 0.9f); close()
            }, Q.Primary)
        }
    }
}

/** Whole turns after release, and how long each takes. */
private const val TURNS = 2
private const val TURN_MS = 900
