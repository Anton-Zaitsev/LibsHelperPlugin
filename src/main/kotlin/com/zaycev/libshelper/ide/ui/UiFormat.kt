package com.zaycev.libshelper.ide.ui

import com.zaycev.libshelper.core.auth.RepositoryAuthScheme
import com.zaycev.libshelper.core.model.AdvisorErrorKind
import com.zaycev.libshelper.core.model.ConflictSignal
import com.zaycev.libshelper.core.model.ConflictType
import com.zaycev.libshelper.core.model.ConnectStatus
import com.zaycev.libshelper.core.model.Consequence
import com.zaycev.libshelper.core.model.ConsequenceId
import com.zaycev.libshelper.core.model.MetadataOriginKind
import com.zaycev.libshelper.core.model.OfferScore
import com.zaycev.libshelper.core.model.RepositoryKind
import com.zaycev.libshelper.core.model.RepositoryScope
import com.zaycev.libshelper.core.model.RepositoryType
import com.zaycev.libshelper.core.model.SearchRole
import com.zaycev.libshelper.core.model.UpdateAdvice
import com.zaycev.libshelper.core.model.VersionChannel
import com.zaycev.libshelper.ide.i18n.msg

enum class StatusKind { Outdated, Current, Alpha, Beta, Rc, Snapshot }

internal data class StatusLabel(
    val text: String,
    val kind: StatusKind,
)

internal fun statusOf(advice: UpdateAdvice): StatusLabel = when {
    advice.isOutdated -> {
        val target = advice.preferredStable?.version?.raw
            ?: advice.latestRc?.version?.raw
            ?: advice.latestBeta?.version?.raw
            ?: advice.latestAlpha?.version?.raw
        StatusLabel(target ?: msg("status.outdated"), StatusKind.Outdated)
    }
    advice.currentChannel == VersionChannel.Alpha -> StatusLabel(msg("status.alpha"), StatusKind.Alpha)
    advice.currentChannel == VersionChannel.Beta -> StatusLabel(msg("status.beta"), StatusKind.Beta)
    advice.currentChannel == VersionChannel.ReleaseCandidate -> StatusLabel(msg("status.rc"), StatusKind.Rc)
    advice.currentChannel == VersionChannel.Snapshot -> StatusLabel(msg("status.snapshot"), StatusKind.Snapshot)
    else -> StatusLabel(msg("status.current"), StatusKind.Current)
}

internal fun channelLabel(channel: VersionChannel): String = when (channel) {
    VersionChannel.Stable -> msg("channel.stable")
    VersionChannel.ReleaseCandidate -> msg("channel.rc")
    VersionChannel.Beta -> msg("channel.beta")
    VersionChannel.Alpha -> msg("channel.alpha")
    VersionChannel.Snapshot -> msg("channel.snapshot")
    VersionChannel.Dev -> msg("channel.dev")
}

internal fun scoreLabel(score: OfferScore): String = when (score) {
    OfferScore.Safe -> msg("score.safe")
    OfferScore.Recommended -> msg("score.recommended")
    OfferScore.Risky -> msg("score.risky")
    OfferScore.DoNot -> msg("score.avoid")
}

internal fun consequenceTitle(item: Consequence): String = when (item.id) {
    ConsequenceId.LocalArtifact -> msg("consequence.local.title", item.args.getOrElse(0) { "JAR" })
    ConsequenceId.Stable -> msg("consequence.stable.title")
    ConsequenceId.Major -> msg("consequence.major.title")
    ConsequenceId.SameLinePatch -> msg("consequence.patch.title")
    ConsequenceId.DowngradeToStable -> msg("consequence.downgrade.title")
    ConsequenceId.Rc -> msg("consequence.rc.title")
    ConsequenceId.Beta -> msg("consequence.beta.title")
    ConsequenceId.Alpha -> msg("consequence.alpha.title")
    ConsequenceId.DataFromProxy -> msg("consequence.proxy.title")
}

internal fun consequenceDetail(item: Consequence): String = when (item.id) {
    ConsequenceId.LocalArtifact -> msg(
        "consequence.local.detail",
        item.args.getOrElse(1) { "" },
        item.args.getOrElse(2) { "" },
    )
    ConsequenceId.Stable -> msg("consequence.stable.detail")
    ConsequenceId.Major -> msg("consequence.major.detail")
    ConsequenceId.SameLinePatch -> msg("consequence.patch.detail", item.args.firstOrNull().orEmpty())
    ConsequenceId.DowngradeToStable -> msg(
        "consequence.downgrade.detail",
        item.args.getOrElse(0) { "" },
        item.args.getOrElse(1) { "" },
    )
    ConsequenceId.Rc -> msg("consequence.rc.detail")
    ConsequenceId.Beta -> msg("consequence.beta.detail")
    ConsequenceId.Alpha -> msg("consequence.alpha.detail")
    ConsequenceId.DataFromProxy -> msg("consequence.proxy.detail")
}

internal fun conflictText(signal: ConflictSignal): String {
    val args = signal.args
    fun at(index: Int): String = args.getOrElse(index) { "" }
    return when (signal.type) {
        ConflictType.MajorBump -> msg("conflict.majorBump", at(0), at(1))
        ConflictType.BomAlignment -> msg("conflict.bomAlignment", at(0), at(1))
        ConflictType.SharedVersionRef -> msg("conflict.sharedVersion", at(0), at(1))
        ConflictType.Relocation -> msg("conflict.relocation", at(0))
        ConflictType.KotlinCompose -> msg("conflict.kotlinCompose", at(0), at(1))
        ConflictType.SdkLevel -> msg("conflict.sdkLevel", at(0), at(1), at(2), at(3))
        ConflictType.RepositoryGap -> if (at(0) == "proxy") {
            msg("conflict.repoGap.proxy")
        } else {
            msg("conflict.repoGap.official")
        }
        ConflictType.TransitiveOverride -> msg("conflict.transitive")
        ConflictType.FamilyMix -> if (at(0) == "okhttp") {
            msg("conflict.family.okhttp")
        } else {
            msg("conflict.family.guava")
        }
        ConflictType.MixedFamilySharedRef -> msg("conflict.mixedFamily", at(0))
    }
}

internal fun originText(kind: MetadataOriginKind?): String = when (kind) {
    MetadataOriginKind.OfficialDirect -> msg("origin.official")
    MetadataOriginKind.OfficialViaHttpProxy -> msg("origin.officialProxy")
    MetadataOriginKind.ProjectProxy -> msg("origin.projectProxy")
    null -> msg("origin.none")
}

internal fun authSchemeLabel(scheme: RepositoryAuthScheme): String = when (scheme) {
    RepositoryAuthScheme.None -> msg("auth.scheme.none")
    RepositoryAuthScheme.Basic -> msg("auth.scheme.basic")
    RepositoryAuthScheme.Bearer -> msg("auth.scheme.bearer")
    RepositoryAuthScheme.Header -> msg("auth.scheme.header")
}

internal fun kindLabel(kind: RepositoryKind): String = when (kind) {
    RepositoryKind.Official -> msg("kind.official")
    RepositoryKind.ProxyMirror -> msg("kind.proxy")
    RepositoryKind.Private -> msg("kind.private")
}

internal fun typeLabel(type: RepositoryType): String = when (type) {
    RepositoryType.Google -> msg("type.google")
    RepositoryType.MavenCentral -> msg("type.central")
    RepositoryType.PluginPortal -> msg("type.plugins")
    RepositoryType.Maven -> msg("type.maven")
    RepositoryType.FlatDir -> msg("type.flatDir")
    RepositoryType.MavenLocal -> msg("type.mavenLocal")
}

internal fun scopeLabel(scope: RepositoryScope): String = when (scope) {
    RepositoryScope.Dependency -> msg("scope.dependency")
    RepositoryScope.Plugin -> msg("scope.plugin")
}

internal fun statusLabel(status: ConnectStatus): String = when (status) {
    ConnectStatus.Unknown -> msg("connect.unknown")
    ConnectStatus.Online -> msg("connect.online")
    ConnectStatus.Timeout -> msg("connect.timeout")
    ConnectStatus.Unauthorized -> msg("connect.unauthorized")
    ConnectStatus.Forbidden -> msg("connect.forbidden")
    ConnectStatus.Unreachable -> msg("connect.unreachable")
}

internal fun roleLabel(role: SearchRole): String = when (role) {
    SearchRole.OfficialSource -> msg("role.official")
    SearchRole.ProjectProxyFallback -> msg("role.proxy")
    SearchRole.SkippedLocal -> msg("role.local")
}

internal fun connectMessage(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    if (raw == "project_mirror") return msg("connect.mirror")
    return networkError(raw)
}

internal fun progressOutcome(raw: String?): String {
    if (raw.isNullOrBlank()) return " "
    return when {
        raw == "project_cache" -> msg("progress.project_cache")
        raw == "inventory_ready" -> msg("progress.inventory_ready")
        raw == "requesting" -> msg("progress.requesting")
        raw == "local_file" -> msg("progress.local_file")
        raw == "done" -> msg("progress.done")
        raw == "cache" -> msg("progress.cache")
        raw == "ok_http_proxy" -> msg("progress.ok_http_proxy")
        raw == "cache_proxy" -> msg("progress.cache_proxy")
        raw == "ok_proxy" -> msg("progress.ok_proxy")
        raw == "error" -> msg("progress.error")
        raw.startsWith("ok ") -> msg("progress.http.ok", raw.removePrefix("ok ").trim())
        else -> networkError(raw)
    }
}

internal fun lookupErrorText(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return when {
        raw.startsWith("local:") -> {
            val parts = raw.split(":")
            msg("lookup.local", parts.getOrElse(1) { "JAR" }, parts.drop(2).joinToString(":"))
        }
        raw == "catalog_silent" -> msg("lookup.noAnswer")
        raw == "lookup_unknown" -> msg("lookup.unknown")
        else -> networkError(raw)
    }
}

internal fun errorByKind(kind: AdvisorErrorKind, message: String?): String = when (kind) {
    AdvisorErrorKind.NotGradle -> if (message == "no_root") msg("error.noProjectRoot") else (message ?: msg("error.notGradle"))
    AdvisorErrorKind.CatalogParse -> msg("banner.catalogParse.body", technical(message))
    AdvisorErrorKind.Unauthorized -> msg("error.unauthorized", technical(message))
    AdvisorErrorKind.ProxyOnly -> message.orEmpty().ifBlank { msg("banner.proxyOnly") }
    AdvisorErrorKind.Network -> msg("error.analysis", networkError(message))
}

internal fun networkError(raw: String?, error: Throwable? = null): String {
    val lower = buildString {
        append(raw.orEmpty())
        append(' ')
        append(error?.javaClass?.name.orEmpty())
        append(' ')
        append(error?.cause?.message.orEmpty())
    }.lowercase()
    return when {
        lower.isBlank() -> msg("error.unknownNetwork")
        raw == "kotlin_mismatch" || "nosuchmethod" in lower || "fromrawvalue" in lower || "kotlin.time" in lower || "linkageerror" in lower ->
            msg("error.kotlinMismatch")
        raw?.startsWith("timeout:") == true -> msg("lookup.timeout", raw.substringAfter(':'))
        raw?.startsWith("unauthorized:") == true -> msg("lookup.unauthorized", raw.substringAfter(':'))
        raw == "forbidden" || "403" in lower || "forbidden" in lower -> msg("error.http403")
        raw == "not_found" || "404" in lower -> msg("error.http404")
        raw?.startsWith("http:") == true -> msg("lookup.http", raw.substringAfter(':'))
        "401" in lower || "unauthorized" in lower -> msg("error.http401")
        "timed" in lower || "timeout" in lower -> msg("error.timeout")
        "unknownhost" in lower || "unable to resolve" in lower -> msg("error.unknownHost")
        "connection refused" in lower -> msg("error.connectionRefused")
        "network" in lower -> msg("error.network")
        else -> msg("error.generic", technical(raw.orEmpty()))
    }
}

internal fun technical(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    return trimmed.ifEmpty { msg("error.noDetails") }
}

internal fun catalogParseBody(detail: String): String = msg("banner.catalogParse.body", technical(detail))
