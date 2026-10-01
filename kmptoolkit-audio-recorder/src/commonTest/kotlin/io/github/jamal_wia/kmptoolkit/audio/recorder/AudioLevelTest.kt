package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The mapping from a dBFS peak to the `0f..1f` value [AudioRecorder.level] publishes, as the
 * property's documentation states it: the floor is `0f`, full scale is `1f`, linear in between.
 */
class AudioLevelTest {

    @Test
    fun `digital silence is the bottom of the meter`() {
        assertEquals(0f, normalizedLevel(Float.NEGATIVE_INFINITY, floorDbfs = -50f))
    }

    @Test
    fun `the floor itself is the bottom of the meter`() {
        assertEquals(0f, normalizedLevel(-50f, floorDbfs = -50f))
    }

    @Test
    fun `anything quieter than the floor is clamped to the bottom`() {
        assertEquals(0f, normalizedLevel(-50.5f, floorDbfs = -50f))
        assertEquals(0f, normalizedLevel(-120f, floorDbfs = -50f))
    }

    @Test
    fun `full scale is the top of the meter`() {
        assertEquals(1f, normalizedLevel(0f, floorDbfs = -50f))
    }

    @Test
    fun `anything louder than full scale is clamped to the top`() {
        assertEquals(1f, normalizedLevel(3f, floorDbfs = -50f))
        assertEquals(1f, normalizedLevel(Float.POSITIVE_INFINITY, floorDbfs = -50f))
    }

    @Test
    fun `halfway between the floor and full scale is half the meter`() {
        assertEquals(0.5f, normalizedLevel(-25f, floorDbfs = -50f))
        assertEquals(0.5f, normalizedLevel(-30f, floorDbfs = -60f))
    }

    @Test
    fun `the scale is linear in decibels`() {
        assertEquals(0.2f, normalizedLevel(-40f, floorDbfs = -50f), absoluteTolerance = 1e-6f)
        assertEquals(0.8f, normalizedLevel(-10f, floorDbfs = -50f), absoluteTolerance = 1e-6f)
    }

    @Test
    fun `a different floor moves the bottom of the meter`() {
        assertEquals(0f, normalizedLevel(-40f, floorDbfs = -40f))
        assertEquals(0.5f, normalizedLevel(-20f, floorDbfs = -40f))
    }

    @Test
    fun `a value that is not a number reads as silence`() {
        assertEquals(0f, normalizedLevel(Float.NaN, floorDbfs = -50f))
    }
}
