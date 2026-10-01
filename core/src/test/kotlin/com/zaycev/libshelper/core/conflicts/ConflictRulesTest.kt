package com.zaycev.libshelper.core.conflicts

import com.zaycev.libshelper.core.model.ConflictType
import com.zaycev.libshelper.core.model.Coordinates
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.DependencySource
import com.zaycev.libshelper.core.model.MetadataOriginKind
import com.zaycev.libshelper.core.model.ProjectInventory
import com.zaycev.libshelper.core.versioning.MavenVersion
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConflictRulesTest {
    @Test
    fun everyRuleFiresForAMatchingProject() {
        val bom = dep("androidx.compose", "compose-bom", "2024.10.00", bom = true)
        val ui = dep("androidx.compose.ui", "ui", "1.7.0", ref = "compose")
        val material = dep("androidx.compose.material3", "material3", "1.3.0", ref = "compose")
        val support = dep("com.android.support", "appcompat-v7", "28.0.0")
        val androidx = dep("androidx.appcompat", "appcompat", "1.7.0")
        val compiler = dep("androidx.compose.compiler", "compiler", "1.5.0")
        val activity = dep("androidx.activity", "activity", "1.8.0")
        val okhttp3 = dep("com.squareup.okhttp3", "okhttp", "4.12.0")
        val okhttp2 = dep("com.squareup.okhttp", "okhttp", "2.7.5")
        val guava = dep("com.google.guava", "guava", "33.0.0")
        val guavaAndroid = dep("com.google.guava", "guava-android", "33.0.0")
        val firebase = dep("com.google.firebase", "firebase-common", "21.0.0")
        val inventory = inventoryOf(
            bom, ui, material, support, androidx, compiler, activity, okhttp3, okhttp2, guava, guavaAndroid, firebase,
            kotlin = "1.9.24",
            minSdk = 16,
        )
        val types = collectConflicts(
            compiler,
            MavenVersion.parse("1.5.15"),
            MavenVersion.parse("2.0.0"),
            inventory,
            MetadataOriginKind.ProjectProxy,
            proxyMissingOfficial = true,
        ).map { it.type }.toSet()
        assertTrue(ConflictType.MajorBump in types)
        assertTrue(ConflictType.BomAlignment in types)
        assertTrue(ConflictType.KotlinCompose in types)
        assertTrue(ConflictType.RepositoryGap in types)
        assertTrue(
            ConflictType.SharedVersionRef in collectConflicts(
                ui, MavenVersion.parse("1.7.0"), MavenVersion.parse("1.7.0"), inventory, MetadataOriginKind.OfficialDirect, false,
            ).map { it.type },
        )
        assertTrue(
            ConflictType.Relocation in collectConflicts(
                support, MavenVersion.parse("28.0.0"), MavenVersion.parse("28.0.0"), inventory, MetadataOriginKind.OfficialDirect, false,
            ).map { it.type },
        )
        assertTrue(
            ConflictType.SdkLevel in collectConflicts(
                activity, MavenVersion.parse("1.8.0"), MavenVersion.parse("1.10.0"), inventory, MetadataOriginKind.OfficialDirect, false,
            ).map { it.type },
        )
        assertTrue(
            ConflictType.FamilyMix in collectConflicts(
                okhttp3, MavenVersion.parse("4.12.0"), MavenVersion.parse("4.12.0"), inventory, MetadataOriginKind.OfficialDirect, false,
            ).map { it.type },
        )
        assertTrue(
            ConflictType.FamilyMix in collectConflicts(
                guava, MavenVersion.parse("33.0.0"), MavenVersion.parse("33.0.0"), inventory, MetadataOriginKind.OfficialDirect, false,
            ).map { it.type },
        )
        assertEquals("compose", bomFamilyOf(ui.coordinates))
        assertEquals("firebase", bomFamilyOf(firebase.coordinates))
        assertEquals("androidx", bomFamilyOf(Coordinates("androidx", "androidx-bom")))
        assertNull(bomFamilyOf(okhttp3.coordinates))
        assertNull(majorBump(null, MavenVersion.parse("2.0.0")))
        assertNull(kotlinRelease("nope"))
        assertEquals(1 to 9, kotlinRelease("1.9.24"))
        assertEquals(1 to 10, kotlinRelease("1.10.0"))
        assertNull(requiredMinSdk(okhttp3.coordinates, MavenVersion.parse("4.12.0")))
        assertNull(repositoryGap(false, MetadataOriginKind.OfficialDirect))
        assertNull(bomAlignment(bom, inventory))
        assertNull(sharedVersionRef(okhttp3, inventory))
        assertNull(relocation(okhttp3, inventory))
        assertNull(familyMix(firebase, inventory))
        assertNull(sdkLevel(activity, MavenVersion.parse("1.9.0"), inventory.copy(minSdk = null)))
    }

    private fun dep(
        group: String,
        artifact: String,
        version: String,
        ref: String? = null,
        bom: Boolean = false,
    ) = DeclaredDependency(
        coordinates = Coordinates(group, artifact),
        requestedVersion = version,
        versionRef = ref,
        configuration = "implementation",
        module = ":app",
        source = DependencySource.Toml,
        isBom = bom,
    )

    private fun inventoryOf(
        vararg dependencies: DeclaredDependency,
        kotlin: String?,
        minSdk: Int?,
    ) = ProjectInventory(
        modules = persistentListOf(":app"),
        dependencies = dependencies.toList().toPersistentList(),
        repositories = persistentListOf(),
        httpProxy = null,
        kotlinVersion = kotlin,
        minSdk = minSdk,
    )
}
