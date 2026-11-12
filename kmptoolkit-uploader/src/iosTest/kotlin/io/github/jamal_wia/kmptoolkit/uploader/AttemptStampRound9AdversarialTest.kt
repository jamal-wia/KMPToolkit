package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Round 9 (A2), mutation survivors: the label a hand-off puts on its session task (the attempt the upload answers) and the
 * reading of it in a relaunched process. Only a label this transport wrote reads as a stamp; anything else, including a
 * negative or overflowing number, is a task without one and its answer is journaled unstamped.
 */
class AttemptStampRound9AdversarialTest {

    private val prefix = attemptTaskDescription(0).removeSuffix("0")

    @Test
    fun the_label_round_trips_every_attempt_a_row_can_hold() {
        for (attempt in listOf(0, 1, 2, 9, 10, 255, 65_536, Int.MAX_VALUE - 1, Int.MAX_VALUE)) {
            assertEquals(attempt, attemptStampOf(attemptTaskDescription(attempt)), "attempt $attempt")
        }
    }

    @Test
    fun anything_that_is_not_a_label_of_this_transport_reads_as_no_stamp() {
        val foreign: List<String?> = listOf(
            null, "", " ", "x", "attempt:3", prefix, prefix + "-1", prefix + "-0".replace("-0", "-2147483648"), prefix + "abc", prefix + "1.5",
            prefix + " 5", prefix + "5 ", prefix + "2147483648", prefix + "99999999999999999999", prefix.uppercase() + "5", "x" + prefix + "5",
            prefix.dropLast(1) + "5",
        )
        for (description in foreign) {
            assertNull(attemptStampOf(description), "'$description' read as a stamp")
        }
    }
}
