package com.zaycev.libshelper.core.recommend

import com.zaycev.libshelper.core.metadata.ArtifactFamily
import com.zaycev.libshelper.core.metadata.artifactFamily
import com.zaycev.libshelper.core.metadata.familiesCompatible
import com.zaycev.libshelper.core.model.ChannelOffer
import com.zaycev.libshelper.core.model.ConflictSignal
import com.zaycev.libshelper.core.model.ConflictType
import com.zaycev.libshelper.core.model.LibraryAdvice
import com.zaycev.libshelper.core.model.MetadataOrigin
import com.zaycev.libshelper.core.model.MetadataOriginKind
import com.zaycev.libshelper.core.model.ProjectInventory
import com.zaycev.libshelper.core.model.Severity
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

fun alignSharedVersionRefs(
    libraries: List<LibraryAdvice>,
    inventory: ProjectInventory,
): List<LibraryAdvice> {
    val grouped = libraries.indices.groupBy { libraries[it].advice.dependency.versionRef }
    if (grouped.none { !it.key.isNullOrBlank() && it.value.size > 1 }) return libraries
    val aligned = libraries.toMutableList()
    for ((ref, indices) in grouped) {
        if (ref.isNullOrBlank() || indices.size < 2) continue
        val members = indices.map { libraries[it] }
        val families = members.map {
            artifactFamily(it.advice.dependency.coordinates, it.advice.dependency.isPlugin)
        }
        val mixed = families.filter { it != ArtifactFamily.Other && it != ArtifactFamily.GradlePlugin }
            .distinct()
            .let { distinct -> distinct.size > 1 && !distinct.all { left -> distinct.all { familiesCompatible(left, it) } } }
        val common = members
            .map { item -> item.advice.candidates.map { it.version.raw }.toSet() }
            .reduce { left, right -> left.intersect(right) }
        val signal = if (mixed) {
            ConflictSignal(
                type = ConflictType.MixedFamilySharedRef,
                severity = Severity.Caution,
                args = persistentListOf(ref, families.joinToString(",")),
            )
        } else {
            null
        }
        for (index in indices) {
            val item = libraries[index]
            val origin = item.advice.candidates.firstOrNull()?.origin
                ?: MetadataOrigin(MetadataOriginKind.OfficialDirect, "")
            val rebuilt = buildAdvice(
                dependency = item.advice.dependency,
                versions = common,
                origin = origin,
                inventory = inventory,
                proxyMissingOfficial = item.advice.preferredStable?.origin?.kind == MetadataOriginKind.ProjectProxy,
            )
            aligned[index] = item.copy(advice = rebuilt.withSharedConflict(signal))
        }
    }
    return aligned
}

private fun com.zaycev.libshelper.core.model.UpdateAdvice.withSharedConflict(
    signal: ConflictSignal?,
): com.zaycev.libshelper.core.model.UpdateAdvice {
    if (signal == null) return this
    return copy(
        preferredStable = preferredStable?.plus(signal),
        latestRc = latestRc?.plus(signal),
        latestBeta = latestBeta?.plus(signal),
        latestAlpha = latestAlpha?.plus(signal),
        sharedConflicts = sharedConflicts + signal,
    )
}

private fun ChannelOffer.plus(signal: ConflictSignal): ChannelOffer =
    copy(conflicts = (conflicts + signal).toPersistentList())
