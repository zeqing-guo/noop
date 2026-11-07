package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The Coach master switch decides whether the More page lists Coach.
 *
 * Coach left the bottom bar for More, so the switch now filters the More groups instead of the bar's
 * trailing tabs. The shared helper is pinned here so the page cannot filter one way and a future caller
 * another.
 */
class CoachTabVisibilityTest {

    private fun destinations(groups: List<DrawerGroup>) = groups.flatMap { it.items }

    @Test
    fun `coach enabled keeps the shipped more groups`() {
        assertEquals(drawerGroups, drawerGroupsFor(coachEnabled = true))
    }

    @Test
    fun `coach disabled drops the coach row and nothing else`() {
        val visible = drawerGroupsFor(coachEnabled = false)
        assertFalse("coach must not be listed", destinations(visible).contains(Destination.Coach))
        assertEquals(
            "only Coach may be removed",
            destinations(drawerGroups).filterNot { it == Destination.Coach },
            destinations(visible),
        )
        assertEquals("no group may disappear", drawerGroups.map { it.header }, visible.map { it.header })
    }

    @Test
    fun `the bar does not depend on the coach switch`() {
        // Workouts took Coach's slot, and the bar draws barTrailingTabs as-is.
        assertFalse(barTrailingTabs.any { it.dest == Destination.Coach })
        assertTrue(barTrailingTabs.any { it.dest == Destination.Workouts })
    }

}

/**
 * The stored default, which is the invariant that actually matters on upgrade.
 *
 * Separate from the filter tests because it needs a Context. The first version of this asserted
 * `BottomBarStyleStore.coachEnabled`, which is an in-memory singleton seeded to true -- that passes
 * whatever the PREF does, so it would not have noticed the one mistake worth catching here: a pref
 * default of false, which silently removes the Coach tab from every existing install on upgrade.
 */
@RunWith(RobolectricTestRunner::class)
class CoachEnabledPrefDefaultTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `an install that never touched the toggle has coach enabled`() {
        NoopPrefs.of(context).edit().remove(NoopPrefs.KEY_COACH_ENABLED).commit()
        assertTrue("unset must read as ON, or upgrades lose the coach", NoopPrefs.coachEnabled(context))
    }

    @Test
    fun `the stored value round-trips in both directions`() {
        NoopPrefs.setCoachEnabled(context, false)
        assertFalse(NoopPrefs.coachEnabled(context))
        NoopPrefs.setCoachEnabled(context, true)
        assertTrue(NoopPrefs.coachEnabled(context))
    }
}
