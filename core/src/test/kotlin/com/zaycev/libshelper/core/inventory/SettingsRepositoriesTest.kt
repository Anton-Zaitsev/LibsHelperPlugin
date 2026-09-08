package com.zaycev.libshelper.core.inventory

import com.zaycev.libshelper.core.model.RepositoryScope
import com.zaycev.libshelper.core.model.RepositoryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsRepositoriesTest {
    private val settings = """
        pluginManagement {
            includeBuild("build-logic")
            repositories {
                maven("https://nexus.company.local/repository/maven-proxy-google/")
                maven("https://nexus.company.local/repository/maven-proxy-gradle-plugins/")
                maven("https://nexus.company.local/repository/maven-proxy/")
                maven("https://nexus.company.local/repository/maven-gitverse-proxy/")
                maven("https://nexus.company.local/repository/maven-proxy-jitpack/")
                maven("https://nexus.company.local/repository/redirector-kotlinlang-org/kxrpc-grpc")
            }
        }
        plugins {
            id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
        }
        buildCache {
            remote<HttpBuildCache> {
                url = uri("http://gradle-cache.company.local/cache/")
            }
        }
        dependencyResolutionManagement {
            repositories {
                maven("https://nexus.company.local/repository/maven-proxy-google/")
                exclusiveContent {
                    forRepository {
                        maven("https://nexus.company.local/repository/maven-gitverse-proxy/")
                    }
                    filter {
                        includeGroup("org.jetbrains.androidx.navigationevent")
                        includeGroup("org.jetbrains.skiko")
                        includeGroup("io.nlopez.compose.rules")
                        includeGroup("build.buf")
                    }
                }
                maven("https://nexus.company.local/repository/maven-proxy/")
                maven("https://nexus.company.local/repository/maven-gitverse-proxy/")
                maven("https://nexus.company.local/repository/maven-googleapis-proxy/")
                maven("https://nexus.company.local/repository/maven-proxy-jitpack/")
                maven("https://nexus.company.local/repository/redirector-kotlinlang-org/kxrpc-grpc")
            }
        }
    """.trimIndent()

    @Test
    fun splitsPluginManagementAndDrmAndKeepsExclusiveContent() {
        val repos = parseSettingsRepositories(settings)
        assertFalse(repos.any { it.url.contains("gradle-cache") })

        val plugins = repos.filter { it.scope == RepositoryScope.Plugin }
        val deps = repos.filter { it.scope == RepositoryScope.Dependency }
        assertTrue(plugins.any { it.type == RepositoryType.Google })
        assertTrue(plugins.any { it.type == RepositoryType.PluginPortal })
        assertTrue(plugins.any { it.type == RepositoryType.MavenCentral && it.url.contains("maven-proxy/") })
        assertTrue(plugins.none { it.exclusive })

        val exclusive = deps.single { it.exclusive }
        assertEquals(
            setOf(
                "org.jetbrains.androidx.navigationevent",
                "org.jetbrains.skiko",
                "io.nlopez.compose.rules",
                "build.buf",
            ),
            exclusive.includeGroups,
        )
        assertTrue(exclusive.url.contains("maven-gitverse-proxy"))
        assertTrue(deps.any { !it.exclusive && it.url.contains("maven-gitverse-proxy") })
        assertTrue(deps.any { it.type == RepositoryType.Google && it.url.contains("maven-proxy-google") })
        assertTrue(deps.any { it.type == RepositoryType.Google && it.url.contains("googleapis") })
        assertTrue(deps.any { it.url.contains("jitpack") })
    }
}
