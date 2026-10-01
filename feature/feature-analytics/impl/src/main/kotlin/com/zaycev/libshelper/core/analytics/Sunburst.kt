package com.zaycev.libshelper.core.analytics

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

private const val FULL_CIRCLE_DEG = 360f
private const val START_DEG = -90f
private const val SLICE_GAP_DEG = 0.7f
private const val INNER_HOLE = 0.36f
private const val INNER_RING_OUTER = 0.62f
private const val OUTER_RING_INNER = 0.66f
private const val OUTER_RING_OUTER = 0.94f
private const val MIN_INNER_SWEEP_DEG = 0.1f
private const val MIN_OUTER_SWEEP_DEG = 0.08f
private const val OUTER_GAP_SHARE = 0.35f
private const val MAX_GROUPS = 10
private const val MAX_LIBS_PER_GROUP = 8

private data class WeightGroup(
    val prefix: String,
    val items: List<LibraryStat>,
)

private data class OuterItem(
    val id: String,
    val label: String,
    val weight: Int,
    val libraryKey: String?,
)

class DefaultSunburstLayout : SunburstLayout {
    override fun layout(libraries: ImmutableList<LibraryStat>, otherLabel: String): SunburstChartData =
        sunburstOf(libraries, otherLabel)
}

internal fun sunburstOf(
    libraries: ImmutableList<LibraryStat>,
    otherLabel: String,
): SunburstChartData {
    val libraryCount = libraries.size
    val totalWeight = libraries.sumOf { it.weight }
    if (libraries.isEmpty() || totalWeight <= 0) {
        return SunburstChartData(persistentListOf(), totalWeight = 0, libraryCount = 0)
    }
    val grouped = libraries.groupBy { orgPrefix(it.group) }
        .map { entry -> WeightGroup(entry.key, entry.value.sortedByDescending { it.weight }) }
        .sortedByDescending { group -> group.items.sumOf { it.weight } }
    val overflow = grouped.drop(MAX_GROUPS)
    val visibleGroups = if (overflow.isEmpty()) {
        grouped
    } else {
        grouped.take(MAX_GROUPS - 1) + WeightGroup(
            prefix = otherLabel,
            items = overflow.flatMap { it.items }.sortedByDescending { it.weight },
        )
    }
    val slices = mutableListOf<SunburstSlice>()
    var cursor = START_DEG
    visibleGroups.forEachIndexed { groupIndex, group ->
        val prefix = group.prefix
        val items = group.items
        val groupWeight = items.sumOf { it.weight }.coerceAtLeast(1)
        val groupSweep = FULL_CIRCLE_DEG * groupWeight / totalWeight
        val innerSweep = (groupSweep - SLICE_GAP_DEG).coerceAtLeast(MIN_INNER_SWEEP_DEG)
        slices += SunburstSlice(
            id = "group:$prefix",
            label = prefix,
            weight = groupWeight,
            startDeg = cursor,
            sweepDeg = innerSweep,
            inner = INNER_HOLE,
            outer = INNER_RING_OUTER,
            colorIndex = groupIndex,
            libraryKey = null,
        )
        val outerItems = outerItems(prefix, items, otherLabel)
        var outerCursor = cursor
        outerItems.forEach { item ->
            val share = innerSweep * item.weight / groupWeight.toFloat()
            val sweep = (share - SLICE_GAP_DEG * OUTER_GAP_SHARE).coerceAtLeast(MIN_OUTER_SWEEP_DEG)
            slices += SunburstSlice(
                id = item.id,
                label = item.label,
                weight = item.weight,
                startDeg = outerCursor,
                sweepDeg = sweep,
                inner = OUTER_RING_INNER,
                outer = OUTER_RING_OUTER,
                colorIndex = groupIndex,
                libraryKey = item.libraryKey,
            )
            outerCursor += share
        }
        cursor += groupSweep
    }
    return SunburstChartData(slices = slices.toPersistentList(), totalWeight = totalWeight, libraryCount = libraryCount)
}

private fun outerItems(
    prefix: String,
    items: List<LibraryStat>,
    otherLabel: String,
): List<OuterItem> {
    if (items.size <= MAX_LIBS_PER_GROUP) {
        return items.map { item ->
            OuterItem(item.key, item.artifact, item.weight, item.key)
        }
    }
    val head = items.take(MAX_LIBS_PER_GROUP - 1)
    val rest = items.drop(MAX_LIBS_PER_GROUP - 1)
    return head.map { item -> OuterItem(item.key, item.artifact, item.weight, item.key) } +
        OuterItem(
            id = "$prefix:$otherLabel",
            label = otherLabel,
            weight = rest.sumOf { it.weight }.coerceAtLeast(1),
            libraryKey = null,
        )
}
