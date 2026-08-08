package io.github.jamal_wia.kmptoolkit.platform.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceTextTest {

    @Test
    fun `uppercases a lowercase region code`() {
        assertEquals("UZ", normalizeCountryCode("uz"))
    }

    @Test
    fun `keeps an already uppercase region code`() {
        assertEquals("US", normalizeCountryCode("US"))
    }

    @Test
    fun `rejects null`() {
        assertNull(normalizeCountryCode(null))
    }

    @Test
    fun `rejects an empty string from a device with no region set`() {
        assertNull(normalizeCountryCode(""))
    }

    @Test
    fun `rejects a numeric UN M49 region code`() {
        // es-419 (Latin America) is a real locale a device can report; 419 is not ISO 3166-1.
        assertNull(normalizeCountryCode("419"))
    }

    @Test
    fun `rejects a three-letter code`() {
        assertNull(normalizeCountryCode("USA"))
    }

    @Test
    fun `rejects a two-character code that is not letters`() {
        assertNull(normalizeCountryCode("U1"))
        assertNull(normalizeCountryCode("  "))
    }

    @Test
    fun `prefixes the manufacturer when the model does not already carry it`() {
        assertEquals("Google Pixel 7", composeDeviceModel("Google", "Pixel 7"))
    }

    @Test
    fun `capitalizes a lowercase manufacturer`() {
        assertEquals("Google Pixel 7", composeDeviceModel("google", "Pixel 7"))
    }

    @Test
    fun `does not repeat a manufacturer the model already starts with`() {
        assertEquals("Samsung SM-G991B", composeDeviceModel("samsung", "Samsung SM-G991B"))
    }

    @Test
    fun `trims surrounding whitespace from both parts`() {
        assertEquals("Google Pixel 7", composeDeviceModel(" Google ", " Pixel 7 "))
    }

    @Test
    fun `falls back to the manufacturer when there is no model`() {
        assertEquals("Google", composeDeviceModel("Google", ""))
        assertEquals("Google", composeDeviceModel("Google", null))
    }

    @Test
    fun `falls back to the model when there is no manufacturer`() {
        assertEquals("Pixel 7", composeDeviceModel("", "Pixel 7"))
        assertEquals("Pixel 7", composeDeviceModel(null, "Pixel 7"))
    }

    @Test
    fun `reports unknown rather than an empty string when the platform says nothing`() {
        assertEquals("unknown", composeDeviceModel(null, null))
        assertEquals("unknown", composeDeviceModel("", ""))
        assertEquals("unknown", composeDeviceModel("  ", "  "))
    }
}
