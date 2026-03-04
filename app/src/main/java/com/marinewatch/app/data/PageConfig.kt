package com.marinewatch.app.data

import android.content.SharedPreferences

/** Number of data pages (excluding the config page). */
const val DATA_PAGE_COUNT = 3

/** Number of slots per data page (2×2 grid). */
const val SLOTS_PER_PAGE = 4

/**
 * Holds the [DataField] assigned to each slot of a single data page.
 *
 * Slots are indexed 0–3, laid out as:
 *   [0]  [1]
 *   [2]  [3]
 */
data class PageConfig(val slots: List<DataField>) {
    init {
        require(slots.size == SLOTS_PER_PAGE) {
            "PageConfig requires exactly $SLOTS_PER_PAGE slots, got ${slots.size}"
        }
    }

    companion object {
        /**
         * Factory that returns sensible defaults for each page index.
         *
         * Page 0 → navigation basics  (STW / DEPTH / COG / SOG)
         * Page 1 → heading + position (HDG / LAT / LON / EMPTY)
         * Page 2 → wind               (AWS / AWA / TWS / TWD)
         */
        fun default(pageIndex: Int): PageConfig = when (pageIndex) {
            0    -> PageConfig(listOf(DataField.STW,     DataField.DEPTH, DataField.COG,   DataField.SOG))
            1    -> PageConfig(listOf(DataField.HDG_MAG, DataField.LAT,   DataField.LON,   DataField.EMPTY))
            2    -> PageConfig(listOf(DataField.AWS,     DataField.AWA,   DataField.TWS,   DataField.TWD))
            else -> PageConfig(List(SLOTS_PER_PAGE) { DataField.EMPTY })
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SharedPreferences helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun prefKey(pageIndex: Int, slotIndex: Int) = "page_${pageIndex}_slot_${slotIndex}"

/**
 * Loads all [DATA_PAGE_COUNT] page configurations from [SharedPreferences].
 * Missing keys fall back to [PageConfig.default].
 */
fun SharedPreferences.loadPageConfigs(): List<PageConfig> =
    List(DATA_PAGE_COUNT) { pageIndex ->
        val slots = List(SLOTS_PER_PAGE) { slotIndex ->
            val key     = prefKey(pageIndex, slotIndex)
            val saved   = getString(key, null)
            val default = PageConfig.default(pageIndex).slots[slotIndex]
            if (saved != null) {
                runCatching { DataField.valueOf(saved) }.getOrDefault(default)
            } else {
                default
            }
        }
        PageConfig(slots)
    }

/**
 * Persists all [DATA_PAGE_COUNT] page configurations to [SharedPreferences].
 */
fun SharedPreferences.savePageConfigs(configs: List<PageConfig>) {
    edit().apply {
        configs.forEachIndexed { pageIndex, page ->
            page.slots.forEachIndexed { slotIndex, field ->
                putString(prefKey(pageIndex, slotIndex), field.name)
            }
        }
        apply()
    }
}
