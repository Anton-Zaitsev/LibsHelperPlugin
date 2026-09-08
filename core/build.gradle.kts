import dev.detekt.gradle.Detekt

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metro)
    alias(libs.plugins.detekt)
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
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
    compileOnly(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.collections.immutable)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    detektPlugins(libs.detekt.compose)
    detektPlugins(libs.detekt.ktlint.wrapper)
}

tasks.test {
    useJUnitPlatform()
}

rootProject.tasks.named("detektAll") {
    dependsOn(tasks.named("detektMain"))
}
