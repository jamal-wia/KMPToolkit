package io.github.jamal_wia.kmptoolkit.proximity

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one subtle branch in this module: a binary sensor answers either zero or its own maximum, and
 * that maximum differs per unit — so "near" must be judged against the sensor's range, not a flat
 * threshold.
 */
class ProximityRuleTest {

    @Test
    fun `a binary sensor reporting its own maximum is nothing near the screen`() {
        // The common shape: the sensor answers either 0 or its maximum, and its maximum IS five.
        assertFalse(ProximityRule.isNear(distanceCm = 5f, maxRangeCm = 5f))
        assertTrue(ProximityRule.isNear(distanceCm = 0f, maxRangeCm = 5f))
    }

    @Test
    fun `a sensor whose maximum is under the threshold still reports far`() {
        // Some sensors answer 0 or 1. Comparing against a flat five would call "far" near.
        assertFalse(ProximityRule.isNear(distanceCm = 1f, maxRangeCm = 1f))
        assertTrue(ProximityRule.isNear(distanceCm = 0f, maxRangeCm = 1f))
    }

    @Test
    fun `a sensor that reports real centimetres uses the threshold`() {
        assertTrue(ProximityRule.isNear(distanceCm = 2f, maxRangeCm = 100f))
        assertFalse(ProximityRule.isNear(distanceCm = 30f, maxRangeCm = 100f))
        // Exactly at the threshold is not near — the sensor is reporting distance, not contact.
        assertFalse(ProximityRule.isNear(distanceCm = ProximityRule.NEAR_CM, maxRangeCm = 100f))
    }
}
