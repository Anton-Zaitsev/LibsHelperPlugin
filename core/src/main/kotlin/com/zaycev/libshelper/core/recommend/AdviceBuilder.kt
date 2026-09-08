package com.zaycev.libshelper.core.recommend

import com.zaycev.libshelper.core.conflicts.collectConflicts
import com.zaycev.libshelper.core.model.ChannelOffer
import com.zaycev.libshelper.core.model.ConflictSignal
import com.zaycev.libshelper.core.model.ConflictType
import com.zaycev.libshelper.core.model.Consequence
import com.zaycev.libshelper.core.model.ConsequenceId
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.LocalArtifactKind
import com.zaycev.libshelper.core.model.MetadataOrigin
import com.zaycev.libshelper.core.model.MetadataOriginKind
import com.zaycev.libshelper.core.model.OfferScore
import com.zaycev.libshelper.core.model.ProjectInventory
import com.zaycev.libshelper.core.model.Severity
import com.zaycev.libshelper.core.model.UpdateAdvice
import com.zaycev.libshelper.core.model.VersionChannel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import com.zaycev.libshelper.core.versioning.ChannelLatest
import com.zaycev.libshelper.core.versioning.MavenVersion
import com.zaycev.libshelper.core.versioning.classifyVersion
import com.zaycev.libshelper.core.versioning.latestPerChannel
import com.zaycev.libshelper.core.versioning.sameMajorStables

fun buildAdvice(
    dependency: DeclaredDependency,
    versions: Collection<String>,
    origin: MetadataOrigin,
    inventory: ProjectInventory,
    proxyMissingOfficial: Boolean = false,
): UpdateAdvice {
    val current = dependency.requestedVersion?.let { MavenVersion.parse(it) }
    val currentChannel = dependency.requestedVersion?.let { classifyVersion(it) }
    val latest = latestPerChannel(versions)
    val stables = versions.mapNotNull { raw ->
        if (classifyVersion(raw) == VersionChannel.Stable) MavenVersion.parse(raw) else null
    }
    val sameLine = current?.let { cur ->
        sameMajorStables(cur, stables).lastOrNull { it > cur }
    }?.takeIf { latest.stable != null && it != latest.stable }

    val latestStable = latest.stable
    val isOutdated = isVersionOutdated(current, currentChannel, latest)

    val shouldRecommendStable = latestStable != null &&
        current?.raw != latestStable.raw &&
        latestStable.let { current != null && it > current }

    val preferred = latestStable?.takeIf { shouldRecommendStable }?.let { stable ->
        offer(
            channel = VersionChannel.Stable,
            version = stable,
            current = current,
            currentChannel = currentChannel,
            isPreferred = true,
            origin = origin,
            inventory = inventory,
            dependency = dependency,
            proxyMissingOfficial = proxyMissingOfficial,
            sameLinePatch = sameLine,
        )
    }

    return UpdateAdvice(
        dependency = dependency,
        current = current,
        currentChannel = currentChannel,
        isOutdated = isOutdated,
        preferredStable = preferred,
        latestRc = latest.rc?.takeIf { it.raw != current?.raw }?.let {
            offer(VersionChannel.ReleaseCandidate, it, current, currentChannel, false, origin, inventory, dependency, proxyMissingOfficial, sameLine)
        },
        latestBeta = latest.beta?.takeIf { it.raw != current?.raw }?.let {
            offer(VersionChannel.Beta, it, current, currentChannel, false, origin, inventory, dependency, proxyMissingOfficial, sameLine)
        },
        latestAlpha = latest.alpha?.takeIf { it.raw != current?.raw }?.let {
            offer(VersionChannel.Alpha, it, current, currentChannel, false, origin, inventory, dependency, proxyMissingOfficial, sameLine)
        },
    )
}

internal fun isVersionOutdated(
    current: MavenVersion?,
    currentChannel: VersionChannel?,
    latest: ChannelLatest,
): Boolean {
    if (current == null) return false
    val latestStable = latest.stable
    val latestOnChannel = when (currentChannel) {
        VersionChannel.ReleaseCandidate -> latest.rc
        VersionChannel.Beta -> latest.beta
        VersionChannel.Alpha -> latest.alpha
        VersionChannel.Snapshot -> latest.snapshot
        VersionChannel.Stable, null -> latestStable
    }
    return when (currentChannel) {
        null, VersionChannel.Stable -> latestStable != null && latestStable > current
        else -> {
            val newerStable = latestStable != null && latestStable > current
            val newerOnChannel = latestOnChannel != null && latestOnChannel > current
            newerStable || newerOnChannel
        }
    }
}

private fun offer(
    channel: VersionChannel,
    version: MavenVersion,
    current: MavenVersion?,
    currentChannel: VersionChannel?,
    isPreferred: Boolean,
    origin: MetadataOrigin,
    inventory: ProjectInventory,
    dependency: DeclaredDependency,
    proxyMissingOfficial: Boolean,
    sameLinePatch: MavenVersion?,
): ChannelOffer {
    val conflicts = collectConflicts(
        dependency = dependency,
        current = current,
        target = version,
        inventory = inventory,
        originKind = origin.kind,
        proxyMissingOfficial = proxyMissingOfficial,
    )
    val consequences = consequencesFor(
        channel = channel,
        current = current,
        currentChannel = currentChannel,
        target = version,
        sameLinePatch = sameLinePatch,
        conflicts = conflicts,
        origin = origin,
        dependency = dependency,
    )
    return ChannelOffer(
        channel = channel,
        version = version,
        isPreferred = isPreferred,
        score = scoreOf(channel, conflicts),
        consequences = consequences.toPersistentList(),
        conflicts = conflicts.toPersistentList(),
        origin = origin,
        sameLinePatch = sameLinePatch,
    )
}

fun scoreOf(channel: VersionChannel, conflicts: List<ConflictSignal>): OfferScore {
    if (conflicts.any { it.severity == Severity.Blocker }) return OfferScore.DoNot
    return when (channel) {
        VersionChannel.Stable -> {
            when {
                conflicts.any { it.severity == Severity.High } -> OfferScore.Risky
                conflicts.any { it.severity == Severity.Caution } -> OfferScore.Recommended
                else -> OfferScore.Safe
            }
        }
        VersionChannel.ReleaseCandidate, VersionChannel.Beta -> OfferScore.Risky
        VersionChannel.Alpha, VersionChannel.Snapshot -> OfferScore.DoNot
    }
}

fun consequencesFor(
    channel: VersionChannel,
    current: MavenVersion?,
    currentChannel: VersionChannel?,
    target: MavenVersion,
    sameLinePatch: MavenVersion?,
    conflicts: List<ConflictSignal>,
    origin: MetadataOrigin,
    dependency: DeclaredDependency,
): List<Consequence> {
    val items = mutableListOf<Consequence>()
    if (dependency.isLocalArtifact) {
        val kind = when (dependency.localKind) {
            LocalArtifactKind.Aar -> "AAR"
            else -> "JAR"
        }
        items += Consequence(
            id = ConsequenceId.LocalArtifact,
            args = persistentListOf(kind, dependency.localFileName ?: dependency.coordinates.artifact, target.raw),
        )
    }
    when (channel) {
        VersionChannel.Stable -> {
            items += Consequence(ConsequenceId.Stable)
            if (conflicts.any { it.type == ConflictType.MajorBump }) {
                items += Consequence(ConsequenceId.Major)
            }
            if (sameLinePatch != null && sameLinePatch.raw != target.raw) {
                items += Consequence(ConsequenceId.SameLinePatch, persistentListOf(sameLinePatch.raw))
            }
            if (currentChannel != null && currentChannel != VersionChannel.Stable && current != null && current > target) {
                items += Consequence(ConsequenceId.DowngradeToStable, persistentListOf(current.raw, target.raw))
            }
        }
        VersionChannel.ReleaseCandidate -> items += Consequence(ConsequenceId.Rc)
        VersionChannel.Beta -> items += Consequence(ConsequenceId.Beta)
        VersionChannel.Alpha, VersionChannel.Snapshot -> items += Consequence(ConsequenceId.Alpha)
    }
    if (origin.kind == MetadataOriginKind.ProjectProxy) {
        items += Consequence(ConsequenceId.DataFromProxy)
    }
    return items
}
