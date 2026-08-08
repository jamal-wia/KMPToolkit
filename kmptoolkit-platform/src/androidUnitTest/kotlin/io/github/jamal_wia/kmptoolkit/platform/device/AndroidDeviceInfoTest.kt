package io.github.jamal_wia.kmptoolkit.platform.device

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class AndroidDeviceInfoTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val defaultLocale: Locale = Locale.getDefault()

    @AfterTest
    fun tearDown() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `reports Android as the OS name`() {
        assertEquals("Android", createDeviceInfo(context).osName)
    }

    @Test
    fun `reports a non-empty OS version and model`() {
        val info: DeviceInfo = createDeviceInfo(context)

        assertTrue(info.osVersion.isNotEmpty())
        assertTrue(info.model.isNotEmpty())
    }

    @Test
    fun `reads the device region live rather than caching it`() {
        val info: DeviceInfo = createDeviceInfo(context)
        Locale.setDefault(Locale.US)
        assertEquals("US", info.currentCountry())

        Locale.setDefault(Locale("uz", "UZ"))

        assertEquals(
            "UZ",
            info.currentCountry(),
            "the region must be re-read, since the user can change it while the app runs",
        )
    }

    @Test
    fun `reports no region when the locale carries none`() {
        Locale.setDefault(Locale("eo"))

        assertNull(createDeviceInfo(context).currentCountry())
    }

    @Test
    @Config(qualifiers = "sw320dp")
    fun `a small screen is a phone`() {
        assertEquals(FormFactor.PHONE, createDeviceInfo(context).formFactor)
    }

    @Test
    @Config(qualifiers = "sw599dp")
    fun `the widest phone is still a phone`() {
        assertEquals(FormFactor.PHONE, createDeviceInfo(context).formFactor)
    }

    @Test
    @Config(qualifiers = "sw600dp")
    fun `the 600dp boundary is a tablet`() {
        assertEquals(FormFactor.TABLET, createDeviceInfo(context).formFactor)
    }

    @Test
    @Config(qualifiers = "sw720dp")
    fun `a large screen is a tablet`() {
        assertEquals(FormFactor.TABLET, createDeviceInfo(context).formFactor)
    }

    @Test
    @Config(qualifiers = "sw320dp-land")
    fun `orientation does not change the form factor`() {
        assertEquals(
            FormFactor.PHONE,
            createDeviceInfo(context).formFactor,
            "form factor is a property of the hardware, not of how the device is held",
        )
    }
}
