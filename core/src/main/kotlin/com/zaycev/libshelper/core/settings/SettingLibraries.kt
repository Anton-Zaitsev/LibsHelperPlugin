package com.zaycev.libshelper.core.settings

import com.zaycev.libshelper.core.inventory.CatalogVersionSite
import com.zaycev.libshelper.core.inventory.catalogVersionSites
import com.zaycev.libshelper.core.model.ChannelOffer
import com.zaycev.libshelper.core.model.Coordinates
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.DependencySource
import com.zaycev.libshelper.core.model.LibraryAdvice
import com.zaycev.libshelper.core.model.MetadataOrigin
import com.zaycev.libshelper.core.model.MetadataOriginKind
import com.zaycev.libshelper.core.model.OfferScore
import com.zaycev.libshelper.core.model.ProjectReport
import com.zaycev.libshelper.core.model.UpdateAdvice
import com.zaycev.libshelper.core.model.VersionCandidate
import com.zaycev.libshelper.core.model.VersionChannel
import com.zaycev.libshelper.core.versioning.MavenVersion
import kotlinx.collections.immutable.persistentListOf

fun settingLibraries(report: ProjectReport): List<LibraryAdvice> {
    val sites = catalogVersionSites(report.inventory.versionSources)
    return report.buildSettings.mapNotNull { advice -> advice.toLibrary(sites[advice.key]) }
}

private fun BuildSettingAdvice.toLibrary(site: CatalogVersionSite?): LibraryAdvice? {
    if (role != BuildSettingRole.CompileSdk &&
        role != BuildSettingRole.TargetSdk &&
        role != BuildSettingRole.Ndk
    ) {
        return null
    }
    val tracked = trackedVersion ?: return null
    if (current.isBlank()) return null
    val outdated = suggestions.isNotEmpty()
    val origin = MetadataOrigin(MetadataOriginKind.OfficialDirect, ANDROID_REPOSITORY)
    val offer = if (outdated) {
        ChannelOffer(
            channel = VersionChannel.Stable,
            version = MavenVersion.parse(tracked),
            isPreferred = true,
            score = OfferScore.Recommended,
            consequences = persistentListOf(),
            conflicts = persistentListOf(),
            origin = origin,
        )
    } else {
        null
    }
    val dependency = DeclaredDependency(
        coordinates = Coordinates(role.listGroup(), key),
        requestedVersion = current,
        catalogAlias = key,
        versionRef = key,
        configuration = role.name,
        module = ":",
        source = DependencySource.Toml,
        catalogPath = site?.path,
        catalogLine = site?.line,
        catalogVersionLine = site?.line,
    )
    return LibraryAdvice(
        advice = UpdateAdvice(
            dependency = dependency,
            current = MavenVersion.parse(current),
            currentChannel = VersionChannel.Stable,
            isOutdated = outdated,
            preferredStable = offer,
            latestRc = null,
            latestBeta = null,
            latestAlpha = null,
            candidates = listOfNotNull(
                offer?.let { VersionCandidate(it.version, VersionChannel.Stable, origin) },
            ),
        ),
    )
}

private fun BuildSettingRole.listGroup(): String = when (this) {
    BuildSettingRole.CompileSdk -> "compileSdk"
    BuildSettingRole.TargetSdk -> "targetSdk"
    BuildSettingRole.Ndk -> "ndk"
    else -> name
}

private const val ANDROID_REPOSITORY = "https://dl.google.com/android/repository/repository2-3.xml"
