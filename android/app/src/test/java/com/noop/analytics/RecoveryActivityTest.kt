package com.noop.analytics

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.noop.data.WorkoutRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryActivityTest {

    /**
     * The classification table, pasted VERBATIM from the Swift twin's stdout
     * (Packages/StrandAnalytics/Sources/StrandAnalytics/RecoveryActivity.swift compiled standalone with
     * swiftc and run over these inputs). The Swift RecoveryActivityTests pins the same rows the other way.
     * Rows are input to key to isRecovery.
     */
    private val swiftOracle: List<Pair<Pair<String, String>, Boolean>> = listOf(
        "Stretching" to "stretching" to true,
        "Meditation" to "meditation" to true,
        "Breathwork" to "breathwork" to true,
        "Sauna" to "sauna" to true,
        "Hot bath" to "hotbath" to true,
        "Ice bath" to "icebath" to true,
        "Massage" to "massage" to true,
        "IceBath" to "icebath" to true,
        "ICE-BATH" to "icebath" to true,
        "ice_bath" to "icebath" to true,
        " ice  bath " to "icebath" to true,
        "HotTub" to "hottub" to true,
        "Hot Tub" to "hottub" to true,
        "Massage Therapy" to "massagetherapy" to true,
        "PercussiveMassage" to "percussivemassage" to true,
        "Cold Plunge" to "coldplunge" to true,
        "Red Light Therapy" to "redlighttherapy" to true,
        "Guided breathing" to "guidedbreathing" to true,
        "Mindfulness" to "mindfulness" to true,
        "泡澡" to "泡澡" to true,
        "桑拿" to "桑拿" to true,
        "冰浴" to "冰浴" to true,
        "按摩" to "按摩" to true,
        "拉伸" to "拉伸" to true,
        "冥想" to "冥想" to true,
        "呼吸练习" to "呼吸练习" to true,
        "热水浴" to "热水浴" to true,
        "泡　澡" to "泡澡" to true,
        "Yoga" to "yoga" to false,
        "Pilates" to "pilates" to false,
        "Other" to "other" to false,
        "Running" to "running" to false,
        "Ice hockey" to "icehockey" to false,
        "Bathtub" to "bathtub" to false,
        "Saunas" to "saunas" to false,
        "Stretch" to "stretch" to true,
        "Bath" to "bath" to true,
        "detected" to "detected" to false,
        "Activity" to "activity" to false,
        "" to "" to false,
        "  " to "" to false,
        "Ünstretching" to "Ünstretching" to false,
        "Massage🙂" to "massage🙂" to false,
        "ŞAUNA" to "Şauna" to false,
        "Cooldown" to "cooldown" to false,
        "Mobility" to "mobility" to false,
    )

    @Test fun classification_matchesTheSwiftTwin() {
        for ((pair, expected) in swiftOracle) {
            val (input, key) = pair
            assertEquals("key(\"$input\")", key, RecoveryActivity.key(input))
            assertEquals("isRecovery(\"$input\")", expected, RecoveryActivity.isRecovery(input))
        }
    }

    @Test fun everyCatalogueName_isAPickerSport_andARecoveryActivity() {
        val names = WorkoutSport.all.map { it.name }
        for (n in RecoveryActivity.catalogueNames) {
            assertTrue("$n must be in WorkoutSport.all", n in names)
            assertTrue("$n must classify as recovery", RecoveryActivity.isRecovery(n))
        }
        assertEquals(RecoveryActivity.catalogueNames, WorkoutSport.recovery.map { it.name })
    }

    /** The pickers' two groups are exactly the catalogue, and no training sport is mistaken for recovery. */
    @Test fun trainingAndRecoveryGroups_partitionTheCatalogue() {
        assertEquals(WorkoutSport.all.size, WorkoutSport.training.size + WorkoutSport.recovery.size)
        assertTrue(WorkoutSport.training.none { RecoveryActivity.isRecovery(it.name) })
        assertEquals("Other", WorkoutSport.training.last().name)
        for (n in listOf("Yoga", "Pilates", "Running", "Ice skating", "Other")) {
            assertTrue("$n stays training", WorkoutSport.training.any { it.name == n })
        }
    }

    /** Only a recovery activity Health Connect has no real type for is kept out of it. */
    @Test fun healthConnectWriteback_skipsOnlyUntypedRecoveryActivities() {
        assertNull(WorkoutSport.healthConnectExerciseType("Sauna"))
        assertNull(WorkoutSport.healthConnectExerciseType("Hot bath"))
        assertNull(WorkoutSport.healthConnectExerciseType("Ice bath"))
        assertNull(WorkoutSport.healthConnectExerciseType("Massage"))
        assertNull(WorkoutSport.healthConnectExerciseType("Meditation"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING, WorkoutSport.healthConnectExerciseType("Stretching"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING, WorkoutSport.healthConnectExerciseType("Breathwork"))
        // Training is unchanged, the generic "Other" and a free-typed name included.
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, WorkoutSport.healthConnectExerciseType("Running"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, WorkoutSport.healthConnectExerciseType("Other"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, WorkoutSport.healthConnectExerciseType("Padel"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, WorkoutSport.healthConnectExerciseType("Birthday 5k"))
    }

    private fun row(sport: String) = WorkoutRow(
        deviceId = "my-whoop", startTs = 1_000, endTs = 4_600, sport = sport, source = "manual",
        durationS = 3_600.0, energyKcal = 410.0, avgHr = 112, maxHr = 131, strain = 9.4,
        zonesJSON = "{\"zone1\":40}", notes = "n",
    )

    /** A session saved, edited or relabelled INTO a recovery activity drops Effort, calories and zones,
     *  and keeps everything measured (heart rate) or typed (notes, time). */
    @Test fun withoutTrainingMetrics_stripsOnlyRecoveryRows() {
        val sauna = RecoveryActivityRows.withoutTrainingMetrics(row("Sauna"))
        assertNull(sauna.strain); assertNull(sauna.energyKcal); assertNull(sauna.zonesJSON)
        assertEquals(112, sauna.avgHr); assertEquals(131, sauna.maxHr)
        assertEquals("n", sauna.notes); assertEquals(3_600.0, sauna.durationS!!, 0.0)
        val imported = RecoveryActivityRows.withoutTrainingMetrics(row("IceBath"))
        assertNull(imported.strain)
        val run = row("Running")
        assertEquals(run, RecoveryActivityRows.withoutTrainingMetrics(run))
    }

    @Test fun partition_keepsOrder() {
        val rows = listOf(row("Running"), row("Sauna"), row("Yoga"), row("Hot bath"))
        val (training, recovery) = RecoveryActivityRows.partition(rows)
        assertEquals(listOf("Running", "Yoga"), training.map { it.sport })
        assertEquals(listOf("Sauna", "Hot bath"), recovery.map { it.sport })
    }
}
