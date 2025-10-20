package com.champion.king.constants

object ScratchCardConstants {
    const val DEFAULT_SHELF_ORDER = 1
    const val MAX_SHELF_COUNT = 6

    val SCRATCH_TYPES_LIST = listOf(
        "10刮 (2x5)", "20刮 (4x5)", "25刮 (5x5)", "30刮 (5x6)",
        "40刮 (5x8)", "50刮 (5x10)", "60刮 (6x10)", "80刮 (8x10)",
        "100刮 (10x10)", "120刮 (10x12)", "160刮 (10x16)", "200刮 (10x20)",
        "240刮 (12x20)"
    )

    val SCRATCH_COUNT_MAP = mapOf(
        10 to "10刮 (2x5)", 20 to "20刮 (4x5)", 25 to "25刮 (5x5)", 30 to "30刮 (5x6)",
        40 to "40刮 (5x8)", 50 to "50刮 (5x10)", 60 to "60刮 (6x10)", 80 to "80刮 (8x10)",
        100 to "100刮 (10x10)", 120 to "120刮 (10x12)", 160 to "160刮 (10x16)",
        200 to "200刮 (10x20)", 240 to "240刮 (12x20)"
    )

    val NUMBER_OPTIONS = listOf("1", "2", "3", "4", "5")
    const val DEFAULT_SCRATCH_TYPE = "25刮 (5x5)"
}