package com.zaycev.libshelper.core.metadata

import com.zaycev.libshelper.core.cache.InMemoryMetadataCache
import com.zaycev.libshelper.core.model.Coordinates
import com.zaycev.libshelper.core.model.DeclaredRepository
import com.zaycev.libshelper.core.model.MetadataOriginKind
import com.zaycev.libshelper.core.model.RepositoryKind
import com.zaycev.libshelper.core.model.RepositoryScope
import com.zaycev.libshelper.core.model.RepositoryType
import com.zaycev.libshelper.core.network.FakeMetadataGateway
import com.zaycev.libshelper.core.network.HttpFailure
import com.zaycev.libshelper.core.network.HttpGetResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf

class LookupMetadataTest {
    private val xml = requireNotNull(javaClass.getResource("/fixtures/okhttp-metadata.xml")).readText()
    private val coords = Coordinates("com.squareup.okhttp3", "okhttp")
    private val path = metadataPath(coords.group, coords.artifact)
    private val official = "https://repo1.maven.org/maven2/$path"
    private val proxyUrl = "https://nexus.company.local/repository/maven-public/$path"
    private val proxyRepo = DeclaredRepository(
        name = "nexus",
        url = "https://nexus.company.local/repository/maven-public/",
        type = RepositoryType.Maven,
        kind = RepositoryKind.ProxyMirror,
        scope = RepositoryScope.Dependency,
    )

    @Test
    fun prefersOfficialDirectOverProxy() = runTest {
        val gateway = FakeMetadataGateway(
            mapOf(
                official to HttpGetResult.Success(xml, 200, 12),
                proxyUrl to HttpGetResult.Success(xml.replace("5.0.0", "4.12.2"), 200, 9),
            ),
        )
        val lookup = lookupMetadata(coords, false, listOf(proxyRepo), null, gateway)
        val resolved = requireNotNull(lookup.resolved)
        assertEquals(MetadataOriginKind.OfficialDirect, resolved.origin.kind)
        assertTrue(resolved.metadata.versions.contains("5.0.0"))
        assertEquals(false, resolved.usedProxyFallback)
    }

    @Test
    fun fallsBackToProjectProxyWhenOfficialFails() = runTest {
        val timeout = { url: String ->
            HttpGetResult.Failure(HttpFailure.Unreachable(url, "timeout"), 8)
        }
        val gateway = FakeMetadataGateway(
            mapOf(
                official to timeout(official),
                "https://dl.google.com/dl/android/maven2/$path" to timeout("https://dl.google.com/dl/android/maven2/$path"),
                "https://maven.google.com/$path" to timeout("https://maven.google.com/$path"),
                proxyUrl to HttpGetResult.Success(xml, 200, 15),
            ),
        )
        val lookup = lookupMetadata(coords, false, listOf(proxyRepo), null, gateway)
        assertEquals(MetadataOriginKind.ProjectProxy, lookup.resolved?.origin?.kind)
        assertEquals(true, lookup.resolved?.usedProxyFallback)
        assertEquals("timeout", lookup.officialError)
    }

    @Test
    fun usesMetadataCacheOnSecondLookup() = runTest {
        val cache = InMemoryMetadataCache()
        val gateway = FakeMetadataGateway(mapOf(official to HttpGetResult.Success(xml, 200, 20)))
        val first = lookupMetadata(coords, false, emptyList(), null, gateway, cache)
        val second = lookupMetadata(coords, false, emptyList(), null, gateway, cache)
        assertEquals(false, first.resolved?.fromCache)
        assertEquals(true, second.resolved?.fromCache)
        assertEquals(first.resolved?.metadata?.versions, second.resolved?.metadata?.versions)
    }

    @Test
    fun parsesAllVersionsAndIgnoresLatestTag() {
        val metadata = parseMavenMetadata(xml)
        assertEquals("5.2.0-alpha03", metadata.latestTag)
        assertTrue(metadata.versions.contains("5.0.0"))
        assertTrue(metadata.versions.contains("4.12.2"))
    }

    @Test
    fun exclusiveContent_skipsOtherProjectProxies() = runTest {
        val skiko = Coordinates("org.jetbrains.skiko", "skiko")
        val skikoPath = metadataPath(skiko.group, skiko.artifact)
        val timeout = { url: String ->
            HttpGetResult.Failure(HttpFailure.Unreachable(url, "timeout"), 8)
        }
        val gitverse = "https://nexus.company.local/repository/maven-gitverse-proxy/"
        val googleProxy = "https://nexus.company.local/repository/maven-proxy-google/"
        val mavenProxy = "https://nexus.company.local/repository/maven-proxy/"
        val repos = listOf(
            repo("maven-proxy-google", googleProxy, RepositoryType.Google),
            repo(
                "maven-gitverse-proxy",
                gitverse,
                RepositoryType.MavenCentral,
                exclusive = true,
                includeGroups = persistentSetOf("org.jetbrains.skiko"),
            ),
            repo("maven-proxy", mavenProxy, RepositoryType.MavenCentral),
            repo("maven-gitverse-proxy", gitverse, RepositoryType.MavenCentral),
        )
        val gateway = FakeMetadataGateway(
            mapOf(
                "https://repo1.maven.org/maven2/$skikoPath" to timeout("central"),
                "https://dl.google.com/dl/android/maven2/$skikoPath" to timeout("google"),
                "https://maven.google.com/$skikoPath" to timeout("maven.google"),
                googleProxy + skikoPath to HttpGetResult.Success(xml, 200, 4),
                mavenProxy + skikoPath to HttpGetResult.Success(xml, 200, 5),
                gitverse + skikoPath to HttpGetResult.Success(xml, 200, 6),
            ),
        )
        val lookup = lookupMetadata(skiko, false, repos, null, gateway)
        assertEquals(MetadataOriginKind.ProjectProxy, lookup.resolved?.origin?.kind)
        assertTrue(lookup.resolved?.origin?.url?.contains("maven-gitverse-proxy") == true)
        assertFalse(gateway.requestedUrls.any { it.startsWith(googleProxy) })
        assertFalse(gateway.requestedUrls.any { it.startsWith(mavenProxy) })
    }

    @Test
    fun pluginLookup_usesPluginManagementReposOnly() = runTest {
        val plugin = Coordinates("com.gradle.develocity", "com.gradle.develocity.gradle.plugin")
        val pluginPath = metadataPath(plugin.group, plugin.artifact)
        val timeout = { url: String ->
            HttpGetResult.Failure(HttpFailure.Unreachable(url, "timeout"), 8)
        }
        val pluginProxy = "https://nexus.company.local/repository/maven-proxy-gradle-plugins/"
        val drmProxy = "https://nexus.company.local/repository/maven-proxy/"
        val gateway = FakeMetadataGateway(
            mapOf(
                "https://plugins.gradle.org/m2/$pluginPath" to timeout("portal"),
                "https://repo1.maven.org/maven2/$pluginPath" to timeout("central"),
                pluginProxy + pluginPath to HttpGetResult.Success(xml, 200, 7),
                drmProxy + pluginPath to HttpGetResult.Success(xml, 200, 3),
            ),
        )
        val lookup = lookupMetadata(
            plugin,
            true,
            listOf(
                repo("maven-proxy-gradle-plugins", pluginProxy, RepositoryType.PluginPortal, RepositoryScope.Plugin),
                repo("maven-proxy", drmProxy, RepositoryType.MavenCentral, RepositoryScope.Dependency),
            ),
            null,
            gateway,
        )
        assertTrue(lookup.resolved?.origin?.url?.contains("maven-proxy-gradle-plugins") == true)
        assertFalse(gateway.requestedUrls.any { it.startsWith(drmProxy) })
    }

    private fun repo(
        name: String,
        url: String,
        type: RepositoryType,
        scope: RepositoryScope = RepositoryScope.Dependency,
        exclusive: Boolean = false,
        includeGroups: ImmutableSet<String> = persistentSetOf(),
    ) = DeclaredRepository(
        name = name,
        url = url,
        type = type,
        kind = RepositoryKind.ProxyMirror,
        scope = scope,
        includeGroups = includeGroups,
        exclusive = exclusive,
    )
}
