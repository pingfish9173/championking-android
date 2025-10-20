package com.champion.king

import com.champion.king.R

/**
 * 版型對應表抽出成單一來源；不改動既有對應。
 */
object ScratchLayouts {
    private val map = mapOf(
        10 to R.layout.scratch_card_10,
        20 to R.layout.scratch_card_20,
        25 to R.layout.scratch_card_25,
        30 to R.layout.scratch_card_30,
        40 to R.layout.scratch_card_40,
        50 to R.layout.scratch_card_50,
        60 to R.layout.scratch_card_60,
        80 to R.layout.scratch_card_80,
        100 to R.layout.scratch_card_100,
        120 to R.layout.scratch_card_120,
        160 to R.layout.scratch_card_160,
        200 to R.layout.scratch_card_200,
        240 to R.layout.scratch_card_240
    )

    fun layoutFor(type: Int) = map[type]
}
