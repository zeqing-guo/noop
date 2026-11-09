import XCTest
@testable import Strand
import StrandDesign
import StrandAnalytics
import WhoopStore

/// Pins the suggestion catalogue (#714): the two new indoor presets exist, are spelled byte-for-byte
/// the way Android persists them (the stored sport label round-trips cross-platform via CSV / export),
/// and default GPS off (no route on a treadmill or a lifting session). Mirrors the Android
/// WorkoutSportTest intent for the same two sports.
final class WorkoutCatalogTests: XCTestCase {

    /// Asked for by a user. The Swift catalogue carries no Health Connect type, so what is pinned here
    /// is the label: it is the stored, cross-platform value and must read identically on Android.
    func testNordicWalkingIsOffered() {
        XCTAssertTrue(WorkoutCatalog.all.contains { $0.name == "Nordic walking" })
    }

    /// The WHOOP-parity batch. Labels only on this side, but they are the stored cross-platform value,
    /// so a rename here silently splits one sport into two across the two apps.
    func testWhoopParitySportsAreOffered() {
        let names = Set(WorkoutCatalog.all.map(\.name))
        for expected in ["Ballet", "Billiards", "Breakdancing", "Cheerleading", "Darts", "Disc golf",
                         "Hurling/Camogie", "Jiu jitsu", "Judo", "Kiteboarding", "Motocross",
                         "Muay Thai", "Paintball", "Parkour", "Polo", "Skydiving"] {
            XCTAssertTrue(names.contains(expected), "missing \(expected)")
        }
    }

    func testTreadmillWalkPresetExistsWithGpsOff() {
        let s = WorkoutCatalog.sport(named: "Treadmill walk")
        XCTAssertNotNil(s, "Treadmill walk must be in the suggestion catalogue (#714)")
        XCTAssertEqual(s?.name, "Treadmill walk", "Name is persisted data, must match Android byte-for-byte")
        XCTAssertEqual(s?.isDistanceSport, false, "Indoor walk has no route, so GPS defaults off")
    }

    func testBodybuildingPresetExistsWithGpsOff() {
        let s = WorkoutCatalog.sport(named: "Bodybuilding")
        XCTAssertNotNil(s, "Bodybuilding must be in the suggestion catalogue (#714)")
        XCTAssertEqual(s?.name, "Bodybuilding", "Name is persisted data, must match Android byte-for-byte")
        XCTAssertEqual(s?.isDistanceSport, false, "A lifting session has no route, so GPS defaults off")
    }

    func testLookupIsCaseInsensitive() {
        XCTAssertEqual(WorkoutCatalog.sport(named: "treadmill WALK")?.name, "Treadmill walk")
        XCTAssertEqual(WorkoutCatalog.sport(named: "  bodybuilding  ")?.name, "Bodybuilding")
    }

    /// #768: the newly requested presets exist and are spelled byte-for-byte the way Android persists
    /// them (the stored sport label round-trips cross-platform via CSV / export). Racket + court sports
    /// have no route so GPS defaults off; snow sports cover ground so GPS defaults on.
    func testNewPresetsExistWithCorrectGpsDefaults() {
        let gpsOff = ["Racquetball", "Volleyball", "Martial arts", "Dancing", "Golf",
                      "Climbing", "Stretching", "Pickleball"]
        for name in gpsOff {
            let s = WorkoutCatalog.sport(named: name)
            XCTAssertNotNil(s, "\(name) must be in the suggestion catalogue (#768)")
            XCTAssertEqual(s?.name, name, "Name is persisted data, must match Android byte-for-byte")
            XCTAssertEqual(s?.isDistanceSport, false, "\(name) has no route, GPS defaults off")
        }
        for name in ["Skiing", "Snowboarding"] {
            let s = WorkoutCatalog.sport(named: name)
            XCTAssertNotNil(s, "\(name) must be in the suggestion catalogue (#768)")
            XCTAssertEqual(s?.isDistanceSport, true, "\(name) covers ground, GPS defaults on")
        }
    }

    /// Padel (#152) stays the canonical racket extra, and the new Pickleball extra rides last, just
    /// before the generic "Other" catch-all , parity with Android's WorkoutSport build order.
    func testExtrasPrecedeOther() {
        let names = WorkoutCatalog.all.map(\.name)
        guard let pickle = names.firstIndex(of: "Pickleball"),
              let other = names.firstIndex(of: "Other") else {
            return XCTFail("Pickleball and Other must both be present")
        }
        XCTAssertLessThan(pickle, other, "Pickleball extra must sit before the generic Other")
        XCTAssertEqual(names.last, "Other", "Other stays the final catch-all")
    }

    /// Bowling (D#850): exists, is spelled byte-for-byte the way Android persists it, defaults GPS off
    /// (a lane has no route), and rides with the extras before the generic "Other" catch-all.
    func testBowlingPresetExistsWithGpsOff() {
        let s = WorkoutCatalog.sport(named: "bowling")
        XCTAssertNotNil(s, "Bowling must be in the suggestion catalogue (D#850)")
        XCTAssertEqual(s?.name, "Bowling", "Name is persisted data, must match Android byte-for-byte")
        XCTAssertEqual(s?.isDistanceSport, false, "Bowling has no route, GPS defaults off")
        let names = WorkoutCatalog.all.map(\.name)
        guard let bowling = names.firstIndex(of: "Bowling"),
              let other = names.firstIndex(of: "Other") else {
            return XCTFail("Bowling and Other must both be present")
        }
        XCTAssertLessThan(bowling, other, "Bowling extra must sit before the generic Other")
    }

    /// Iconography lockstep: every catalogue sport must resolve to a `KnownWorkoutType` with a unique
    /// runtime glyph on the current OS, matching the live-workout Liquid Glass control.
    func testCatalogueSportsHaveUniqueWorkoutTypeIcons() {
        let catalogNames = WorkoutCatalog.all.map(\.name)
        let knownNames = KnownWorkoutType.allCases.map(\.rawValue)
        XCTAssertEqual(Set(catalogNames), Set(knownNames),
                       "KnownWorkoutType raw values must match WorkoutCatalog.all names exactly")

        var identities = Set<String>()
        for sport in WorkoutCatalog.all {
            guard let type = KnownWorkoutType.exact(matching: sport.name) else {
                return XCTFail("No KnownWorkoutType for catalogue sport \(sport.name)")
            }
            let id = WorkoutTypeIconography.identity(for: type)
            XCTAssertFalse(identities.contains(id), "Duplicate icon \(id) for \(sport.name)")
            identities.insert(id)
        }
        XCTAssertEqual(identities.count, WorkoutCatalog.all.count)
    }

    /// Recovery activities: every catalogue name is offered, GPS off, and the pickers' two groups are
    /// exactly the catalogue. Twin of Android RecoveryActivityTest.
    func testRecoveryActivitiesAreOfferedAsTheirOwnGroup() {
        for name in RecoveryActivity.catalogueNames {
            let s = WorkoutCatalog.sport(named: name)
            XCTAssertEqual(s?.name, name, "Name is persisted data, must match Android byte-for-byte")
            XCTAssertEqual(s?.isDistanceSport, false, "\(name) has no route, GPS defaults off")
        }
        XCTAssertEqual(WorkoutCatalog.recovery.map(\.name), RecoveryActivity.catalogueNames)
        XCTAssertEqual(WorkoutCatalog.training.count + WorkoutCatalog.recovery.count, WorkoutCatalog.all.count)
        XCTAssertEqual(WorkoutCatalog.training.last?.name, "Other")
        XCTAssertFalse(WorkoutCatalog.training.contains { RecoveryActivity.isRecovery($0.name) })
    }

    /// A session saved, edited or relabelled INTO a recovery activity drops Effort, calories and zones and
    /// keeps what was measured or typed. Twin of Android withoutTrainingMetrics_stripsOnlyRecoveryRows.
    func testWithoutTrainingMetricsStripsOnlyRecoveryRows() {
        func row(_ sport: String) -> WorkoutRow {
            WorkoutRow(startTs: 1_000, endTs: 4_600, sport: sport, source: "manual", durationS: 3_600,
                       energyKcal: 410, avgHr: 112, maxHr: 131, strain: 9.4, distanceM: nil,
                       zonesJSON: "{\"zone1\":40}", notes: "n", steps: nil)
        }
        let sauna = WorkoutSource.withoutTrainingMetrics(row("Sauna"))
        XCTAssertNil(sauna.strain); XCTAssertNil(sauna.energyKcal); XCTAssertNil(sauna.zonesJSON)
        XCTAssertEqual(sauna.avgHr, 112); XCTAssertEqual(sauna.maxHr, 131); XCTAssertEqual(sauna.notes, "n")
        XCTAssertNil(WorkoutSource.withoutTrainingMetrics(row("IceBath")).strain)
        XCTAssertEqual(WorkoutSource.withoutTrainingMetrics(row("Running")), row("Running"))
        let split = WorkoutSource.partitionRecovery([row("Running"), row("Sauna"), row("Yoga"), row("Hot bath")])
        XCTAssertEqual(split.training.map(\.sport), ["Running", "Yoga"])
        XCTAssertEqual(split.recovery.map(\.sport), ["Sauna", "Hot bath"])
    }
}
