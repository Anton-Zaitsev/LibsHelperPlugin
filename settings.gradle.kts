import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    // Settings plugins cannot use alias(libs.plugins.*); versions come from gradle/libs.versions.toml.
    val catalogFile = settings.layout.settingsDirectory.file("gradle/libs.versions.toml").asFile
    fun catalogVersion(key: String): String {
        val prefix = "$key = \""
        return catalogFile.readLines()
            .first { it.trimStart().startsWith(prefix) }
            .substringAfter(prefix)
            .substringBefore('"')
    }
    val foojayResolverVersion = catalogVersion("foojay-resolver")
    val intellijPlatformPluginVersion = catalogVersion("intellij-platform")
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "org.gradle.toolchains.foojay-resolver-convention" -> useVersion(foojayResolverVersion)
                "org.jetbrains.intellij.platform.settings" -> useVersion(intellijPlatformPluginVersion)
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
    id("org.jetbrains.intellij.platform.settings")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}

rootProject.name = "LibsHelperPlugin"
include("core")
