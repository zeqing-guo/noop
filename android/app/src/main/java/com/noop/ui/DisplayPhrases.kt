package com.noop.ui

import com.noop.R

/**
 * Redesign: display-time translation for the English phrases that pure, unit-tested logic returns
 * (sleep words, night labels, "vs typical" captions, the sleep-debt ledger, stage names). The logic
 * keeps returning its English identity text — tests pin it — and passes it through [t]. The app installs
 * [resourcePhraseTranslator] at start-up; in a plain JVM test the translator is the identity.
 */
internal object DisplayPhrases {
    @Volatile
    var translator: (String) -> String = { it }

    fun t(raw: String): String = translator(raw)
}

private val exact: Map<String, Int> = mapOf(
    "Poor" to R.string.redesign_phrase_poor,
    "Fair" to R.string.redesign_phrase_fair,
    "Good" to R.string.redesign_phrase_good,
    "Optimal" to R.string.redesign_phrase_optimal,
    "Last night" to R.string.redesign_phrase_last_night,
    "1 night ago" to R.string.redesign_phrase_one_night_ago,
    "vs typical - " to R.string.redesign_phrase_vs_typical_none,
    "vs need" to R.string.redesign_phrase_vs_need,
    "On target" to R.string.redesign_phrase_on_target,
    "Below need" to R.string.redesign_phrase_below_need,
    "balanced" to R.string.redesign_phrase_balanced,
    "sleep debt" to R.string.redesign_phrase_sleep_debt,
    "Awake" to R.string.redesign_stage_awake,
    "Light" to R.string.redesign_stage_light,
    "Deep" to R.string.redesign_stage_deep,
    "REM" to R.string.redesign_stage_rem,
)

private val nightsAgo = Regex("""^(\d+) nights ago$""")
private val vsTypical = Regex("""^([+−].+) vs typical$""")
private val metTarget = Regex("""^You've met your current sleep target across the last (\d+) nights?\. .*""")
private val aimFor = Regex("""^Aim for about (.+) beyond your base need tonight\..*""")
private val weekEarly = Regex("""^Only (\d+) days? into this week so far, too early to call a week-over-week trend yet\.$""")
private val weekThin = Regex("""^Last week only had (\d+) days? of data, so week-over-week changes are rough, not a trend\.$""")
private val weekSteadyRest = Regex("""^A steady week: Rest held even \(±(.+) pts\) and nothing moved much\.$""")
private val surplus = Regex("""^You're carrying about (.+) of surplus over the last (\d+) nights?\..*""")

/** Maps a known English phrase onto its string resource in the app language; anything else passes. */
internal fun resourcePhraseTranslator(raw: String): String {
    exact[raw]?.let { return uiString(it) }
    nightsAgo.matchEntire(raw)?.let { return uiString(R.string.redesign_phrase_nights_ago, it.groupValues[1].toInt()) }
    vsTypical.matchEntire(raw)?.let { return uiString(R.string.redesign_phrase_vs_typical, it.groupValues[1]) }
    metTarget.matchEntire(raw)?.let { return uiString(R.string.redesign_phrase_debt_met, it.groupValues[1].toInt()) }
    aimFor.matchEntire(raw)?.let { return uiString(R.string.redesign_phrase_debt_aim, it.groupValues[1]) }
    surplus.matchEntire(raw)?.let { return uiString(R.string.redesign_phrase_debt_surplus, it.groupValues[1], it.groupValues[2].toInt()) }
    weekEarly.matchEntire(raw)?.let { return uiString(R.string.redesign_phrase_week_early, it.groupValues[1].toInt()) }
    weekThin.matchEntire(raw)?.let { return uiString(R.string.redesign_phrase_week_thin, it.groupValues[1].toInt()) }
    weekSteadyRest.matchEntire(raw)?.let { return uiString(R.string.redesign_phrase_week_steady_rest, it.groupValues[1]) }
    if (raw == "A steady week: no metric moved meaningfully from last week.") {
        return uiString(R.string.redesign_phrase_week_steady)
    }
    return raw
}
