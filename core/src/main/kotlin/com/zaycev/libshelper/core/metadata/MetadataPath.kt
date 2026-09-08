package com.zaycev.libshelper.core.metadata

fun metadataPath(group: String, artifact: String): String =
    group.replace('.', '/') + "/$artifact/maven-metadata.xml"
