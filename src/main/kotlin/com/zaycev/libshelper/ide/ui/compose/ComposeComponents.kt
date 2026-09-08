package com.zaycev.libshelper.ide.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import kotlinx.collections.immutable.ImmutableList

@Composable
fun AccentCard(
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val stripe = 4.dp
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ComposePalette.cardFill())
            .border(1.dp, ComposePalette.cardBorder(), shape)
            .drawBehind {
                drawRect(accent, size = Size(stripe.toPx(), size.height))
            }
            .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun Pill(
    text: String,
    tone: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = tone,
        style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
        modifier = modifier
            .clip(CircleShape)
            .background(tone.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = ComposePalette.muted(),
        style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
        modifier = modifier,
    )
}

@Composable
fun TitleText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
        modifier = modifier,
    )
}

@Composable
fun HeroVersion(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
        modifier = modifier,
    )
}

@Composable
fun NavTab(
    label: String,
    selected: Boolean,
    tone: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alpha = animateFloatAsState(if (selected) 1f else 0f, tween(180), label = "tab")
    val bg = tone.copy(alpha = 0.18f * alpha.value)
    val fg = if (selected) tone else ComposePalette.muted()
    Text(
        text = label,
        color = fg,
        style = JewelTheme.defaultTextStyle.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
fun ChipTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavTab(
        label = label,
        selected = selected,
        tone = ComposePalette.accent(),
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
fun PillRow(
    modifier: Modifier = Modifier,
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
fun SectionGap(modifier: Modifier = Modifier) {
    Spacer(modifier.height(12.dp))
}

@Composable
fun ProgressTrack(completed: Int, total: Int, modifier: Modifier = Modifier) {
    val fraction = if (total <= 0) 0f else (completed.toFloat() / total).coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(ComposePalette.cardBorder()),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(CircleShape)
                .background(ComposePalette.accent()),
        )
    }
}

@Composable
fun IndeterminateTrack(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(ComposePalette.accent().copy(alpha = 0.35f)),
    )
}

@Composable
fun ToolbarAction(
    compact: Boolean,
    label: String,
    icon: IconKey,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(key = icon, contentDescription = label, modifier = Modifier.size(16.dp))
            if (!compact) {
                Text(label)
            }
        }
    }
}

@Composable
fun <T> ResponsiveGrid(
    items: ImmutableList<T>,
    wide: Boolean,
    modifier: Modifier = Modifier,
    itemContent: @Composable (T) -> Unit,
) {
    val columns = if (wide) 2 else 1
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { item ->
                    Box(Modifier.weight(1f)) { itemContent(item) }
                }
                repeat(columns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
