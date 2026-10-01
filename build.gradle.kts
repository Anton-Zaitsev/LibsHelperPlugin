import dev.detekt.gradle.Detekt
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.intellij.platform)
    alias(libs.plugins.detekt)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
    compilerOptions {
        optIn.add("org.jetbrains.jewel.foundation.ExperimentalJewelApi")
        allWarningsAsErrors.set(true)
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
        // Without this, Kotlin copies every platform default method into the
        // implementing class. Deprecated defaults then show up in the plugin verifier.
        freeCompilerArgs.add("-jvm-default=no-compatibility")
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
    implementation(project(":feature:feature-analytics:impl"))
    implementation(project(":feature:feature-log:impl"))
    implementation(project(":feature:feature-mcp:api"))
    compileOnly(project(":feature:feature-mcp:impl"))
    testImplementation(libs.junit.jupiter)
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly(libs.junit.platform.launcher)
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
        testFramework(TestFrameworkType.JUnit5)
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
    pluginVerification {
        ides {
            recommended()
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

tasks.register("detektAll") {
    group = "verification"
    description = "Run detekt with type resolution (including Compose rules) on all modules"
    dependsOn("detektMain")
}

val moduleBuildDirectories = objects.fileCollection().from(
    allprojects.map { it.layout.buildDirectory },
)

tasks.register<Delete>("clear") {
    group = "build"
    description = "Delete the build directory of the root project and every module"
    delete(moduleBuildDirectories)
}

tasks {
    test {
        useJUnitPlatform()
        dependsOn(":core:test")
        dependsOn(":core:utils:test")
        dependsOn(":feature:feature-log:api:test")
        dependsOn(":feature:feature-log:impl:test")
        dependsOn(":feature:feature-analytics:api:test")
        dependsOn(":feature:feature-analytics:impl:test")
        dependsOn(":feature:feature-mcp:api:test")
        dependsOn(":feature:feature-mcp:impl:test")
    }
    named("check") {
        dependsOn("detektAll")
        dependsOn(":core:check")
        dependsOn(":core:utils:check")
        dependsOn(":feature:feature-analytics:api:check")
        dependsOn(":feature:feature-analytics:impl:check")
        dependsOn(":feature:feature-log:api:check")
        dependsOn(":feature:feature-log:impl:check")
        dependsOn(":feature:feature-mcp:api:check")
        dependsOn(":feature:feature-mcp:impl:check")
        dependsOn("verifyPlugin")
    }
    named("buildPlugin") {
        dependsOn("detektAll")
    }
    withType<JavaCompile>().configureEach {
        sourceCompatibility = libs.versions.jvm.get()
        targetCompatibility = libs.versions.jvm.get()
    }
    withType<RunIdeTask>().configureEach {
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
            return@configureEach
        }
        val bundleRoot = layout.projectDirectory.dir(".intellijPlatform/mac-app").asFile.toPath()
        doFirst {
            systemProperty("idea.home.path", macOsAppContents(platformPath, bundleRoot).toString())
        }
    }
}

private fun macOsAppContents(platformHome: Path, bundleRoot: Path): Path {
    val parentName = platformHome.parent?.fileName?.toString()
    if (parentName != null && parentName.endsWith(".app")) {
        return platformHome.toAbsolutePath().normalize()
    }
    val contents = bundleRoot.resolve("idea.app").resolve("Contents")
    Files.createDirectories(contents.parent)
    val desired = platformHome.toAbsolutePath().normalize()
    val current = if (contents.isSymbolicLink()) contents.readSymbolicLink().toAbsolutePath().normalize() else null
    if (current != desired) {
        if (Files.exists(contents, LinkOption.NOFOLLOW_LINKS)) {
            check(contents.isSymbolicLink()) { "Refusing to replace a non-symlink at $contents" }
            Files.delete(contents)
        }
        Files.createSymbolicLink(contents, desired)
    }
    return contents.toAbsolutePath().normalize()
}

evaluationDependsOn(":feature:feature-mcp:impl")
val mcpShadowJar = project(":feature:feature-mcp:impl").tasks.named("shadowJar")
dependencies {
    runtimeOnly(files(mcpShadowJar))
}
