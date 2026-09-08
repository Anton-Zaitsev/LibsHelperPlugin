package com.zaycev.libshelper.core.conflicts

import com.zaycev.libshelper.core.model.ConflictSignal
import com.zaycev.libshelper.core.model.ConflictType
import com.zaycev.libshelper.core.model.Coordinates
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.MetadataOriginKind
import com.zaycev.libshelper.core.model.ProjectInventory
import com.zaycev.libshelper.core.model.Severity
import com.zaycev.libshelper.core.versioning.MavenVersion
import com.zaycev.libshelper.core.versioning.VersionToken
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/**
 * Project-level upgrade hazards for a target version.
 * Channel (stable/RC/beta/alpha) is scored in [com.zaycev.libshelper.core.recommend.scoreOf]
 * and explained in consequences — it is not a conflict with the rest of the project.
 */
fun collectConflicts(
    dependency: DeclaredDependency,
    current: MavenVersion?,
    target: MavenVersion,
    inventory: ProjectInventory,
    originKind: MetadataOriginKind,
    proxyMissingOfficial: Boolean,
): List<ConflictSignal> {
    return listOfNotNull(
        majorBump(current, target),
        bomAlignment(dependency, inventory),
        sharedVersionRef(dependency, inventory),
        relocation(dependency, inventory),
        kotlinCompose(dependency, target, inventory),
        sdkLevel(dependency, target, inventory),
        repositoryGap(proxyMissingOfficial, originKind),
        familyMix(dependency, inventory),
    )
}

internal fun majorBump(current: MavenVersion?, target: MavenVersion): ConflictSignal? {
    if (current == null) return null
    val currentMajor = (current.tokens.firstOrNull() as? VersionToken.Number)?.value ?: return null
    val targetMajor = (target.tokens.firstOrNull() as? VersionToken.Number)?.value ?: return null
    if (targetMajor <= currentMajor) return null
    return ConflictSignal(
        type = ConflictType.MajorBump,
        severity = Severity.High,
        args = persistentListOf(current.raw, target.raw),
    )
}

internal fun bomAlignment(dependency: DeclaredDependency, inventory: ProjectInventory): ConflictSignal? {
    if (dependency.isBom) return null
    val family = bomFamilyOf(dependency.coordinates) ?: return null
    val bom = inventory.dependencies.firstOrNull { it.isBom && bomFamilyOf(it.coordinates) == family }
        ?: return null
    return ConflictSignal(
        type = ConflictType.BomAlignment,
        severity = Severity.Blocker,
        args = persistentListOf(bom.coordinates.artifact, dependency.coordinates.artifact),
        related = persistentListOf(bom.coordinates),
    )
}

internal fun sharedVersionRef(dependency: DeclaredDependency, inventory: ProjectInventory): ConflictSignal? {
    val ref = dependency.versionRef ?: return null
    val others = inventory.dependencies.filter {
        it.versionRef == ref && it.coordinates != dependency.coordinates
    }
    if (others.isEmpty()) return null
    return ConflictSignal(
        type = ConflictType.SharedVersionRef,
        severity = Severity.Caution,
        args = persistentListOf(ref, others.joinToString { it.coordinates.artifact }),
        related = others.map { it.coordinates }.toPersistentList(),
    )
}

internal fun relocation(dependency: DeclaredDependency, inventory: ProjectInventory): ConflictSignal? {
    val old = isLegacySupport(dependency.coordinates)
    if (!old) return null
    val androidx = inventory.dependencies.filter { it.coordinates.group.startsWith("androidx.") }
    if (androidx.isEmpty()) return null
    return ConflictSignal(
        type = ConflictType.Relocation,
        severity = Severity.High,
        args = persistentListOf(dependency.coordinates.key),
        related = androidx.map { it.coordinates }.take(3).toPersistentList(),
    )
}

internal fun kotlinCompose(
    dependency: DeclaredDependency,
    target: MavenVersion,
    inventory: ProjectInventory,
): ConflictSignal? {
    val isCompose = dependency.coordinates.group.startsWith("androidx.compose") ||
        dependency.coordinates.artifact.contains("compose", ignoreCase = true)
    if (!isCompose) return null
    val kotlin = inventory.kotlinVersion ?: return null
    val kotlinMajorMinor = kotlin.substringBeforeLast('.').toDoubleOrNull()
        ?: kotlin.take(3).toDoubleOrNull()
        ?: return null
    val composeMajor = (target.tokens.firstOrNull() as? VersionToken.Number)?.value ?: return null
    if (composeMajor >= 1 && kotlinMajorMinor < 2.0) {
        return ConflictSignal(
            type = ConflictType.KotlinCompose,
            severity = Severity.High,
            args = persistentListOf(target.raw, kotlin),
        )
    }
    return null
}

internal fun sdkLevel(
    dependency: DeclaredDependency,
    target: MavenVersion,
    inventory: ProjectInventory,
): ConflictSignal? {
    val minSdk = inventory.minSdk ?: return null
    val required = requiredMinSdk(dependency.coordinates, target) ?: return null
    if (required <= minSdk) return null
    return ConflictSignal(
        type = ConflictType.SdkLevel,
        severity = Severity.High,
        args = persistentListOf(dependency.coordinates.artifact, target.raw, required.toString(), minSdk.toString()),
    )
}

internal fun repositoryGap(proxyMissingOfficial: Boolean, originKind: MetadataOriginKind): ConflictSignal? {
    if (originKind == MetadataOriginKind.ProjectProxy) {
        return ConflictSignal(
            type = ConflictType.RepositoryGap,
            severity = Severity.Caution,
            args = persistentListOf("proxy"),
        )
    }
    if (proxyMissingOfficial) {
        return ConflictSignal(
            type = ConflictType.RepositoryGap,
            severity = Severity.Caution,
            args = persistentListOf("official"),
        )
    }
    return null
}

internal fun familyMix(dependency: DeclaredDependency, inventory: ProjectInventory): ConflictSignal? {
    val group = dependency.coordinates.group
    if (group == "com.squareup.okhttp3") {
        val old = inventory.dependencies.filter { it.coordinates.group == "com.squareup.okhttp" }
        if (old.isNotEmpty()) {
            return ConflictSignal(
                type = ConflictType.FamilyMix,
                severity = Severity.High,
                args = persistentListOf("okhttp"),
                related = old.map { it.coordinates }.toPersistentList(),
            )
        }
    }
    if (dependency.coordinates.artifact.contains("guava", ignoreCase = true)) {
        val variants = inventory.dependencies.filter {
            it.coordinates.group == "com.google.guava" && it.coordinates != dependency.coordinates
        }
        if (variants.any { it.coordinates.artifact != dependency.coordinates.artifact }) {
            return ConflictSignal(
                type = ConflictType.FamilyMix,
                severity = Severity.Caution,
                args = persistentListOf("guava"),
                related = variants.map { it.coordinates }.toPersistentList(),
            )
        }
    }
    return null
}

internal fun bomFamilyOf(coordinates: Coordinates): String? = when {
    coordinates.artifact.contains("compose-bom") || coordinates.group.startsWith("androidx.compose") -> "compose"
    coordinates.artifact.contains("firebase-bom") || coordinates.group.startsWith("com.google.firebase") -> "firebase"
    coordinates.artifact.contains("androidx-bom") -> "androidx"
    else -> null
}

internal fun isLegacySupport(coordinates: Coordinates): Boolean =
    coordinates.group.startsWith("com.android.support") ||
        coordinates.group.startsWith("android.arch")

internal fun requiredMinSdk(coordinates: Coordinates, target: MavenVersion): Int? {
    if (coordinates.group.startsWith("androidx.activity") || coordinates.group.startsWith("androidx.fragment")) {
        val minor = (target.tokens.getOrNull(1) as? VersionToken.Number)?.value
        if (minor != null && minor >= 10) return 21
    }
    return null
}
