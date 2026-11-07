package com.noop.ui

import com.noop.data.WorkoutRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the Workouts HR-zone card's parsing/aggregation to the real stored shapes:
 *   - WhoopCsvImporter.zonesJson → {"zone1".."zone5"} (this platform's own rows)
 *   - the macOS importer/backfill → {"z1".."z5"} (same data, other platform's key shape)
 * Percentages are 0–100 of each workout's duration and may sum to less than 100
 * (below-Z1 time is not exported).
 */
class WorkoutZonesTest {

    @Test
    fun parsesAndroidKeyShape() {
        assertEquals(
            listOf(10.0, 20.0, 30.0, 25.0, 15.0),
            parseZonePercents("""{"zone1":10.0,"zone2":20.0,"zone3":30.0,"zone4":25.0,"zone5":15.0}"""),
        )
    }

    @Test
    fun parsesMacKeyShape_missingZonesAreZero() {
        assertEquals(listOf(12.5, 0.0, 0.0, 0.0, 4.5), parseZonePercents("""{"z1":12.5,"z5":4.5}"""))
    }

    @Test
    fun rejectsNullBlankEmptyAndAllZero() {
        assertNull(parseZonePercents(null))
        assertNull(parseZonePercents(""))
        assertNull(parseZonePercents("{}"))
        assertNull(parseZonePercents("""{"zone1":0}"""))
    }

    @Test
    fun summaryIsDurationWeighted_andSkipsZonelessRows() {
        fun row(start: Long, durS: Double, zones: String?) = WorkoutRow(
            deviceId = "my-whoop", startTs = start, endTs = start + durS.toLong(),
            sport = "Running", source = "my-whoop", durationS = durS, zonesJSON = zones,
        )
        val s = zoneSummary(
            listOf(
                row(0, 3600.0, """{"zone1":100}"""),     // 60 min all Z1
                row(10_000, 1800.0, """{"z5":100}"""),   // 30 min all Z5 (mac shape)
                row(20_000, 1800.0, null),                  // zoneless — excluded
            ),
        )!!
        assertEquals(2, s.sessionsWithZones)
        assertEquals(2, s.importedSessions)
        assertEquals(0, s.strapSessions)
        assertEquals(60.0, s.minutes[0], 1e-9)
        assertEquals(30.0, s.minutes[4], 1e-9)
        assertEquals(90.0, s.totalMinutes, 1e-9)
    }

    private fun row(start: Long, durS: Double, zones: String?, source: String = "my-whoop") = WorkoutRow(
        deviceId = "my-whoop", startTs = start, endTs = start + durS.toLong(),
        sport = "Running", source = source, durationS = durS, zonesJSON = zones,
    )

    @Test
    fun importedZoneMinutes_isDurationTimesPercent_orNull() {
        assertEquals(listOf(30.0, 0.0, 0.0, 0.0, 15.0), importedZoneMinutes(row(0, 3600.0, """{"z1":50,"z5":25}""")))
        assertNull(importedZoneMinutes(row(0, 3600.0, null)))
        assertNull(importedZoneMinutes(row(0, 0.0, """{"z1":50}""")))
    }

    @Test
    fun summaryCountsStrapMinutes_forRowsWithoutAnImportedSplit() {
        val detected = row(20_000, 1800.0, null, source = "detected")
        val s = zoneSummary(
            listOf(row(0, 3600.0, """{"zone1":100}"""), detected),
            mapOf(workoutZoneKey(detected) to listOf(0.0, 10.0, 20.0, 0.0, 0.0)),
        )!!
        assertEquals(1, s.importedSessions)
        assertEquals(1, s.strapSessions)
        assertEquals(2, s.sessionsWithZones)
        assertEquals(listOf(60.0, 10.0, 20.0, 0.0, 0.0), s.minutes)
    }

    @Test
    fun summaryPrefersTheImportedSplit_overStrapMinutesForTheSameRow() {
        val r = row(0, 3600.0, """{"z3":100}""")
        val s = zoneSummary(listOf(r), mapOf(workoutZoneKey(r) to listOf(99.0, 0.0, 0.0, 0.0, 0.0)))!!
        assertEquals(1, s.importedSessions)
        assertEquals(0, s.strapSessions)
        assertEquals(listOf(0.0, 0.0, 60.0, 0.0, 0.0), s.minutes)
    }

    @Test
    fun summaryIsNull_whenNoRowHasEitherSource() {
        val r = row(0, 3600.0, null)
        assertNull(zoneSummary(listOf(r)))
        assertNull(zoneSummary(listOf(r), mapOf("other|key" to listOf(1.0, 1.0, 1.0, 1.0, 1.0))))
        assertNull(zoneSummary(listOf(r), mapOf(workoutZoneKey(r) to listOf(1.0, 1.0))))
    }
    @Test
    fun resolvedZoneMinutes_importedWins_elseStrap_elseNull() {
        val imported = row(0, 3600.0, """{"z2":50}""")
        val strapOnly = row(10_000, 600.0, null, source = "detected")
        val strap = mapOf(
            workoutZoneKey(imported) to listOf(9.0, 9.0, 9.0, 9.0, 9.0),
            workoutZoneKey(strapOnly) to listOf(1.0, 2.0, 3.0, 4.0, 5.0),
        )
        assertEquals(listOf(0.0, 30.0, 0.0, 0.0, 0.0), resolvedZoneMinutes(imported, strap))
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0, 5.0), resolvedZoneMinutes(strapOnly, strap))
        assertNull(resolvedZoneMinutes(row(20_000, 600.0, null), strap))
    }

    @Test
    fun weekBoundaries_mondayFirst_tokyo() {
        // 2026-11-07 12:00 JST, a Saturday. Expected values computed independently with Python zoneinfo;
        // the Swift twin asserts the same literals.
        assertEquals(
            listOf(1792335600L, 1792940400L, 1793545200L, 1794150000L),
            weekBoundaries(1794020400L, 3, java.time.ZoneId.of("Asia/Tokyo"), java.time.DayOfWeek.MONDAY),
        )
    }

    @Test
    fun weekBoundaries_sundayFirst_newYork_acrossTheDstChange() {
        // 2026-11-03 09:00 EST. DST ended 2026-11-01, so the current week is 169 hours long.
        val b = weekBoundaries(1793714400L, 2, java.time.ZoneId.of("America/New_York"), java.time.DayOfWeek.SUNDAY)
        assertEquals(listOf(1792900800L, 1793505600L, 1794114000L), b)
        assertEquals(169L * 3600, b[2] - b[1])
    }

    @Test
    fun weeklyIntensity_splitsModerateAndVigorous_byWeek() {
        val bounds = listOf(1792335600L, 1792940400L, 1793545200L, 1794150000L)
        val atWeekStart = row(1792940400L, 3600.0, """{"z2":25,"z3":25,"z4":25,"z5":25}""") // exactly week 1's start
        val lastSecondOfWeek0 = row(1792940399L, 600.0, null, source = "detected")
        val week2Strap = row(1793600000L, 1800.0, null, source = "detected")
        val beforeRange = row(1792335599L, 3600.0, """{"z5":100}""")
        val atRangeEnd = row(1794150000L, 3600.0, """{"z5":100}""")
        val noZones = row(1793700000L, 3600.0, null)
        val strap = mapOf(
            workoutZoneKey(lastSecondOfWeek0) to listOf(1.0, 2.0, 3.0, 4.0, 0.0),
            workoutZoneKey(week2Strap) to listOf(0.0, 5.0, 5.0, 10.0, 10.0),
        )
        val weeks = weeklyIntensity(
            listOf(atWeekStart, lastSecondOfWeek0, week2Strap, beforeRange, atRangeEnd, noZones), strap, bounds,
        )
        assertEquals(3, weeks.size)
        assertEquals(listOf(1792335600L, 1792940400L, 1793545200L), weeks.map { it.weekStart })
        // Week 0: the strap session one second before week 1 begins.
        assertEquals(5.0, weeks[0].moderateMinutes, 1e-9)
        assertEquals(4.0, weeks[0].vigorousMinutes, 1e-9)
        assertEquals(1, weeks[0].sessions)
        // Week 1: the imported session starting exactly on the boundary, 60 min split evenly.
        assertEquals(30.0, weeks[1].moderateMinutes, 1e-9)
        assertEquals(30.0, weeks[1].vigorousMinutes, 1e-9)
        assertEquals(90.0, weeks[1].whoEquivalentMinutes, 1e-9)
        // Week 2: the strap session; the zone-less one is skipped; out-of-range rows are ignored.
        assertEquals(10.0, weeks[2].moderateMinutes, 1e-9)
        assertEquals(20.0, weeks[2].vigorousMinutes, 1e-9)
        assertEquals(1, weeks[2].sessions)
    }

    @Test
    fun weeklyIntensity_needsAtLeastTwoBoundaries() {
        assertEquals(emptyList<WeekIntensity>(), weeklyIntensity(listOf(row(0, 60.0, """{"z3":100}""")), emptyMap(), listOf(0L)))
    }

    @Test
    fun isWalkingSport_matchesEverySpellingOfAWalk_notHiking() {
        listOf("Walking", "walking", "TreadmillWalk", "Treadmill walk", "Walk", "步行", "走路").forEach {
            assertEquals(it, true, isWalkingSport(it))
        }
        listOf("Hiking", "Running", "Elliptical", "detected", "Stair climber").forEach {
            assertEquals(it, false, isWalkingSport(it))
        }
    }
}
