package dev.enov.ytm

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp

/** One shared shimmer clock keeps the placeholders moving together. */
@Composable
internal fun LoadingSkeleton(modifier: Modifier = Modifier, showHeading: Boolean = true, rows: Int = 8) {
    val transition = rememberInfiniteTransition(label = "Loading music")
    val phase by transition.animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "Shimmer position"
    )
    BoxWithConstraints(modifier.fillMaxSize().clearAndSetSemantics {
        contentDescription = "Loading music"
        liveRegion = LiveRegionMode.Polite
    }) {
        val width = with(LocalDensity.current) { maxWidth.toPx() }
        val base = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f)
        val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = .15f)
        val shimmer = Brush.linearGradient(
            colors = listOf(base, highlight, base),
            start = Offset(width * phase, 0f),
            end = Offset(width * (phase + 1f), width * .3f)
        )
        LazyColumn(contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp), userScrollEnabled = false) {
            if (showHeading) item {
                SkeletonBlock(Modifier.width(120.dp).height(10.dp), shimmer)
                Spacer(Modifier.height(16.dp))
                SkeletonBlock(Modifier.fillMaxWidth(.72f).height(30.dp), shimmer)
                Spacer(Modifier.height(24.dp))
            }
            items(rows) { index ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SkeletonBlock(Modifier.size(56.dp), shimmer)
                    Column(Modifier.weight(1f).padding(top = 7.dp)) {
                        SkeletonBlock(Modifier.fillMaxWidth(if (index % 2 == 0) .8f else .6f).height(15.dp), shimmer)
                        Spacer(Modifier.height(11.dp))
                        SkeletonBlock(Modifier.fillMaxWidth(.46f).height(11.dp), shimmer)
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonBlock(modifier: Modifier, brush: Brush) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(brush))
}
