package io.github.jamal_wia.kmptoolkit.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The one line [forwardTestKnobs] prints per test task: nothing without a knob, every knob sorted by name with its value. */
class TestKnobsTest {

    @Test
    fun `no knob set prints nothing`() {
        assertNull(describeTestKnobs(emptyMap()))
    }

    @Test
    fun `every knob is named with its value and sorted by name`() {
        assertEquals(
            "kmptoolkit.test.a.trials=3, kmptoolkit.test.seed=42",
            describeTestKnobs(mapOf("kmptoolkit.test.seed" to "42", "kmptoolkit.test.a.trials" to "3")),
        )
    }

    @Test
    fun `an empty value is printed as empty and not dropped`() {
        assertEquals("kmptoolkit.test.hazards=", describeTestKnobs(mapOf("kmptoolkit.test.hazards" to "")))
    }
}
