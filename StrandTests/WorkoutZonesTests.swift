import XCTest
import WhoopStore
@testable import Strand

/// Pins the Workouts HR-zone card's parsing/aggregation to the real stored shapes:
/// the macOS importer/backfill write {"z1".."z5"}, the Android importer writes
/// {"zone1".."zone5"} for the same data — both must parse so a cache moved between
/// platforms still renders. Mirrors the Android WorkoutZonesTest case-for-case.
final class WorkoutZonesTests: XCTestCase {

    func testParsesMacKeyShape() {
        XCTAssertEqual(WorkoutZones.percents(#"{"z1":12.5,"z5":4.5}"#), [12.5, 0, 0, 0, 4.5])
    }

    func testParsesAndroidKeyShape() {
        XCTAssertEqual(WorkoutZones.percents(#"{"zone1":10,"zone2":20,"zone3":30,"zone4":25,"zone5":15}"#),
                       [10, 20, 30, 25, 15])
    }

    func testRejectsNilEmptyAndAllZero() {
        XCTAssertNil(WorkoutZones.percents(nil))
        XCTAssertNil(WorkoutZones.percents("{}"))
        XCTAssertNil(WorkoutZones.percents(#"{"z1":0}"#))
        XCTAssertNil(WorkoutZones.percents("not json"))
    }

    func testSummaryIsDurationWeighted() {
        func row(_ start: Int, _ durS: Double, _ zones: String?) -> WorkoutRow {
            WorkoutRow(startTs: start, endTs: start + Int(durS), sport: "Running", source: "whoop",
                       durationS: durS, energyKcal: nil, avgHr: nil, maxHr: nil, strain: nil,
                       distanceM: nil, zonesJSON: zones, notes: nil, steps: nil)
        }
        let s = WorkoutZones.summary(from: [
            row(0, 3600, #"{"z1":100}"#),          // 60 min all Z1
            row(10_000, 1800, #"{"zone5":100}"#),  // 30 min all Z5 (Android key shape)
            row(20_000, 1800, nil),                // no zones — excluded from the count
        ])
        XCTAssertEqual(s?.sessionsWithZones, 2)
        XCTAssertEqual(s?.importedSessions, 2)
        XCTAssertEqual(s?.strapSessions, 0)
        XCTAssertEqual(s?.minutes[0] ?? 0, 60, accuracy: 1e-9)
        XCTAssertEqual(s?.minutes[4] ?? 0, 30, accuracy: 1e-9)
        XCTAssertEqual(s?.totalMinutes ?? 0, 90, accuracy: 1e-9)
    }

    private func row(_ start: Int, _ durS: Double, _ zones: String?, source: String = "whoop",
                     sport: String = "Running") -> WorkoutRow {
        WorkoutRow(startTs: start, endTs: start + Int(durS), sport: sport, source: source,
                   durationS: durS, energyKcal: nil, avgHr: nil, maxHr: nil, strain: nil,
                   distanceM: nil, zonesJSON: zones, notes: nil, steps: nil)
    }

    func testImportedMinutesIsDurationTimesPercentOrNil() {
        XCTAssertEqual(WorkoutZones.importedMinutes(row(0, 3600, #"{"z1":50,"z5":25}"#)), [30, 0, 0, 0, 15])
        XCTAssertNil(WorkoutZones.importedMinutes(row(0, 3600, nil)))
        XCTAssertNil(WorkoutZones.importedMinutes(row(0, 0, #"{"z1":50}"#)))
    }

    func testSummaryCountsStrapMinutesForRowsWithoutAnImportedSplit() {
        let detected = row(20_000, 1800, nil, source: "detected")
        let s = WorkoutZones.summary(from: [row(0, 3600, #"{"zone1":100}"#), detected],
                                     strapMinutes: [WorkoutZones.strapKey(detected): [0, 10, 20, 0, 0]])
        XCTAssertEqual(s?.importedSessions, 1)
        XCTAssertEqual(s?.strapSessions, 1)
        XCTAssertEqual(s?.sessionsWithZones, 2)
        XCTAssertEqual(s?.minutes, [60, 10, 20, 0, 0])
    }

    func testSummaryPrefersTheImportedSplitOverStrapMinutesForTheSameRow() {
        let r = row(0, 3600, #"{"z3":100}"#)
        let s = WorkoutZones.summary(from: [r], strapMinutes: [WorkoutZones.strapKey(r): [99, 0, 0, 0, 0]])
        XCTAssertEqual(s?.importedSessions, 1)
        XCTAssertEqual(s?.strapSessions, 0)
        XCTAssertEqual(s?.minutes, [0, 0, 60, 0, 0])
    }

    func testSummaryIsNilWhenNoRowHasEitherSource() {
        let r = row(0, 3600, nil)
        XCTAssertNil(WorkoutZones.summary(from: [r]))
        XCTAssertNil(WorkoutZones.summary(from: [r], strapMinutes: ["other|key": [1, 1, 1, 1, 1]]))
        XCTAssertNil(WorkoutZones.summary(from: [r], strapMinutes: [WorkoutZones.strapKey(r): [1, 1]]))
    }

    func testResolvedMinutesImportedWinsElseStrapElseNil() {
        let imported = row(0, 3600, #"{"z2":50}"#)
        let strapOnly = row(10_000, 600, nil, source: "detected")
        let strap = [
            WorkoutZones.strapKey(imported): [9.0, 9, 9, 9, 9],
            WorkoutZones.strapKey(strapOnly): [1.0, 2, 3, 4, 5],
        ]
        XCTAssertEqual(WorkoutZones.resolvedMinutes(imported, strapMinutes: strap), [0, 30, 0, 0, 0])
        XCTAssertEqual(WorkoutZones.resolvedMinutes(strapOnly, strapMinutes: strap), [1, 2, 3, 4, 5])
        XCTAssertNil(WorkoutZones.resolvedMinutes(row(20_000, 600, nil), strapMinutes: strap))
    }

    // The week-boundary literals below are the Kotlin WorkoutZonesTest's, computed independently with
    // Python zoneinfo; both platforms assert the same values.

    func testWeekBoundariesMondayFirstTokyo() {
        // 2026-11-07 12:00 JST, a Saturday.
        XCTAssertEqual(
            WorkoutZones.weekBoundaries(nowSec: 1_794_020_400, weeks: 3,
                                        timeZone: TimeZone(identifier: "Asia/Tokyo")!, firstWeekday: 2),
            [1_792_335_600, 1_792_940_400, 1_793_545_200, 1_794_150_000])
    }

    func testWeekBoundariesSundayFirstNewYorkAcrossTheDstChange() {
        // 2026-11-03 09:00 EST. DST ended 2026-11-01, so the current week is 169 hours long.
        let b = WorkoutZones.weekBoundaries(nowSec: 1_793_714_400, weeks: 2,
                                            timeZone: TimeZone(identifier: "America/New_York")!, firstWeekday: 1)
        XCTAssertEqual(b, [1_792_900_800, 1_793_505_600, 1_794_114_000])
        XCTAssertEqual(b[2] - b[1], 169 * 3600)
    }

    func testWeekBoundariesOnAMidnightDstChangeStartTheDayAtOneAm() {
        // America/Santiago springs forward at 00:00 on Sunday 2026-09-06, so that day starts at 01:00 -03.
        // Sunday-first, from 2026-09-06 12:00: the boundary is that 01:00, not a midnight that never
        // happened. Same values from Python zoneinfo and java.time (atStartOfDay, the Kotlin twin's call).
        XCTAssertEqual(
            WorkoutZones.weekBoundaries(nowSec: 1_788_706_800, weeks: 1,
                                        timeZone: TimeZone(identifier: "America/Santiago")!, firstWeekday: 1),
            [1_788_667_200, 1_789_268_400])
    }

    func testWeeklyIntensitySplitsModerateAndVigorousByWeek() {
        let bounds = [1_792_335_600, 1_792_940_400, 1_793_545_200, 1_794_150_000]
        let atWeekStart = row(1_792_940_400, 3600, #"{"z2":25,"z3":25,"z4":25,"z5":25}"#)  // exactly week 1's start
        let lastSecondOfWeek0 = row(1_792_940_399, 600, nil, source: "detected")
        let week2Strap = row(1_793_600_000, 1800, nil, source: "detected")
        let beforeRange = row(1_792_335_599, 3600, #"{"z5":100}"#)
        let atRangeEnd = row(1_794_150_000, 3600, #"{"z5":100}"#)
        let noZones = row(1_793_700_000, 3600, nil)
        let strap = [
            WorkoutZones.strapKey(lastSecondOfWeek0): [1.0, 2, 3, 4, 0],
            WorkoutZones.strapKey(week2Strap): [0.0, 5, 5, 10, 10],
        ]
        let weeks = WorkoutZones.weeklyIntensity(
            rows: [atWeekStart, lastSecondOfWeek0, week2Strap, beforeRange, atRangeEnd, noZones],
            strapMinutes: strap, weekBounds: bounds)
        XCTAssertEqual(weeks.count, 3)
        XCTAssertEqual(weeks.map(\.weekStart), [1_792_335_600, 1_792_940_400, 1_793_545_200])
        // Week 0: the strap session one second before week 1 begins.
        XCTAssertEqual(weeks[0].moderateMinutes, 5, accuracy: 1e-9)
        XCTAssertEqual(weeks[0].vigorousMinutes, 4, accuracy: 1e-9)
        XCTAssertEqual(weeks[0].sessions, 1)
        // Week 1: the imported session starting exactly on the boundary, 60 min split evenly.
        XCTAssertEqual(weeks[1].moderateMinutes, 30, accuracy: 1e-9)
        XCTAssertEqual(weeks[1].vigorousMinutes, 30, accuracy: 1e-9)
        XCTAssertEqual(weeks[1].whoEquivalentMinutes, 90, accuracy: 1e-9)
        // Week 2: the strap session; the zone-less one is skipped; out-of-range rows are ignored.
        XCTAssertEqual(weeks[2].moderateMinutes, 10, accuracy: 1e-9)
        XCTAssertEqual(weeks[2].vigorousMinutes, 20, accuracy: 1e-9)
        XCTAssertEqual(weeks[2].sessions, 1)
    }

    func testWeeklyIntensityNeedsAtLeastTwoBoundaries() {
        XCTAssertEqual(WorkoutZones.weeklyIntensity(rows: [row(0, 60, #"{"z3":100}"#)], strapMinutes: [:],
                                                    weekBounds: [0]), [])
    }

    func testIsWalkingSportMatchesEverySpellingOfAWalkNotHiking() {
        for s in ["Walking", "walking", "TreadmillWalk", "Treadmill walk", "Walk", "步行", "走路"] {
            XCTAssertTrue(WorkoutZones.isWalkingSport(s), s)
        }
        for s in ["Hiking", "Running", "Elliptical", "detected", "Stair climber"] {
            XCTAssertFalse(WorkoutZones.isWalkingSport(s), s)
        }
    }

    func testWeeklyIntensityWalkingKeyMatchesAndroid() {
        XCTAssertEqual(WorkoutZones.weeklyIntensityIncludesWalkingKey, "noop.weeklyIntensityIncludesWalking")
        XCTAssertEqual(WorkoutZones.weeklyIntensityWeeks, 2)
        XCTAssertEqual(WorkoutZones.whoWeeklyModerateEquivalentMinutes, 150)
    }
}
