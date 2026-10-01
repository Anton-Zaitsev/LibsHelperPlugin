package com.zaycev.libshelper.core.inventory

import com.zaycev.libshelper.core.model.Coordinates
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.DependencySource
import kotlin.test.Test
import kotlin.test.assertEquals

class MergeUniqueDependenciesTest {
    @Test
    fun sameCoordinatesCollapseEvenWhenConfigurationsDiffer() {
        val implementation = dependency("implementation")
        val api = dependency("api")
        val merged = mergeUniqueDependencies(listOf(implementation, api))
        assertEquals(1, merged.size)
        assertEquals("implementation", merged.single().configuration)
    }

    private fun dependency(configuration: String) = DeclaredDependency(
        coordinates = Coordinates("com.example", "demo"),
        requestedVersion = "1.0.0",
        configuration = configuration,
        module = ":app",
        source = DependencySource.KotlinDsl,
    )
}
