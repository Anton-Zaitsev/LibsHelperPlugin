import dev.detekt.gradle.Detekt

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.process.CommandLineArgumentProvider

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
    compilerOptions {
        allWarningsAsErrors.set(true)
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
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

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("mcp-runtime")
    relocate("kotlinx.coroutines", "com.zaycev.libshelper.mcp.shaded.kotlinx.coroutines")
    mergeServiceFiles()
    dependencies {
        exclude(project(":feature:feature-mcp:api"))
        exclude(project(":core:utils"))
        exclude(dependency("org.jetbrains.kotlin:.*"))
        exclude(dependency("org.jetbrains.kotlinx:kotlinx-serialization-.*"))
        exclude(dependency("org.slf4j:.*"))
        exclude(dependency("org.jetbrains:annotations"))
    }
}

val platformCoroutines = configurations.create("platformCoroutines") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    add(platformCoroutines.name, "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api(project(":feature:feature-mcp:api"))
    api(project(":core:utils"))
    implementation(libs.mcp.sdk.server)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.status.pages)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mcp.sdk.client)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit.platform.launcher)
    detektPlugins(libs.detekt.compose)
    detektPlugins(libs.detekt.ktlint.wrapper)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.named<ShadowJar>("shadowJar"))
    jvmArgumentProviders.add(objects.newInstance(McpPlatformTestArguments::class.java).apply {
        shadowJar.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
        platformCoroutinesJar.fileProvider(provider { platformCoroutines.singleFile })
        runtimeJars.from(configurations.testRuntimeClasspath)
        apiClasses.set(project(":feature:feature-mcp:api").layout.buildDirectory.dir("classes/kotlin/main"))
        utilsClasses.set(project(":core:utils").layout.buildDirectory.dir("classes/kotlin/main"))
    })
}

abstract class McpPlatformTestArguments : CommandLineArgumentProvider {
    @get:Classpath
    abstract val shadowJar: RegularFileProperty

    @get:Classpath
    abstract val platformCoroutinesJar: RegularFileProperty

    @get:Classpath
    abstract val runtimeJars: ConfigurableFileCollection

    @get:Classpath
    abstract val apiClasses: DirectoryProperty

    @get:Classpath
    abstract val utilsClasses: DirectoryProperty

    override fun asArguments(): Iterable<String> {
        val runtime = runtimeJars.files
        fun jar(predicate: (java.io.File) -> Boolean) = runtime.first(predicate).absolutePath
        return listOf(
            "-Dmcp.shadowJar=${shadowJar.get().asFile.absolutePath}",
            "-Dmcp.platformCoroutines=${platformCoroutinesJar.get().asFile.absolutePath}",
            "-Dmcp.stdlib=${jar { it.name.matches(Regex("kotlin-stdlib-\\d.*\\.jar")) }}",
            "-Dmcp.slf4j=${jar { it.name.startsWith("slf4j-api-") }}",
            "-Dmcp.serializationCore=${jar { it.name.startsWith("kotlinx-serialization-core-jvm") }}",
            "-Dmcp.serializationJson=${jar { it.name.startsWith("kotlinx-serialization-json-jvm") }}",
            "-Dmcp.apiClasses=${apiClasses.get().asFile.absolutePath}",
            "-Dmcp.utilsClasses=${utilsClasses.get().asFile.absolutePath}",
        )
    }
}

rootProject.tasks.named("detektAll") {
    dependsOn(tasks.named("detektMain"))
}
