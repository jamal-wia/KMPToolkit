package io.github.jamal_wia.kmptoolkit.platform

import io.github.jamal_wia.kmptoolkit.platform.connectivity.ConnectivityObserver
import io.github.jamal_wia.kmptoolkit.platform.connectivity.ConnectivityStatus
import io.github.jamal_wia.kmptoolkit.platform.connectivity.createConnectivityObserver
import io.github.jamal_wia.kmptoolkit.platform.crash.CrashLogConfig
import io.github.jamal_wia.kmptoolkit.platform.crash.CrashLogStore
import io.github.jamal_wia.kmptoolkit.platform.crash.CrashRecord
import io.github.jamal_wia.kmptoolkit.platform.crash.createCrashLogStore
import io.github.jamal_wia.kmptoolkit.platform.device.DeviceInfo
import io.github.jamal_wia.kmptoolkit.platform.device.FormFactor
import io.github.jamal_wia.kmptoolkit.platform.device.createDeviceInfo
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.Foundation.NSTemporaryDirectory

class IosDeviceInfoTest {

    private val info: DeviceInfo = createDeviceInfo()

    @Test
    fun `reports iOS as the OS name`() {
        assertEquals("iOS", info.osName)
    }

    @Test
    fun `reports a non-empty OS version`() {
        assertTrue(info.osVersion.isNotEmpty())
    }

    @Test
    fun `reports the machine identifier as the model`() {
        // The simulator reports its host architecture (arm64/x86_64); a device reports iPhoneN,M.
        // Either way it must be a non-blank identifier and never the user-editable device name.
        assertTrue(info.model.isNotBlank(), "model was blank")
    }

    @Test
    fun `reports a form factor from the known set`() {
        assertTrue(info.formFactor in FormFactor.entries, "unexpected form factor")
    }

    @Test
    fun `reports either a two-letter region or none at all`() {
        val country: String? = info.currentCountry()

        if (country != null) {
            assertEquals(2, country.length, "expected an alpha-2 code but got $country")
            assertEquals(country.uppercase(), country, "the region code must be uppercase")
        }
    }
}

class IosCrashLogStoreTest {

    private val config = CrashLogConfig(
        fileName = "kmptoolkit_crash_log_test.txt",
        directoryPath = NSTemporaryDirectory(),
    )

    private fun store(): CrashLogStore = createCrashLogStore(config)

    private fun record(message: String, timestampMs: Long = 1) = CrashRecord(
        timestampMs = timestampMs,
        threadName = "native",
        message = message,
        stackTrace = "at A\nat B",
    )

    @Test
    fun `a written record comes back unchanged`() {
        val fresh: CrashLogStore = store()
        fresh.readAndClear()
        val written: CrashRecord = record("boom")

        fresh.write(written)

        assertEquals(listOf(written), fresh.readAndClear())
    }

    @Test
    fun `several crashes are kept in order`() {
        val fresh: CrashLogStore = store()
        fresh.readAndClear()

        fresh.write(record("first", 1))
        fresh.write(record("second", 2))

        assertContentEquals(listOf(record("first", 1), record("second", 2)), fresh.readAndClear())
    }

    @Test
    fun `reading clears so the same crash is never reported twice`() {
        val fresh: CrashLogStore = store()
        fresh.write(record("boom"))
        fresh.readAndClear()

        assertEquals(emptyList(), fresh.readAndClear())
    }

    @Test
    fun `a record written by one store is read by the next over the same file`() {
        store().let { first ->
            first.readAndClear()
            first.write(record("boom"))
        }

        assertEquals(listOf(record("boom")), store().readAndClear())
    }
}

class IosConnectivityObserverTest {

    @Test
    fun `starts unknown before the platform reports anything`() {
        val observer: ConnectivityObserver = createConnectivityObserver()
        try {
            assertTrue(
                observer.status.value in ConnectivityStatus.entries,
                "status must always be one of the known values",
            )
        } finally {
            observer.close()
        }
    }

    @Test
    fun `closing twice is harmless`() {
        val observer: ConnectivityObserver = createConnectivityObserver()

        observer.close()
        observer.close()
    }
}
