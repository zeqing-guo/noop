package com.noop.ingest

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.units.Pressure
import com.noop.analytics.LabBookProjection
import com.noop.analytics.LabMarkerCategory
import com.noop.ingest.HealthConnectImporter.ImportCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Health Connect blood pressure -> Lab Book. Pins the mapping from one [BloodPressureRecord] to the
 * systolic/diastolic row pair the Lab Book reads, and the opt-in category that gates the read.
 */
class HealthConnectBloodPressureImportTest {

    private val phoneZone: ZoneId = ZoneId.of("UTC")

    private fun reading(
        at: String,
        systolic: Double,
        diastolic: Double,
        offset: ZoneOffset? = ZoneOffset.UTC,
    ) = BloodPressureRecord(
        time = Instant.parse(at),
        zoneOffset = offset,
        systolic = Pressure.millimetersOfMercury(systolic),
        diastolic = Pressure.millimetersOfMercury(diastolic),
    )

    /** One reading becomes exactly the two keys the editor and the CSV importer write, in mmHg. */
    @Test
    fun oneReadingBecomesTheSystolicDiastolicPair() {
        val rows = HealthConnectImporter.bloodPressureLabRows(
            reading("2026-11-07T08:15:00Z", 128.0, 84.0), phoneZone,
        )
        assertEquals(
            listOf(LabBookProjection.BP_SYSTOLIC_KEY, LabBookProjection.BP_DIASTOLIC_KEY),
            rows.map { it.markerKey },
        )
        assertEquals(listOf(128.0, 84.0), rows.map { it.value })
        rows.forEach {
            assertEquals("mmHg", it.unit)
            assertEquals(LabMarkerCategory.BLOOD_PRESSURE.raw, it.category)
            assertEquals(null, it.valueText)
        }
    }

    /**
     * Stored under the id the Lab Book screen reads ("my-whoop"); anything else is written and never
     * shown. The source keeps it apart from a hand-entered reading at the same instant.
     */
    @Test
    fun storedWhereTheLabBookReadsWithItsOwnSource() {
        val rows = HealthConnectImporter.bloodPressureLabRows(
            reading("2026-11-07T08:15:00Z", 120.0, 80.0), phoneZone,
        )
        rows.forEach {
            assertEquals("my-whoop", it.deviceId)
            assertEquals("health-connect", it.source)
            assertNotEquals("manual", it.source)
        }
    }

    /** The reading's own instant, not the CSV path's local-noon stand-in. */
    @Test
    fun takenAtIsTheReadingsInstant() {
        val at = Instant.parse("2026-11-07T08:15:42Z")
        val rows = HealthConnectImporter.bloodPressureLabRows(
            reading("2026-11-07T08:15:42Z", 120.0, 80.0), phoneZone,
        )
        rows.forEach { assertEquals(at.epochSecond, it.takenAt) }
    }

    /**
     * #1002: keyed by the offset the reading was taken in. 16:30Z is 01:30 the next day in Tokyo, so a
     * reading taken there belongs to the 8th even though the phone is now on UTC.
     */
    @Test
    fun dayIsKeyedByTheReadingsOwnOffset() {
        val tokyo = HealthConnectImporter.bloodPressureLabRows(
            reading("2026-11-07T16:30:00Z", 120.0, 80.0, offset = ZoneOffset.ofHours(9)), phoneZone,
        )
        tokyo.forEach { assertEquals("2026-11-08", it.day) }
    }

    /** No recorded offset falls back to the phone's zone, like every other record the importer reads. */
    @Test
    fun missingOffsetFallsBackToThePhoneZone() {
        val rows = HealthConnectImporter.bloodPressureLabRows(
            reading("2026-11-07T16:30:00Z", 120.0, 80.0, offset = null), phoneZone,
        )
        rows.forEach { assertEquals("2026-11-07", it.day) }
    }

    /**
     * A re-import of the same reading produces the same ids, so it replaces the row in place instead of
     * minting a new id under it; the two halves of one reading never share an id.
     */
    @Test
    fun idsAreStableAcrossImportsAndDistinctPerKey() {
        val r = reading("2026-11-07T08:15:00Z", 120.0, 80.0)
        val first = HealthConnectImporter.bloodPressureLabRows(r, phoneZone).map { it.id }
        val again = HealthConnectImporter.bloodPressureLabRows(r, phoneZone).map { it.id }
        assertEquals(first, again)
        assertEquals(2, first.toSet().size)
    }

    /** Must match the Manifest declaration, or Health Connect silently drops the request (#412). */
    @Test
    fun categoryRequestsTheDeclaredReadPermission() {
        assertEquals(
            setOf("android.permission.health.READ_BLOOD_PRESSURE"),
            HealthConnectImporter.permissionsFor(setOf(ImportCategory.BLOOD_PRESSURE)),
        )
    }

    /**
     * Opt-in: not part of the default selection, and not inside Recovery, so adding it never re-prompts
     * an existing user whose Recovery category is already enabled.
     */
    @Test
    fun isOptInAndOutsideRecovery() {
        val bp = HealthPermission.getReadPermission(BloodPressureRecord::class)
        assertFalse(ImportCategory.BLOOD_PRESSURE in HealthConnectImporter.DEFAULT_CATEGORIES)
        assertFalse(bp in HealthConnectImporter.permissionsFor(setOf(ImportCategory.RECOVERY)))
        assertTrue(
            HealthConnectImporter.unaskedPermissions(
                asked = HealthConnectImporter.permissionsFor(setOf(ImportCategory.RECOVERY)),
                categories = setOf(ImportCategory.RECOVERY),
            ).isEmpty(),
        )
    }

    /** A user who granted it in Health Connect settings gets the category back from that grant. */
    @Test
    fun grantRecoversTheCategory() {
        assertEquals(
            setOf(ImportCategory.BLOOD_PRESSURE),
            HealthConnectImporter.categoriesFromGrantedPermissions(
                setOf(HealthPermission.getReadPermission(BloodPressureRecord::class)),
            ),
        )
    }
}
