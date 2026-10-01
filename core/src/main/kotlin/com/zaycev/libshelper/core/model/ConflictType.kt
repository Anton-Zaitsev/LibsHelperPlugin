package com.zaycev.libshelper.core.model

enum class ConflictType {
    MajorBump,
    BomAlignment,
    SharedVersionRef,
    Relocation,
    KotlinCompose,
    SdkLevel,
    RepositoryGap,
    TransitiveOverride,
    FamilyMix,
    MixedFamilySharedRef,
}

