package com.zaycev.libshelper.core.model

data class LibraryLinks(
    val mavenCentral: String,
    val mvnRepository: String,
    val googleMaven: String? = null,
    val github: String? = null,
) {
    companion object {
        val none: LibraryLinks = LibraryLinks(
            mavenCentral = "https://central.sonatype.com/",
            mvnRepository = "https://mvnrepository.com/",
        )
    }
}

