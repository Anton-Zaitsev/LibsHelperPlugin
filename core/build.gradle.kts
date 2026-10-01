import dev.detekt.gradle.Detekt
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metro)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

val generatePluginVersion = tasks.register<GeneratePluginVersionTask>("generatePluginVersion") {
    pluginVersion.set(providers.gradleProperty("pluginVersion"))
    outputDirectory.set(layout.buildDirectory.dir("generated/sources/pluginVersion/kotlin"))
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
    compilerOptions {
        allWarningsAsErrors.set(true)
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
    }
    sourceSets.named("main") {
        kotlin.srcDir(generatePluginVersion.flatMap { it.outputDirectory })
    }
}

kover {
    reports {
        total {
            verify {
                rule {
                    minBound(90)
                }
            }
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    ignoreFailures = false
    // detekt 2.0.0-alpha can crash with parallel=true: https://github.com/detekt/detekt/issues/9530
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

dependencies {
    api(project(":core:utils"))
    api(project(":feature:feature-log:api"))
    api(project(":feature:feature-analytics:api"))
    compileOnly(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.collections.immutable)
    testImplementation(kotlin("test"))
    testImplementation(project(":feature:feature-log:impl"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    detektPlugins(libs.detekt.compose)
    detektPlugins(libs.detekt.ktlint.wrapper)
}

tasks.test {
    useJUnitPlatform()
}

rootProject.tasks.named("detektAll") {
    dependsOn(tasks.named("detektMain"))
}

abstract class GeneratePluginVersionTask : DefaultTask() {
    @get:Input
    abstract val pluginVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val file = outputDirectory.file("com/zaycev/libshelper/core/PluginVersion.kt").get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package com.zaycev.libshelper.core\n\nconst val PLUGIN_VERSION: String = \"${pluginVersion.get()}\"\n",
        )
    }
}
