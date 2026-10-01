package com.zaycev.libshelper.core.metadata

import com.zaycev.libshelper.core.model.Coordinates
import com.zaycev.libshelper.core.model.DeclaredRepository
import com.zaycev.libshelper.core.proxy.officialGoogleMaven
import com.zaycev.libshelper.core.proxy.officialJitPack
import com.zaycev.libshelper.core.proxy.officialJetBrainsCompose
import com.zaycev.libshelper.core.proxy.officialMavenCentral
import com.zaycev.libshelper.core.proxy.officialPluginPortal

fun officialBasesFor(
    coordinates: Coordinates,
    isPlugin: Boolean,
    projectRepositories: List<DeclaredRepository> = emptyList(),
): List<String> {
    val group = coordinates.group
    val family = artifactFamily(coordinates, isPlugin)
    val declaredJitpack = projectRepositories.any { it.url.contains("jitpack.io", ignoreCase = true) }
    val declaredComposeDev = projectRepositories.any { repo ->
        val url = repo.url.lowercase()
        url.contains("maven.pkg.jetbrains.space") && url.contains("compose")
    }
    if (family == ArtifactFamily.GradlePlugin || isPlugin) {
        return listOf(officialPluginPortal(), officialMavenCentral())
    }
    val githubGroup = group.startsWith("com.github.") || group.startsWith("io.github.")
    if (githubGroup && declaredJitpack) {
        return listOf(officialJitPack(), officialMavenCentral())
    }
    if (githubGroup) {
        return listOf(officialMavenCentral())
    }
    return when (family) {
        ArtifactFamily.JetBrainsCompose,
        ArtifactFamily.JetBrainsAndroidX,
        -> {
            val bases = mutableListOf(officialMavenCentral())
            if (declaredComposeDev) bases += officialJetBrainsCompose()
            bases
        }
        ArtifactFamily.AndroidX -> officialGoogleMaven() + officialMavenCentral()
        ArtifactFamily.Kotlin,
        ArtifactFamily.GradlePlugin,
        ArtifactFamily.Other,
        -> listOf(officialMavenCentral()) + officialGoogleMaven()
    }.distinct()
}
