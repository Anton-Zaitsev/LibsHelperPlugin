package com.zaycev.libshelper.core.model

data class Coordinates(
    val group: String,
    val artifact: String,
) {
    val key: String get() = "$group:$artifact"

    override fun toString(): String = key
}

