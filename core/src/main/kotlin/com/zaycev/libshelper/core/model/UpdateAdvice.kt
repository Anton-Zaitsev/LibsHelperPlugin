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
)

