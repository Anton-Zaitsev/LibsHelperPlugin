import dev.detekt.gradle.Detekt

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
    compilerOptions {
        allWarningsAsErrors.set(true)
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

dependencies {
    testImplementation(kotlin("test"))
    detektPlugins(libs.detekt.compose)
    detektPlugins(libs.detekt.ktlint.wrapper)
}

tasks.test {
    useJUnitPlatform()
}

rootProject.tasks.named("detektAll") {
    dependsOn(tasks.named("detektMain"))
}
