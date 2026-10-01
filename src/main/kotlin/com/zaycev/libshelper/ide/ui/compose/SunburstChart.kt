package com.zaycev.libshelper.ide.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaycev.libshelper.core.analytics.SunburstChartData
import com.zaycev.libshelper.core.analytics.SunburstSlice
import com.zaycev.libshelper.ide.i18n.msg
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

private val SlicePalette = persistentListOf(
    Color(0xFFE07A3D),
    Color(0xFF4C8DFF),
    Color(0xFFD4A017),
    Color(0xFF3DB872),
    Color(0xFFC48BE6),
    Color(0xFFE06C75),
    Color(0xFF6B9BF2),
    Color(0xFF2BB8A3),
    Color(0xFFF0A050),
    Color(0xFF8E8E93),
)

private const val FULL_CIRCLE_DEG = 360f
private const val HALF_CIRCLE_DEG = 180f

@Composable
fun SunburstChart(
    data: SunburstChartData,
    onOpenLibrary: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(data.slices) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 700, easing = FastOutSlowInEasing))
    }
    var hover by remember(data.slices) { mutableStateOf<SunburstSlice?>(null) }
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(280.dp),
        ) {
            val scratch = remember { Path() }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(data.slices) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val position = event.changes.first().position
                                val hit = hitSlice(
                                    data.slices,
                                    position,
                                    size.width.toFloat(),
                                    size.height.toFloat(),
                                )
                                hover = hit
                                if (event.type == PointerEventType.Release) {
                                    hit?.libraryKey?.let(onOpenLibrary)
                                }
                            }
                        }
                    },
            ) {
                val radius = min(size.width, size.height) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                data.slices.forEach { slice ->
                    val base = SlicePalette[slice.colorIndex.mod(SlicePalette.size)]
                    val color = if (slice.libraryKey == null) base.copy(alpha = 0.72f) else base
                    val highlighted = hover?.id == slice.id ||
                        (hover != null && slice.libraryKey == null && hover?.colorIndex == slice.colorIndex)
                    drawSlice(
                        slice = slice,
                        color = if (highlighted) color.copy(alpha = 1f) else color,
                        center = center,
                        radius = radius,
                        progress = progress.value,
                        scratch = scratch,
                    )
                }
            }
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = data.libraryCount.toString(),
                    style = JewelTheme.defaultTextStyle.copy(fontSize = 22.sp),
                )
                MutedText(msg("analytics.sunburst.center"))
            }
        }
        val caption = hover
        if (caption != null) {
            MutedText(
                msg("analytics.sunburst.hint", caption.label, caption.weight),
                Modifier.padding(top = 8.dp),
            )
        } else {
            MutedText(msg("analytics.sunburst.idle"), Modifier.padding(top = 8.dp))
        }
    }
}

private fun DrawScope.drawSlice(
    slice: SunburstSlice,
    color: Color,
    center: Offset,
    radius: Float,
    progress: Float,
    scratch: Path,
) {
    val inner = radius * slice.inner
    val outer = radius * slice.outer
    val start = slice.startDeg
    val sweep = slice.sweepDeg * progress
    if (sweep <= 0f) return
    val path = scratch.apply { rewind() }
    val outerRect = Rect(center.x - outer, center.y - outer, center.x + outer, center.y + outer)
    val innerRect = Rect(center.x - inner, center.y - inner, center.x + inner, center.y + inner)
    path.moveTo(
        center.x + inner * kotlin.math.cos(start.toRadians()),
        center.y + inner * kotlin.math.sin(start.toRadians()),
    )
    path.lineTo(
        center.x + outer * kotlin.math.cos(start.toRadians()),
        center.y + outer * kotlin.math.sin(start.toRadians()),
    )
    path.arcTo(outerRect, start, sweep, false)
    path.lineTo(
        center.x + inner * kotlin.math.cos((start + sweep).toRadians()),
        center.y + inner * kotlin.math.sin((start + sweep).toRadians()),
    )
    path.arcTo(innerRect, start + sweep, -sweep, false)
    path.close()
    drawPath(path, color)
}

private fun hitSlice(
    slices: ImmutableList<SunburstSlice>,
    position: Offset,
    width: Float,
    height: Float,
): SunburstSlice? {
    val cx = width / 2f
    val cy = height / 2f
    val radius = min(width, height) / 2f
    if (radius <= 0f) return null
    val dx = position.x - cx
    val dy = position.y - cy
    val dist = hypot(dx, dy) / radius
    val deg = atan2(dy, dx) * HALF_CIRCLE_DEG / PI.toFloat()
    return slices.lastOrNull { slice ->
        dist in slice.inner..slice.outer && angleInSlice(deg, slice.startDeg, slice.sweepDeg)
    }
}

private fun angleInSlice(deg: Float, start: Float, sweep: Float): Boolean {
    val hit = wrapDeg(deg)
    val from = wrapDeg(start)
    val delta = wrapDeg(hit - from)
    return delta <= sweep
}

private fun wrapDeg(value: Float): Float {
    var current = value % FULL_CIRCLE_DEG
    if (current < 0f) current += FULL_CIRCLE_DEG
    return current
}

private fun Float.toRadians(): Float = this * PI.toFloat() / HALF_CIRCLE_DEG
