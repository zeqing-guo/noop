package com.noop.analytics

import com.noop.data.WorkoutRow

/**
 * Row-level rules for recovery activities ([RecoveryActivity]). Twin of the Swift
 * `WorkoutSource.withoutTrainingMetrics` / `WorkoutSource.partitionRecovery` (Strand/Data/WorkoutSource.swift).
 */
object RecoveryActivityRows {

    /**
     * [row] as it is stored when it is a recovery activity: no session Effort, no calories and no HR-zone
     * split, because heat or a massage table raises heart rate without any work being done. Heart rate
     * itself (average, peak) is kept: it is a real measurement and the session's HR curve still draws.
     * A training session is returned unchanged.
     *
     * Applied on every path that WRITES a session NOOP owns (manual add / edit / duplicate / merge, a
     * relabel, a live session ending, the post-sync rescore) so a session edited or relabelled INTO a
     * recovery activity drops the Effort it carried. Imported history is never rewritten; the screens
     * ignore those fields on a recovery row instead. Swift twin: `WorkoutSource.withoutTrainingMetrics`.
     */
    fun withoutTrainingMetrics(row: WorkoutRow): WorkoutRow =
        if (RecoveryActivity.isRecovery(row.sport)) row.copy(strain = null, energyKcal = null, zonesJSON = null)
        else row

    /** [rows] split into (training, recovery), each keeping the input order. Swift twin:
     *  `WorkoutSource.partitionRecovery`. */
    fun partition(rows: List<WorkoutRow>): Pair<List<WorkoutRow>, List<WorkoutRow>> =
        rows.partition { !RecoveryActivity.isRecovery(it.sport) }
}
