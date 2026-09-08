package com.zaycev.libshelper.core.metadata

import com.zaycev.libshelper.core.model.Coordinates
import com.zaycev.libshelper.core.proxy.officialGoogleMaven
import com.zaycev.libshelper.core.proxy.officialJitPack
import com.zaycev.libshelper.core.proxy.officialMavenCentral
import com.zaycev.libshelper.core.proxy.officialPluginPortal

fun officialBasesFor(coordinates: Coordinates, isPlugin: Boolean): List<String> {
    val group = coordinates.group
    val jitpack = group.startsWith("com.github.") || group.startsWith("io.github.")
    val googleFirst = group.startsWith("androidx.") ||
        group.startsWith("com.android.") ||
        group.startsWith("com.google.android.") ||
        group.startsWith("com.google.firebase.")
    if (isPlugin) {
        return listOf(officialPluginPortal(), officialMavenCentral())
    }
    if (jitpack) {
        return listOf(officialJitPack(), officialMavenCentral())
    }
    return if (googleFirst) {
        officialGoogleMaven() + officialMavenCentral()
    } else {
        listOf(officialMavenCentral()) + officialGoogleMaven()
    }.distinct()
}
