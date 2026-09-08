package com.zaycev.libshelper.ide.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.zaycev.libshelper.core.model.OfferScore
import com.zaycev.libshelper.core.model.VersionChannel
import com.zaycev.libshelper.ide.ui.StatusKind
import org.jetbrains.jewel.foundation.theme.JewelTheme

object ComposePalette {
    @Composable
    fun status(kind: StatusKind): Color = when (kind) {
        StatusKind.Outdated -> if (dark()) Color(0xFFF0A050) else Color(0xFFC24E00)
        StatusKind.Current -> if (dark()) Color(0xFF3DB872) else Color(0xFF1B7F4E)
        StatusKind.Alpha -> if (dark()) Color(0xFFC48BE6) else Color(0xFF7A3EC8)
        StatusKind.Beta -> if (dark()) Color(0xFF6B9BF2) else Color(0xFF2B5EC9)
        StatusKind.Rc -> if (dark()) Color(0xFFE6B450) else Color(0xFFB57A00)
        StatusKind.Snapshot -> if (dark()) Color(0xFF9AA0A6) else Color(0xFF5F6368)
    }

    @Composable
    fun channel(channel: VersionChannel): Color = when (channel) {
        VersionChannel.Stable -> status(StatusKind.Current)
        VersionChannel.ReleaseCandidate -> status(StatusKind.Rc)
        VersionChannel.Beta -> status(StatusKind.Beta)
        VersionChannel.Alpha -> status(StatusKind.Alpha)
        VersionChannel.Snapshot -> status(StatusKind.Snapshot)
    }

    @Composable
    fun score(score: OfferScore): Color = when (score) {
        OfferScore.Safe, OfferScore.Recommended -> status(StatusKind.Current)
        OfferScore.Risky -> status(StatusKind.Outdated)
        OfferScore.DoNot -> if (dark()) Color(0xFFE06C75) else Color(0xFFB3261E)
    }

    @Composable
    @ReadOnlyComposable
    fun muted(): Color = JewelTheme.globalColors.text.info

    @Composable
    @ReadOnlyComposable
    fun danger(): Color = JewelTheme.globalColors.outlines.error

    @Composable
    @ReadOnlyComposable
    fun accent(): Color = JewelTheme.globalColors.outlines.focused

    @Composable
    fun tabUpdates(): Color = status(StatusKind.Outdated)

    @Composable
    fun tabLibraries(): Color = current()

    @Composable
    fun tabAnalytics(): Color = status(StatusKind.Current)

    @Composable
    fun tabSources(): Color = status(StatusKind.Alpha)

    @Composable
    fun cardFill(): Color = if (dark()) Color(0xFF2B2D30) else Color(0xFFF7F8FA)

    @Composable
    @ReadOnlyComposable
    fun cardBorder(): Color = JewelTheme.globalColors.borders.normal

    @Composable
    fun noteFill(): Color = if (dark()) Color(0xFF323437) else Color(0xFFEEF1F5)

    @Composable
    fun current(): Color = if (dark()) Color(0xFF7AA8F0) else Color(0xFF3D6BC9)

    @Composable
    @ReadOnlyComposable
    private fun dark(): Boolean = JewelTheme.isDark
}
