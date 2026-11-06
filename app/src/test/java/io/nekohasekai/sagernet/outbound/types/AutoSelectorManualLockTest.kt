package io.nekohasekai.sagernet.outbound.types

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSelectorManualLockTest {

    @Test
    fun automaticModeAllowsEveryMember() {
        val selector = AutoSelector()
        selector.pinnedID = 42L
        selector.strictPinned = false

        assertTrue(selector.allowsMember(42L))
        assertTrue(selector.allowsMember(99L))
    }

    @Test
    fun manualModeAllowsOnlyThePinnedMember() {
        val selector = AutoSelector()
        selector.pinnedID = 42L
        selector.strictPinned = true

        assertTrue(selector.allowsMember(42L))
        assertFalse(selector.allowsMember(99L))
    }

    @Test
    fun strictManualLockSurvivesSelectorPersistence() {
        val selector = AutoSelector()
        selector.pinnedID = 42L
        selector.strictPinned = true

        val restored = AutoSelector()
        assertTrue(restored.parseFromJson(selector.exportToJson()))
        assertEquals(42L, restored.pinnedID)
        assertTrue(restored.strictPinned)
        assertFalse(restored.allowsMember(99L))
    }

    @Test
    fun confirmedChangeExcludesOnlyTheRememberedMemberOnce() {
        val selector = AutoSelector()
        selector.pinnedID = 42L
        selector.strictPinned = false
        selector.excludeProfileIdOnce = 42L

        assertFalse(selector.allowsMember(42L))
        assertTrue(selector.allowsMember(99L))
    }

    @Test
    fun oneShotExclusionSurvivesSelectorPersistence() {
        val selector = AutoSelector()
        selector.pinnedID = 42L
        selector.strictPinned = false
        selector.excludeProfileIdOnce = 42L

        val restored = AutoSelector()
        assertTrue(restored.parseFromJson(selector.exportToJson()))
        assertEquals(42L, restored.excludeProfileIdOnce)
        assertFalse(restored.allowsMember(42L))
        assertTrue(restored.allowsMember(99L))
    }
}
