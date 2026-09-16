package io.github.jamal_wia.kmptoolkit.systembars

import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests for the pure luminance → icon-style decision used by both strips of [AutoSystemBarsIconStyle]. */
class LuminanceToStyleTest {

    @Test
    fun `fully bright strip asks for dark icons`() {
        assertEquals(SystemBarIconStyle.DarkIcons, luminanceToStyle(1.0))
    }

    @Test
    fun `fully dark strip asks for light icons`() {
        assertEquals(SystemBarIconStyle.LightIcons, luminanceToStyle(0.0))
    }

    @Test
    fun `just above the midpoint asks for dark icons`() {
        assertEquals(SystemBarIconStyle.DarkIcons, luminanceToStyle(0.51))
    }

    @Test
    fun `just below the midpoint asks for light icons`() {
        assertEquals(SystemBarIconStyle.LightIcons, luminanceToStyle(0.49))
    }

    @Test
    fun `exactly the midpoint counts as dim and asks for light icons`() {
        // Boundary is strict greater-than, so 0.5 is treated as dim.
        assertEquals(SystemBarIconStyle.LightIcons, luminanceToStyle(0.5))
    }
}
