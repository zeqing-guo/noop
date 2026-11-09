package com.noop.ui

import com.noop.analytics.WorkoutSport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pickers show sport labels in the app language but store the catalogue's English name. */
class SportPickerLabelTest {

    /** Stand-in for the zh resources, so the rules run on the JVM without Android resources. */
    private val zh = mapOf("Sauna" to "桑拿", "Hot bath" to "泡澡", "Running" to "跑步", "Mountain biking" to "山地自行车")
    private val label: (String) -> String = { zh[it] ?: it }

    /** Every catalogue sport has a translated label, so no picker row falls back to English in zh. */
    @Test fun everyCatalogueSport_hasALabel() {
        val missing = WorkoutSport.all.map { it.name }.filterNot { WorkoutEditing.hasSportLabel(it) }
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun search_matchesEnglishNameOrLabel() {
        assertTrue(WorkoutEditing.sportMatchesQuery("Sauna", "sau", label))
        assertTrue(WorkoutEditing.sportMatchesQuery("Sauna", "桑拿", label))
        assertTrue(WorkoutEditing.sportMatchesQuery("Sauna", "桑", label))
        assertTrue(WorkoutEditing.sportMatchesQuery("Mountain biking", "自行车", label))
        assertTrue(WorkoutEditing.sportMatchesQuery("Sauna", "  ", label))
        assertFalse(WorkoutEditing.sportMatchesQuery("Sauna", "泡澡", label))
        assertFalse(WorkoutEditing.sportMatchesQuery("Running", "bath", label))
    }

    @Test fun canonicalName_storesTheCatalogueName() {
        assertEquals("Sauna", WorkoutEditing.canonicalSportName("桑拿", label))
        assertEquals("Hot bath", WorkoutEditing.canonicalSportName(" 泡澡 ", label))
        assertEquals("Sauna", WorkoutEditing.canonicalSportName("sauna", label))
        assertEquals("Running", WorkoutEditing.canonicalSportName("跑步", label))
        // Free text is kept as typed (trimmed).
        assertEquals("Birthday 5k", WorkoutEditing.canonicalSportName("  Birthday 5k ", label))
        assertEquals("", WorkoutEditing.canonicalSportName("   ", label))
    }
}
