pluginManagement {
    val catalogFile = settings.layout.settingsDirectory.file("gradle/libs.versions.toml").asFile
    fun catalogVersion(key: String): String {
        val prefix = "$key = \""
        return catalogFile.readLines()
            .first { it.trimStart().startsWith(prefix) }
            .substringAfter(prefix)
            .substringBefore('"')
    }
    val foojayResolverVersion = catalogVersion("foojay-resolver")
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "org.gradle.toolchains.foojay-resolver-convention") {
                useVersion(foojayResolverVersion)
            }
        }
    }
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "LibsHelperPlugin"
include("core")
include("core:utils")
include("feature:feature-log:api")
include("feature:feature-log:impl")
include("feature:feature-analytics:api")
include("feature:feature-analytics:impl")
include("feature:feature-mcp:api")
include("feature:feature-mcp:impl")
