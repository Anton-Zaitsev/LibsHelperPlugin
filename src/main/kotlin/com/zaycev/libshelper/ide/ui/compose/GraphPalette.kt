package com.zaycev.libshelper.ide.ui.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.zaycev.libshelper.core.model.ModuleKind

@Immutable
internal data class GraphPalette(
    val canvas: Color,
    val label: Color,
    val muted: Color,
    val hierarchy: Color,
    val projectDep: Color,
    val targetLink: Color,
    val selected: Color,
    val match: Color,
    val dim: Color,
)

private val RootDark = Color(0xFF9AA0A6)
private val RootLight = Color(0xFF5F6368)
private val GradleDark = Color(0xFF6B9BF2)
private val GradleLight = Color(0xFF2B5EC9)
private val AndroidDark = Color(0xFF3DDC84)
private val AndroidLight = Color(0xFF1B8A4A)
private val JvmDark = Color(0xFFF0A050)
private val JvmLight = Color(0xFFC24E00)
private val KmpDark = Color(0xFFC48BE6)
private val KmpLight = Color(0xFF7A3EC8)
private val IosDark = Color(0xFF8E8E93)
private val IosLight = Color(0xFF4A4A4F)
private val JsDark = Color(0xFFE6C84A)
private val JsLight = Color(0xFFB08900)
private val WasmDark = Color(0xFF2BB8A3)
private val WasmLight = Color(0xFF0F7A6B)
private val NativeDark = Color(0xFF6B9BF2)
private val NativeLight = Color(0xFF3D6BC9)
private val DesktopDark = Color(0xFF4C8DFF)
private val DesktopLight = Color(0xFF1D4ED8)
private val CommonDark = Color(0xFFB0B4BA)
private val CommonLight = Color(0xFF6B7280)

internal fun graphKindFill(kind: ModuleKind, dark: Boolean): Color = when (kind) {
    ModuleKind.Root -> if (dark) RootDark else RootLight
    ModuleKind.Gradle -> if (dark) GradleDark else GradleLight
    ModuleKind.Android -> if (dark) AndroidDark else AndroidLight
    ModuleKind.Jvm -> if (dark) JvmDark else JvmLight
    ModuleKind.Kmp -> if (dark) KmpDark else KmpLight
    ModuleKind.Ios -> if (dark) IosDark else IosLight
    ModuleKind.Js -> if (dark) JsDark else JsLight
    ModuleKind.Wasm -> if (dark) WasmDark else WasmLight
    ModuleKind.Native -> if (dark) NativeDark else NativeLight
    ModuleKind.Desktop -> if (dark) DesktopDark else DesktopLight
    ModuleKind.Common -> if (dark) CommonDark else CommonLight
}

internal fun graphKindGlyph(kind: ModuleKind): String = when (kind) {
    ModuleKind.Root -> "⌂"
    ModuleKind.Gradle -> "G"
    ModuleKind.Android -> "A"
    ModuleKind.Jvm -> "J"
    ModuleKind.Kmp -> "K"
    ModuleKind.Ios -> "i"
    ModuleKind.Js -> "S"
    ModuleKind.Wasm -> "W"
    ModuleKind.Native -> "N"
    ModuleKind.Desktop -> "D"
    ModuleKind.Common -> "c"
}
