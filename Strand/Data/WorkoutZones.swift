import Foundation
import WhoopStore

// MARK: - Imported per-workout HR zones
//
// `zonesJSON` is the verbatim HR-zone-percentage object from the WHOOP CSV import
// (WhoopImporter writes "z1"…"z5"; the Android importer writes "zone1"…"zone5" for the
// same data — tolerate both so a cache moved between platforms still renders). Values
// are 0–100 percent of the workout's duration and may sum to less than 100 (time below
// zone 1 is not exported).
enum WorkoutZones {

    /// Z1…Z5 percentages (0–100) for one workout, or nil when the row carries no usable zone data.
    static func percents(_ zonesJSON: String?) -> [Double]? {
        guard let zonesJSON,
              let data = zonesJSON.data(using: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else { return nil }
        let p = (1...5).map { i -> Double in
            let v = (obj["z\(i)"] ?? obj["zone\(i)"]) as? NSNumber
            return min(max(v?.doubleValue ?? 0, 0), 100)
        }
        return p.contains(where: { $0 > 0 }) ? p : nil
    }

    /// Zone minutes aggregated across sessions, split by where each session's minutes came from:
    /// `importedSessions` carried a WHOOP-imported split, `strapSessions` were binned from strap HR.
    /// APPROXIMATE either way: an on-device aggregate, not a WHOOP-computed figure.
    struct Summary {
        let minutes: [Double]          // index 0 = Z1 … 4 = Z5
        let importedSessions: Int
        var strapSessions: Int = 0
        var sessionsWithZones: Int { importedSessions + strapSessions }
        var totalMinutes: Double { minutes.reduce(0, +) }
    }

    /// One workout's imported Z1…Z5 MINUTES (duration-minutes × pct ÷ 100), or nil when the row
    /// carries no usable imported split. The single "imported" half of the per-workout zone rule: the
    /// session detail and `summary` both read it, so a session and the aggregate that counts it cannot
    /// disagree. Kotlin twin: `importedZoneMinutes` in WorkoutsScreen.kt.
    static func importedMinutes(_ row: WorkoutRow) -> [Double]? {
        guard let p = percents(row.zonesJSON) else { return nil }
        let durMin = (row.durationS ?? Double(row.endTs - row.startTs)) / 60.0
        guard durMin > 0 else { return nil }
        return p.map { durMin * $0 / 100.0 }
    }

    /// Screen-local identity for a row's strap-derived zone minutes. Includes the source because
    /// `Repository.workoutZoneMinutes` resolves its HR ids from it. Never persisted.
    static func strapKey(_ row: WorkoutRow) -> String {
        "\(row.startTs)|\(row.endTs)|\(row.source)"
    }

    /// Duration-weighted zone minutes across rows. Per row, the same rule as the session detail: the
    /// imported split wins; otherwise the minutes binned from strap HR (`strapMinutes`, keyed by
    /// `strapKey`); otherwise the row is skipped. Kotlin twin: `zoneSummary`.
    static func summary(from rows: [WorkoutRow], strapMinutes: [String: [Double]] = [:]) -> Summary? {
        var mins = [Double](repeating: 0, count: 5)
        var imported = 0
        var strap = 0
        for r in rows {
            guard let m = resolvedMinutes(r, strapMinutes: strapMinutes) else { continue }
            for i in 0..<5 { mins[i] += m[i] }
            if importedMinutes(r) != nil { imported += 1 } else { strap += 1 }
        }
        guard imported + strap > 0, mins.reduce(0, +) > 0 else { return nil }
        return Summary(minutes: mins, importedSessions: imported, strapSessions: strap)
    }

    /// One workout's Z1…Z5 minutes under the per-row rule every zone readout shares: the imported split
    /// wins, otherwise the strap-HR minutes in `strapMinutes` (keyed by `strapKey`), otherwise nil. The
    /// zones card, the weekly intensity card and the session detail all resolve through this rule, so no
    /// two of them can count one session differently. Kotlin twin: `resolvedZoneMinutes`.
    static func resolvedMinutes(_ row: WorkoutRow, strapMinutes: [String: [Double]]) -> [Double]? {
        importedMinutes(row) ?? strapMinutes[strapKey(row)].flatMap { $0.count == 5 ? $0 : nil }
    }

    // MARK: - Weekly intensity (moderate Z2–Z3 / vigorous Z4–Z5 minutes per calendar week)

    /// UserDefaults key for whether the weekly intensity card counts walks. Default off. The same key
    /// string as Android's `NoopPrefs.KEY_WEEKLY_INTENSITY_INCLUDES_WALKING`.
    static let weeklyIntensityIncludesWalkingKey = "noop.weeklyIntensityIncludesWalking"

    /// Calendar weeks the weekly intensity card reads: this week, plus last week for the comparison line.
    /// Kotlin twin: `WEEKLY_INTENSITY_WEEKS`.
    static let weeklyIntensityWeeks = 2

    /// WHO 2020 physical-activity guideline for adults: at least 150 minutes of moderate-intensity, or 75
    /// minutes of vigorous-intensity, aerobic activity a week, or an equivalent combination, in which a
    /// vigorous minute counts as two moderate ones (Bull et al., Br J Sports Med 2020;54:1451-1462).
    /// Kotlin twin: `WHO_WEEKLY_MODERATE_EQUIVALENT_MINUTES`.
    static let whoWeeklyModerateEquivalentMinutes = 150.0

    /// Whether a workout is a walk, for the weekly intensity card's walking switch: the catalogue's
    /// Walking and Treadmill walk under every spelling the sources use (WHOOP / Apple Health "Walking",
    /// camel-cased "TreadmillWalk", a bare "walk") plus the two Chinese names a wearer might type. Hiking
    /// is NOT a walk here. Kotlin twin: `isWalkingSport`.
    static func isWalkingSport(_ sport: String) -> Bool {
        walkingSportKeys.contains(WorkoutSource.sportKey(sport))
    }

    private static let walkingSportKeys: Set<String> = ["walking", "walk", "treadmillwalk", "步行", "走路"]

    /// One calendar week of workout intensity. Moderate = Z2 + Z3 (60–80% HRmax under the default model),
    /// vigorous = Z4 + Z5 (80% HRmax and above), both in the user's own zone model.
    /// Kotlin twin: `WeekIntensity`.
    struct WeekIntensity: Equatable {
        /// Epoch seconds of the week's first instant (local midnight on the week's first day, Monday).
        let weekStart: Int
        let moderateMinutes: Double
        let vigorousMinutes: Double
        /// Sessions in this week that carried zone minutes (imported or strap HR).
        let sessions: Int
        var totalMinutes: Double { moderateMinutes + vigorousMinutes }
        /// The WHO combination: moderate minutes plus twice the vigorous minutes.
        var whoEquivalentMinutes: Double { moderateMinutes + 2.0 * vigorousMinutes }
    }

    /// Per-week moderate / vigorous minutes, oldest week first. `weekBounds` holds N+1 ascending
    /// epoch-second boundaries for N weeks; a session belongs to week i when
    /// `weekBounds[i] <= startTs < weekBounds[i+1]`. Sessions outside the bounds or without zone minutes
    /// are skipped. Pure: the calendar lives in `weekBoundaries`. Kotlin twin: `weeklyIntensity`.
    static func weeklyIntensity(rows: [WorkoutRow], strapMinutes: [String: [Double]],
                                weekBounds: [Int]) -> [WeekIntensity] {
        guard weekBounds.count >= 2 else { return [] }
        let n = weekBounds.count - 1
        var moderate = [Double](repeating: 0, count: n)
        var vigorous = [Double](repeating: 0, count: n)
        var sessions = [Int](repeating: 0, count: n)
        for r in rows {
            guard let i = (0..<n).first(where: { r.startTs >= weekBounds[$0] && r.startTs < weekBounds[$0 + 1] }),
                  let m = resolvedMinutes(r, strapMinutes: strapMinutes)
            else { continue }
            moderate[i] += m[1] + m[2]
            vigorous[i] += m[3] + m[4]
            sessions[i] += 1
        }
        return (0..<n).map {
            WeekIntensity(weekStart: weekBounds[$0], moderateMinutes: moderate[$0],
                          vigorousMinutes: vigorous[$0], sessions: sessions[$0])
        }
    }

    /// N+1 boundaries for the `weeks` calendar weeks ending with the one containing `nowSec`: each is
    /// local midnight on `firstWeekday` (Gregorian numbering: 1 = Sunday, 2 = Monday) in `timeZone`, and
    /// the last is the start of next week. Local midnights rather than fixed 7-day strides, so a DST week
    /// is 167 or 169 hours, as the calendar says. Kotlin twin: `weekBoundaries`.
    static func weekBoundaries(nowSec: Int, weeks: Int, timeZone: TimeZone, firstWeekday: Int) -> [Int] {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let now = Date(timeIntervalSince1970: TimeInterval(nowSec))
        // Day arithmetic runs from about local noon (twelve hours after the day starts, always inside the
        // same calendar day), then snaps back to that day's first instant: a zone whose DST change falls at
        // midnight starts that day at 01:00, as java.time's atStartOfDay does.
        guard let noonToday = cal.date(byAdding: .hour, value: 12, to: cal.startOfDay(for: now)) else { return [] }
        let back = (cal.component(.weekday, from: now) - firstWeekday + 7) % 7
        return stride(from: weeks - 1, through: -1, by: -1).compactMap { k -> Int? in
            guard let noon = cal.date(byAdding: .day, value: -back - 7 * k, to: noonToday) else { return nil }
            return Int(cal.startOfDay(for: noon).timeIntervalSince1970)
        }
    }
}
