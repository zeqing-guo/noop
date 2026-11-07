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
            let fromImport = importedMinutes(r)
            guard let m = fromImport ?? strapMinutes[strapKey(r)].flatMap({ $0.count == 5 ? $0 : nil })
            else { continue }
            for i in 0..<5 { mins[i] += m[i] }
            if fromImport != nil { imported += 1 } else { strap += 1 }
        }
        guard imported + strap > 0, mins.reduce(0, +) > 0 else { return nil }
        return Summary(minutes: mins, importedSessions: imported, strapSessions: strap)
    }
}
