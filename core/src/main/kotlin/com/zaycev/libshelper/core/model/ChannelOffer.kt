package com.zaycev.libshelper.core.model

import com.zaycev.libshelper.core.versioning.MavenVersion
import kotlinx.collections.immutable.ImmutableList

data class ChannelOffer(
    val channel: VersionChannel,
    val version: MavenVersion,
    val isPreferred: Boolean,
    val score: OfferScore,
    val consequences: ImmutableList<Consequence>,
    val conflicts: ImmutableList<ConflictSignal>,
    val origin: MetadataOrigin,
    val sameLinePatch: MavenVersion? = null,
)
