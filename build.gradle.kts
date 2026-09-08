import dev.detekt.gradle.Detekt
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.intellij.platform")
    alias(libs.plugins.detekt)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
    compilerOptions {
        optIn.add("org.jetbrains.jewel.foundation.ExperimentalJewelApi")
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("config/compose/stability.conf"))
}

configurations.matching { config ->
    val name = config.name
    name == "runtimeClasspath" ||
        (name.endsWith("RuntimeClasspath") && !name.contains("test", ignoreCase = true) && !name.contains("Test"))
}.configureEach {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-slf4j")
}

dependencies {
    compileOnly(libs.kotlinx.coroutines.core)
    implementation(project(":core"))
    detektPlugins(libs.detekt.compose)
    detektPlugins(libs.detekt.ktlint.wrapper)
    intellijPlatform {
        intellijIdea(libs.versions.intellij.idea.get())
        bundledPlugin("com.intellij.java")
        bundledPlugin("Git4Idea")
        bundledPlugin("org.jetbrains.plugins.gradle")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("org.intellij.groovy")
        bundledPlugin("org.toml.lang")
        testFramework(TestFrameworkType.Platform)
        composeUI()
    }
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        id = providers.gradleProperty("pluginId")
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
        vendor {
            name = providers.gradleProperty("pluginVendorName")
            email = providers.gradleProperty("pluginVendorEmail")
            url = providers.gradleProperty("pluginVendorUrl")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
        changeNotes.set(
            providers.fileContents(layout.projectDirectory.file("CHANGELOG.html")).asText,
        )
    }
    publishing {
        token = providers.gradleProperty("intellijPlatformPublishingToken")
    }
}

detekt {
    buildUponDefaultConfig = true
    ignoreFailures = false
    parallel = false
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    basePath.set(rootProject.projectDir)
}

tasks.withType<Detekt>().configureEach {
    reports {
        html.required.set(true)
        sarif.required.set(true)
    }
}

tasks.register("detektAll") {
    group = "verification"
    description = "Run detekt with type resolution (including Compose rules) on all modules"
    dependsOn("detektMain")
}

tasks {
    test {
        useJUnitPlatform()
        dependsOn(":core:test")
    }
    named("check") {
        dependsOn(":core:check")
    }
    named("buildPlugin") {
        dependsOn("detektAll")
    }
    withType<JavaCompile>().configureEach {
        sourceCompatibility = libs.versions.jvm.get()
        targetCompatibility = libs.versions.jvm.get()
    }
}
