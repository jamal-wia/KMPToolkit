package io.github.jamal_wia.kmptoolkit.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FontScaleTest {

    @Test
    fun `the default multiplier is one`() {
        assertEquals(1.0f, FontScale.DEFAULT.multiplier)
    }

    @Test
    fun `an arbitrary multiplier inside the range is kept exactly`() {
        assertEquals(1.15f, FontScale(1.15f).multiplier)
        assertEquals(1.3f, FontScale(1.3f).multiplier)
        assertEquals(2.375f, FontScale(2.375f).multiplier)
    }

    @Test
    fun `both ends of the range are accepted`() {
        assertEquals(0.5f, FontScale(FontScale.MINIMUM_MULTIPLIER).multiplier)
        assertEquals(3.0f, FontScale(FontScale.MAXIMUM_MULTIPLIER).multiplier)
    }

    @Test
    fun `a multiplier below the range is rejected at the call site`() {
        assertFailsWith<IllegalArgumentException> { FontScale(0.49f) }
    }

    @Test
    fun `a multiplier above the range is rejected at the call site`() {
        assertFailsWith<IllegalArgumentException> { FontScale(3.01f) }
    }

    @Test
    fun `a zero or negative multiplier is rejected`() {
        assertFailsWith<IllegalArgumentException> { FontScale(0f) }
        assertFailsWith<IllegalArgumentException> { FontScale(-1f) }
    }

    @Test
    fun `NaN and the infinities are rejected`() {
        assertFailsWith<IllegalArgumentException> { FontScale(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { FontScale(Float.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { FontScale(Float.NEGATIVE_INFINITY) }
    }

    @Test
    fun `of returns null instead of throwing for a value outside the range`() {
        assertNull(FontScale.of(0.1f))
        assertNull(FontScale.of(9.0f))
        assertNull(FontScale.of(Float.NaN))
    }

    @Test
    fun `of returns the scale for a value inside the range`() {
        assertEquals(FontScale(1.15f), FontScale.of(1.15f))
    }

    @Test
    fun `of accepts both ends of the range`() {
        assertEquals(FontScale(0.5f), FontScale.of(FontScale.MINIMUM_MULTIPLIER))
        assertEquals(FontScale(3.0f), FontScale.of(FontScale.MAXIMUM_MULTIPLIER))
    }

    @Test
    fun `coerced leaves both ends of the range alone`() {
        assertEquals(FontScale(0.5f), FontScale.coerced(FontScale.MINIMUM_MULTIPLIER))
        assertEquals(FontScale(3.0f), FontScale.coerced(FontScale.MAXIMUM_MULTIPLIER))
    }

    @Test
    fun `coerced clamps to the nearer end of the range`() {
        assertEquals(FontScale(FontScale.MINIMUM_MULTIPLIER), FontScale.coerced(0.1f))
        assertEquals(FontScale(FontScale.MAXIMUM_MULTIPLIER), FontScale.coerced(9.0f))
    }

    @Test
    fun `coerced keeps a value that is already in range`() {
        assertEquals(FontScale(1.15f), FontScale.coerced(1.15f))
    }

    @Test
    fun `coerced turns NaN into the default because it names no direction`() {
        assertEquals(FontScale.DEFAULT, FontScale.coerced(Float.NaN))
    }

    @Test
    fun `two scales with the same multiplier are equal`() {
        assertEquals(FontScale(1.15f), FontScale(1.15f))
        assertEquals(FontScale(1.15f).hashCode(), FontScale(1.15f).hashCode())
    }
}
