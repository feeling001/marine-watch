package com.marinewatch.app.data

/**
 * Enumerates every displayable field available from the Marine Gateway BLE services.
 *
 * Each entry carries:
 *  - [label]     Short uppercase string shown above the value on screen.
 *  - [unit]      Unit string shown below the value (empty string if not applicable).
 *  - [extract]   Lambda that pulls the formatted display string out of a [DisplayData]
 *                snapshot. Returns "---" when the underlying value is null.
 *
 * [EMPTY] is a sentinel used to leave a grid slot blank.
 */
enum class DataField(
    val label: String,
    val unit: String,
    val extract: (DisplayData) -> String
) {
    // ── Navigation ────────────────────────────────────────────────────────────
    STW(
        label = "STW",
        unit  = "kn",
        extract = { d -> d.nav.stw?.let { "%.1f".format(it) } ?: "---" }
    ),
    SOG(
        label = "SOG",
        unit  = "kn",
        extract = { d -> d.nav.sog?.let { "%.1f".format(it) } ?: "---" }
    ),
    COG(
        label = "COG",
        unit  = "°",
        extract = { d -> d.nav.cog?.let { "%.0f".format(it) } ?: "---" }
    ),
    DEPTH(
        label = "DEPTH",
        unit  = "m",
        extract = { d -> d.nav.depth?.let { "%.1f".format(it) } ?: "---" }
    ),
    HDG_MAG(
        label = "HDG",
        unit  = "°M",
        extract = { d -> d.nav.hdgMag?.let { "%.0f".format(it) } ?: "---" }
    ),
    LAT(
        label = "LAT",
        unit  = "°",
        extract = { d ->
            d.nav.lat?.let {
                val dir = if (it >= 0) "N" else "S"
                "%.4f%s".format(Math.abs(it.toDouble()), dir)
            } ?: "---"
        }
    ),
    LON(
        label = "LON",
        unit  = "°",
        extract = { d ->
            d.nav.lon?.let {
                val dir = if (it >= 0) "E" else "W"
                "%.4f%s".format(Math.abs(it.toDouble()), dir)
            } ?: "---"
        }
    ),

    // ── Wind ──────────────────────────────────────────────────────────────────
    AWS(
        label = "AWS",
        unit  = "kn",
        extract = { d -> d.wind?.aws?.let { "%.1f".format(it) } ?: "---" }
    ),
    AWA(
        label = "AWA",
        unit  = "°",
        extract = { d -> d.wind?.awa?.let { "%.0f".format(it) } ?: "---" }
    ),
    TWS(
        label = "TWS",
        unit  = "kn",
        extract = { d -> d.wind?.tws?.let { "%.1f".format(it) } ?: "---" }
    ),
    TWA(
        label = "TWA",
        unit  = "°",
        extract = { d -> d.wind?.twa?.let { "%.0f".format(it) } ?: "---" }
    ),
    TWD(
        label = "TWD",
        unit  = "°",
        extract = { d -> d.wind?.twd?.let { "%.0f".format(it) } ?: "---" }
    ),

    // ── Sail Performance ──────────────────────────────────────────────────────
    VMG(
        label = "VMG",
        unit  = "kn",
        extract = { d -> extractVmg(d) }
    ),
    POLAR_PCT(
        label = "POLAR",
        unit  = "%",
        extract = { d -> extractPolarPct(d) }
    ),
    TARGET_STW(
        label = "TGT",
        unit  = "kn",
        extract = { d -> extractTargetStw(d) }
    ),

    // ── Sentinel — blank slot ─────────────────────────────────────────────────
    EMPTY(
        label = "",
        unit  = "",
        extract = { "---" }
    );
}

// ── Performance extract helpers (top-level to avoid return-in-lambda issues) ──

private fun extractVmg(d: DisplayData): String {
    val vmg = d.perf?.vmg ?: return "---"
    return if (vmg >= 0f) "+%.1f".format(vmg) else "%.1f".format(vmg)
}

private fun extractPolarPct(d: DisplayData): String {
    val perf = d.perf ?: return "---"
    if (!perf.polarLoaded) return "NO POL"
    return perf.polarPct?.let { "%.0f".format(it) } ?: "---"
}

private fun extractTargetStw(d: DisplayData): String {
    val perf = d.perf ?: return "---"
    if (!perf.polarLoaded) return "NO POL"
    return perf.targetStw?.let { "%.1f".format(it) } ?: "---"
}
