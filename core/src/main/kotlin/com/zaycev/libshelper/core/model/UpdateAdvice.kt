package com.zaycev.libshelper.core.model

import com.zaycev.libshelper.core.versioning.MavenVersion

data class UpdateAdvice(
    val dependency: DeclaredDependency,
    val current: MavenVersion?,
    val currentChannel: VersionChannel?,
    val isOutdated: Boolean,
    val preferredStable: ChannelOffer?,
    val latestRc: ChannelOffer?,
    val latestBeta: ChannelOffer?,
    val latestAlpha: ChannelOffer?,
    val candidates: List<VersionCandidate> = emptyList(),
    val sharedConflicts: List<ConflictSignal> = emptyList(),
)

data class DisplayTarget(
    val version: String,
    val channel: VersionChannel,
)

fun UpdateAdvice.displayTarget(): DisplayTarget? {
    val stable = preferredStable
    if (stable != null) return DisplayTarget(stable.version.raw, VersionChannel.Stable)
    val currentVersion = current ?: return null
    val offer = when (currentChannel) {
        VersionChannel.Alpha -> latestAlpha
        VersionChannel.Beta -> latestBeta
        VersionChannel.ReleaseCandidate -> latestRc
        else -> null
    } ?: return null
    if (offer.version <= currentVersion) return null
    return DisplayTarget(offer.version.raw, offer.channel)
}

