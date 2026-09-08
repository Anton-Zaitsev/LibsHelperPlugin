package com.zaycev.libshelper.ide.inlay

import com.intellij.openapi.vfs.VirtualFile

internal fun isDependencyHintFile(name: String): Boolean =
    name.endsWith(".versions.toml") ||
        name == "libs.versions.toml" ||
        name == "build.gradle.kts" ||
        name == "build.gradle" ||
        name == "settings.gradle.kts" ||
        name == "settings.gradle"

internal fun isDependencyHintFile(file: VirtualFile): Boolean = isDependencyHintFile(file.name)
