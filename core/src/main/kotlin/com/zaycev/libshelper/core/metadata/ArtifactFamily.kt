package com.zaycev.libshelper.core.metadata

import com.zaycev.libshelper.core.model.Coordinates

enum class ArtifactFamily {
    JetBrainsCompose,
    AndroidX,
    JetBrainsAndroidX,
    Kotlin,
    GradlePlugin,
    Other,
}

fun artifactFamily(coordinates: Coordinates, isPlugin: Boolean): ArtifactFamily {
    val group = coordinates.group
    if (isPlugin || coordinates.artifact.endsWith(".gradle.plugin")) return ArtifactFamily.GradlePlugin
    if (group.startsWith("org.jetbrains.compose")) return ArtifactFamily.JetBrainsCompose
    if (group.startsWith("org.jetbrains.androidx.")) return ArtifactFamily.JetBrainsAndroidX
    if (group.startsWith("androidx.") ||
        group.startsWith("com.android.") ||
        group.startsWith("com.google.android.") ||
        group.startsWith("com.google.firebase.")
    ) {
        return ArtifactFamily.AndroidX
    }
    if (group.startsWith("org.jetbrains.kotlin")) return ArtifactFamily.Kotlin
    return ArtifactFamily.Other
}

fun familiesCompatible(left: ArtifactFamily, right: ArtifactFamily): Boolean {
    if (left == right) return true
    if (left == ArtifactFamily.Other || right == ArtifactFamily.Other) return true
    if (left == ArtifactFamily.GradlePlugin || right == ArtifactFamily.GradlePlugin) return true
    return false
}
