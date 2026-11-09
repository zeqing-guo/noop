package com.noop.analytics

/*
 * RecoveryActivity.kt — which logged sessions are recovery activities (stretching, sauna, a hot bath,
 * an ice bath, massage, meditation, breathwork) rather than training. Kotlin twin of
 * Packages/StrandAnalytics/Sources/StrandAnalytics/RecoveryActivity.swift; both are pinned by the same
 * expected classification table.
 *
 * A recovery activity is still stored as an ordinary workout row, keyed by its sport name, so nothing
 * in the schema changes and an older build reads it as a plain session. What changes is how it counts:
 * it carries no session Effort and no calories (the heat of a sauna or a bath raises heart rate
 * without any work being done, so an HR-derived Effort or calorie figure would be invented), and it is
 * left out of the training summaries (Effort hero, totals, per-sport breakdown, HR zones, the weekly
 * moderate / vigorous card, the heart-rate-recovery trend). The day's Effort is computed from the
 * whole day's heart rate and is not affected either way.
 *
 * Classification is by sport NAME, so WHOOP / Health Connect imports of the same activities
 * ("IceBath", "Hot Tub", "Massage Therapy") and the Chinese names a wearer might type are recognised
 * too. Pure: no Android, no database.
 */
object RecoveryActivity {

    /**
     * The recovery activities the pickers offer, in picker order. These are the STORED sport names, so
     * they are byte-identical to the Swift list and to the catalogue entries in [WorkoutSport.all].
     */
    val catalogueNames: List<String> = listOf(
        "Stretching",
        "Meditation",
        "Breathwork",
        "Sauna",
        "Hot bath",
        "Ice bath",
        "Massage",
    )

    /** Every recognised spelling, as a [key]. Catalogue names, the WHOOP / Health Connect labels for the
     *  same activities, and the Chinese names. */
    private val keys: Set<String> = setOf(
        // Stretching
        "stretching", "stretch", "拉伸",
        // Meditation
        "meditation", "mindfulness", "冥想",
        // Breathwork
        "breathwork", "breathing", "guidedbreathing", "呼吸练习",
        // Sauna
        "sauna", "infraredsauna", "桑拿",
        // Hot bath
        "hotbath", "hottub", "bath", "泡澡", "热水浴",
        // Ice bath
        "icebath", "coldplunge", "coldwaterimmersion", "冰浴",
        // Massage
        "massage", "massagetherapy", "percussivemassage", "按摩",
        // Other recovery modalities WHOOP lists, recognised on import (not offered in the pickers)
        "redlighttherapy", "aircompression", "compressiontherapy",
    )

    /**
     * The matching form of a sport name: ASCII letters lower-cased, spaces / tabs / line breaks /
     * ideographic spaces / underscores / hyphens dropped, every other character kept as it is. So
     * "IceBath", "Ice bath", "ice_bath" and "ICE-BATH" all read "icebath", and "泡澡" stays "泡澡".
     * Deliberately ASCII-only case folding: it is the same on both platforms for every input.
     * Swift twin: `RecoveryActivity.key`.
     */
    fun key(sport: String): String {
        val out = StringBuilder(sport.length)
        for (ch in sport) {
            when (ch) {
                ' ', '\t', '\n', '\r', '\u3000', '_', '-' -> Unit
                in 'A'..'Z' -> out.append(ch + ('a' - 'A'))
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    /** True when [sport] names a recovery activity. Swift twin: `RecoveryActivity.isRecovery`. */
    fun isRecovery(sport: String): Boolean = key(sport) in keys
}
