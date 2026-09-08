package com.zaycev.libshelper.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class MetadataOriginKind {
    OfficialDirect,
    OfficialViaHttpProxy,
    ProjectProxy,
}

