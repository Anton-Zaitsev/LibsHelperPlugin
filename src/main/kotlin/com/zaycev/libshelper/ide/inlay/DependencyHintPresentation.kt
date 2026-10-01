package com.zaycev.libshelper.ide.inlay

import com.intellij.codeInsight.hints.presentation.BasePresentation
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.zaycev.libshelper.core.inlay.BadgeLayout
import com.zaycev.libshelper.core.inlay.DependencyHint
import com.zaycev.libshelper.core.inlay.DependencyHintKind
import com.zaycev.libshelper.core.inlay.layoutBadge
import com.zaycev.libshelper.core.model.VersionChannel
import com.zaycev.libshelper.ide.i18n.msg
import com.zaycev.libshelper.ide.ui.channelLabel
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.RenderingHints
import kotlin.math.roundToInt

internal fun dependencyHintPresentation(
    editor: Editor,
    factory: PresentationFactory,
    hint: DependencyHint,
    onClick: (DependencyHint) -> Unit,
): InlayPresentation {
    val badge = StatusBadgePresentation(editor, hintLabel(hint), colorOf(hint.kind))
    val withTooltip = factory.withTooltip(hintTooltip(hint), badge)
    return factory.referenceOnHover(withTooltip) { _, _ -> onClick(hint) }
}

internal fun hintLabel(hint: DependencyHint): String = when (hint.kind) {
    DependencyHintKind.Outdated -> {
        val version = hint.recommendedVersion?.takeIf { it.isNotBlank() }
        val channel = hint.recommendedChannel
        when {
            version == null -> msg("inlay.label.current")
            channel != null && channel != VersionChannel.Stable -> "$version · ${channelLabel(channel)}"
            else -> version
        }
    }
    DependencyHintKind.Current -> msg("inlay.label.current")
    DependencyHintKind.Alpha -> msg("inlay.label.alpha")
    DependencyHintKind.Beta -> msg("inlay.label.beta")
    DependencyHintKind.Rc -> msg("inlay.label.rc")
    DependencyHintKind.Snapshot -> msg("inlay.label.snapshot")
}

internal fun matchesSettings(hint: DependencyHint, settings: DependencyHintSettings): Boolean =
    when (hint.kind) {
        DependencyHintKind.Outdated -> settings.showOutdated
        DependencyHintKind.Current -> settings.showCurrent
        DependencyHintKind.Alpha,
        DependencyHintKind.Beta,
        DependencyHintKind.Rc,
        DependencyHintKind.Snapshot,
        -> settings.showPrerelease
    }

internal fun hintTooltip(hint: DependencyHint): String {
    val name = hint.catalogAlias ?: hint.coordinatesKey
    return when (hint.kind) {
        DependencyHintKind.Outdated -> {
            val next = hint.recommendedVersion
            if (next.isNullOrBlank()) {
                msg("inlay.tooltip.outdated", name, hint.currentVersion.orEmpty())
            } else {
                msg("inlay.tooltip.outdated.to", name, hint.currentVersion.orEmpty(), next)
            }
        }
        DependencyHintKind.Current -> msg("inlay.tooltip.current", name, hint.currentVersion.orEmpty())
        DependencyHintKind.Alpha -> msg("inlay.tooltip.alpha", name, hint.currentVersion.orEmpty())
        DependencyHintKind.Beta -> msg("inlay.tooltip.beta", name, hint.currentVersion.orEmpty())
        DependencyHintKind.Rc -> msg("inlay.tooltip.rc", name, hint.currentVersion.orEmpty())
        DependencyHintKind.Snapshot -> msg("inlay.tooltip.snapshot", name, hint.currentVersion.orEmpty())
    }
}

private fun colorOf(kind: DependencyHintKind): Color = when (kind) {
    DependencyHintKind.Outdated -> Outdated
    DependencyHintKind.Current -> Current
    DependencyHintKind.Alpha -> Alpha
    DependencyHintKind.Beta -> Beta
    DependencyHintKind.Rc -> Rc
    DependencyHintKind.Snapshot -> Snapshot
}

private val Outdated = JBColor(Color(0xC24E00), Color(0xF0A050))
private val Current = JBColor(Color(0x1B7F4E), Color(0x3DB872))
private val Alpha = JBColor(Color(0x7A3EC8), Color(0xC48BE6))
private val Beta = JBColor(Color(0x2B5EC9), Color(0x6B9BF2))
private val Rc = JBColor(Color(0xB57A00), Color(0xE6B450))
private val Snapshot = JBColor(Color(0x5F6368), Color(0x9AA0A6))

private class StatusBadgePresentation(
    private val editor: Editor,
    private val label: String,
    private val accent: Color,
) : BasePresentation() {
    override val width: Int
        get() = layout(metrics()).width

    override val height: Int
        get() = layout(metrics()).height

    override fun paint(g: Graphics2D, attributes: TextAttributes) {
        val font = labelFont(editor)
        val metrics = editor.contentComponent.getFontMetrics(font)
        val placed = layout(metrics)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val pillX = leading()
            val pillWidth = placed.width - leading()
            val height = placed.height
            g2.color = bubbleFill(accent)
            g2.fillRoundRect(pillX, 0, pillWidth, height, height, height)
            val mid = height / 2.0
            val dot = dotSize()
            g2.color = accent
            g2.fillRoundRect(
                placed.dotX,
                (mid - dot / 2.0).roundToInt(),
                dot,
                dot,
                JBUI.scale(DOT_CORNER),
                JBUI.scale(DOT_CORNER),
            )
            g2.font = font
            g2.color = accent
            g2.drawString(
                label,
                placed.textX,
                (mid + (metrics.ascent - metrics.descent) / 2.0).roundToInt(),
            )
        } finally {
            g2.dispose()
        }
    }

    override fun toString(): String = label

    private fun metrics(): FontMetrics = editor.contentComponent.getFontMetrics(labelFont(editor))

    private fun layout(metrics: FontMetrics): BadgeLayout = layoutBadge(
        textWidth = metrics.stringWidth(label),
        ascent = metrics.ascent,
        descent = metrics.descent,
        dot = dotSize(),
        padH = JBUI.scale(PAD_H),
        padV = JBUI.scale(PAD_V),
        gap = JBUI.scale(GAP),
        leading = leading(),
    )
}

private fun labelFont(editor: Editor): Font {
    val base = editor.colorsScheme.getFont(EditorFontType.PLAIN)
    return base.deriveFont(Font.PLAIN, base.size2D * FONT_SCALE)
}

private fun bubbleFill(accent: Color): Color =
    Color(accent.red, accent.green, accent.blue, BUBBLE_ALPHA)

private fun leading(): Int = JBUI.scale(LEADING)

private fun dotSize(): Int = JBUI.scale(DOT)

private const val FONT_SCALE = 0.82f
private const val DOT = 7
private const val PAD_H = 6
private const val PAD_V = 2
private const val GAP = 4
private const val LEADING = 6
private const val BUBBLE_ALPHA = 56
private const val DOT_CORNER = 2
