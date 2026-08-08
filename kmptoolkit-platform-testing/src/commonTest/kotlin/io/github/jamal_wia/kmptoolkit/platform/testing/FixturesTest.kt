package io.github.jamal_wia.kmptoolkit.platform.testing

import io.github.jamal_wia.kmptoolkit.platform.connectivity.ConnectivityStatus
import io.github.jamal_wia.kmptoolkit.platform.crash.CrashRecord
import io.github.jamal_wia.kmptoolkit.platform.device.FormFactor
import io.github.jamal_wia.kmptoolkit.platform.files.PickResult
import io.github.jamal_wia.kmptoolkit.platform.files.PickedFile
import io.github.jamal_wia.kmptoolkit.platform.url.UrlOpenResult
import io.github.jamal_wia.kmptoolkit.platform.wakelock.WakeLockResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FakeConnectivityObserverTest {

    @Test
    fun `starts unknown like a real observer does`() {
        assertEquals(ConnectivityStatus.UNKNOWN, FakeConnectivityObserver().status.value)
    }

    @Test
    fun `starts from the status it was given`() {
        assertEquals(
            ConnectivityStatus.ONLINE,
            FakeConnectivityObserver(ConnectivityStatus.ONLINE).status.value,
        )
    }

    @Test
    fun `emitting publishes the new status`() {
        val observer = FakeConnectivityObserver()

        observer.emit(ConnectivityStatus.OFFLINE)

        assertEquals(ConnectivityStatus.OFFLINE, observer.status.value)
    }

    @Test
    fun `records how many times it was closed`() {
        val observer = FakeConnectivityObserver()
        assertFalse(observer.isClosed)

        observer.close()
        observer.close()

        assertTrue(observer.isClosed)
        assertEquals(2, observer.closeCount)
    }

    @Test
    fun `still emits after closing so a test can prove a collector stopped`() {
        val observer = FakeConnectivityObserver()
        observer.close()

        observer.emit(ConnectivityStatus.OFFLINE)

        assertEquals(ConnectivityStatus.OFFLINE, observer.status.value)
    }
}

class FakeDeviceInfoTest {

    @Test
    fun `defaults to a phone with a region`() {
        val info = FakeDeviceInfo()

        assertEquals(FormFactor.PHONE, info.formFactor)
        assertEquals("US", info.currentCountry())
    }

    @Test
    fun `reports the values it was given`() {
        val info = FakeDeviceInfo(
            osName = "Android",
            osVersion = "14",
            model = "Pixel 7",
            formFactor = FormFactor.TABLET,
            country = null,
        )

        assertEquals("Android", info.osName)
        assertEquals("14", info.osVersion)
        assertEquals("Pixel 7", info.model)
        assertEquals(FormFactor.TABLET, info.formFactor)
        assertNull(info.currentCountry())
    }

    @Test
    fun `a changed region is visible to the next read`() {
        val info = FakeDeviceInfo(country = "US")

        info.country = "UZ"

        assertEquals("UZ", info.currentCountry())
    }
}

class FakeReducedMotionProbeTest {

    @Test
    fun `defaults to motion being allowed`() {
        assertFalse(FakeReducedMotionProbe().isReducedMotionEnabled())
    }

    @Test
    fun `counts reads so a test can prove the value is not cached`() {
        val probe = FakeReducedMotionProbe()

        probe.isReducedMotionEnabled()
        probe.isReducedMotionEnabled()

        assertEquals(2, probe.readCount)
    }

    @Test
    fun `a changed setting is visible to the next read`() {
        val probe = FakeReducedMotionProbe(enabled = false)

        probe.enabled = true

        assertTrue(probe.isReducedMotionEnabled())
    }
}

class RecordingUrlOpenerTest {

    @Test
    fun `records every url in order`() {
        val opener = RecordingUrlOpener()

        opener.open("https://a.example")
        opener.open("https://b.example")

        assertEquals(listOf("https://a.example", "https://b.example"), opener.openedUrls)
        assertEquals("https://b.example", opener.lastUrl)
    }

    @Test
    fun `reports no last url before anything was opened`() {
        assertNull(RecordingUrlOpener().lastUrl)
    }

    @Test
    fun `returns the configured result`() {
        val opener = RecordingUrlOpener(result = UrlOpenResult.NO_HANDLER)

        assertEquals(UrlOpenResult.NO_HANDLER, opener.open("myapp://x"))
    }

    @Test
    fun `records a url even when the result is a failure`() {
        val opener = RecordingUrlOpener(result = UrlOpenResult.FAILED)

        opener.open("https://a.example")

        assertEquals(listOf("https://a.example"), opener.openedUrls)
    }
}

class RecordingScreenWakeLockTest {

    @Test
    fun `is not held before anything is requested`() {
        assertFalse(RecordingScreenWakeLock().isHeld)
    }

    @Test
    fun `tracks the most recent request`() {
        val wakeLock = RecordingScreenWakeLock()

        wakeLock.setKeepScreenOn(true)
        assertTrue(wakeLock.isHeld)

        wakeLock.setKeepScreenOn(false)
        assertFalse(wakeLock.isHeld)
    }

    @Test
    fun `records repeated identical requests rather than collapsing them`() {
        val wakeLock = RecordingScreenWakeLock()

        wakeLock.setKeepScreenOn(true)
        wakeLock.setKeepScreenOn(true)

        assertEquals(listOf(true, true), wakeLock.requests)
    }

    @Test
    fun `returns the configured result`() {
        val wakeLock = RecordingScreenWakeLock(result = WakeLockResult.NO_ACTIVE_WINDOW)

        assertEquals(WakeLockResult.NO_ACTIVE_WINDOW, wakeLock.setKeepScreenOn(true))
    }
}

class FakeFilePickerTest {

    @Test
    fun `defaults to the outcome users produce most often`() = runTest {
        assertEquals(PickResult.Cancelled, FakeFilePicker().pick())
    }

    @Test
    fun `returns the configured result`() = runTest {
        val file = PickedFile("a.pdf", "application/pdf", byteArrayOf(1))
        val picker = FakeFilePicker(PickResult.Picked(file))

        assertEquals(PickResult.Picked(file), picker.pick())
    }

    @Test
    fun `records the filter of every call`() = runTest {
        val picker = FakeFilePicker()

        picker.pick(listOf("image/png"))
        picker.pick()

        assertEquals(listOf(listOf("image/png"), emptyList()), picker.requestedMimeTypes)
        assertEquals(2, picker.pickCount)
    }
}

class InMemoryCrashLogStoreTest {

    private fun record(message: String) = CrashRecord(1, "main", message, "trace")

    @Test
    fun `starts empty`() {
        assertEquals(emptyList(), InMemoryCrashLogStore().readAndClear())
    }

    @Test
    fun `starts from the records it was seeded with`() {
        val store = InMemoryCrashLogStore(listOf(record("previous")))

        assertEquals(listOf(record("previous")), store.readAndClear())
    }

    @Test
    fun `keeps written records in order`() {
        val store = InMemoryCrashLogStore()

        store.write(record("first"))
        store.write(record("second"))

        assertEquals(listOf(record("first"), record("second")), store.stored)
    }

    @Test
    fun `inspecting does not consume`() {
        val store = InMemoryCrashLogStore()
        store.write(record("boom"))

        assertEquals(1, store.stored.size)
        assertEquals(1, store.stored.size)
        assertEquals(listOf(record("boom")), store.readAndClear())
    }

    @Test
    fun `reading clears`() {
        val store = InMemoryCrashLogStore()
        store.write(record("boom"))

        store.readAndClear()

        assertEquals(emptyList(), store.readAndClear())
        assertEquals(2, store.readCount)
    }

    @Test
    fun `seeding adds without going through write`() {
        val store = InMemoryCrashLogStore()

        store.seed(record("previous"))

        assertEquals(listOf(record("previous")), store.stored)
    }
}
