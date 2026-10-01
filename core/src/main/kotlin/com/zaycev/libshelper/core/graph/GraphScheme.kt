package com.zaycev.libshelper.core.graph

/**
 * Настройки карты модулей. Все размеры в мировых единицах, если не сказано иное.
 * Раскладка читает зазоры, маршруты — полосу зависимостей, отрисовка — толщину и пороги масштаба.
 */
object GraphScheme {
    const val CARD_WIDTH = 248f
    const val CARD_HEIGHT = 72f
    const val COLUMN_GAP = 104f
    const val ROW_GAP = 176f
    const val COLUMN_PITCH = CARD_WIDTH + COLUMN_GAP
    const val ROW_PITCH = CARD_HEIGHT + ROW_GAP
    const val EDGE_STUB = 20f

    const val BUS_RADIUS = 16f
    const val ARC_STEPS = 5
    const val DASH_ON = 10f
    const val DASH_OFF = 7f
    const val LANE_MARGIN = 8f
    const val LANE_PITCH = 14f

    /** Сколько зависимостей в одном коридоре рисуются отдельно. Сверх этого — одна рейка. */
    const val MAX_SEPARATE_DEPENDENCIES = 1

    /** Толщина полосы, в которой лежат рейки зависимостей у края карточки. */
    const val DEPENDENCY_STRIP = 36f

    const val STROKE_FAR = 22f
    const val STROKE_MID = 7f
    const val STROKE_NEAR = 2.6f
    const val BORDER_MID = 2.4f
    const val BORDER_NEAR = 1.4f
    const val RING_STROKE = 2.6f
    const val DOT_RADIUS = 18f
    const val CARD_CORNER = 14f
    const val CARD_STRIPE = 7f
    const val CARD_PAD = 10f
    const val BADGE = 26f
    const val LABEL_SLACK = 12f
    const val SHADOW_OFFSET = 3f

    const val FAR_ENTER = 0.18f
    const val FAR_EXIT = 0.26f
    const val NEAR_ENTER = 0.62f
    const val NEAR_EXIT = 0.48f
    const val DIM_ALPHA = 0.72f
    const val CULL_PAD = 96f
}
