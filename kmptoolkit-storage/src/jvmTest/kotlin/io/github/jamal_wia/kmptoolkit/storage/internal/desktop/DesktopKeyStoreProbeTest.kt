package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.DesktopKeyStore
import io.github.jamal_wia.kmptoolkit.storage.DesktopSecureStorageOptions
import io.github.jamal_wia.kmptoolkit.storage.StorageConfig
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.SecureStorageProtection
import io.github.jamal_wia.kmptoolkit.storage.UnsupportedReason
import io.github.jamal_wia.kmptoolkit.storage.createSecureKeyValueStorage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * `probeDesktopKeyStore` (T6 probe rows, T12): the answer per OS and per status, never a key read, created or deleted, never a
 * prompt, bounded when the OS hangs. The probe runs through [DesktopKeyStoreProbe] with the fake binding.
 */
class DesktopKeyStoreProbeTest {

    private val executor = NativeExecutor()
    private val fake = FakeNativeKeyStoreBinding()
    private var sessionBus: Boolean = true

    @AfterTest
    fun closeExecutor() {
        executor.close()
    }

    private fun probe(os: DesktopOs, source: BindingSource? = null): DesktopKeyStoreProbe =
        DesktopKeyStoreProbe(
            source ?: FixedBindingSource(BindingSelection.Selected(fake, os, BindingKind.FAKE)),
            executor,
            SecureStoreEnvironment(getenv = { name: String -> if (name == "DBUS_SESSION_BUS_ADDRESS" && sessionBus) "unix:path=/x" else null }, exists = { false }),
        )

    private suspend fun DesktopKeyStoreProbe.answer(allowUi: Boolean = true): StorageResult<DesktopKeyStore> =
        probe(DesktopSecureStorageOptions(allowUserInteraction = allowUi))

    private val keyCalls: Set<String> = setOf("copyGenericPassword", "addGenericPassword", "deleteGenericPassword", "lookup", "store", "clear")

    private fun assertNoKeyTouched() {
        assertTrue(fake.callNames.none { it in keyCalls }, "the probe touched a key: ${fake.callNames}")
    }

    @Test
    fun `macOS answers the file keychain for success and not-found, and never reads, adds or deletes`() = runBlocking {
        for (allowUi: Boolean in listOf(true, false)) {
            assertEquals(StorageResult.Success(DesktopKeyStore.MACOS_FILE_KEYCHAIN), probe(DesktopOs.MACOS).answer(allowUi))
            fake.forcedStatus["probe"] = 0
            assertEquals(StorageResult.Success(DesktopKeyStore.MACOS_FILE_KEYCHAIN), probe(DesktopOs.MACOS).answer(allowUi))
            fake.forcedStatus.clear()
        }
        assertNoKeyTouched()
        assertTrue(fake.calls.all { it.name == "probe" && it.detail == "ui=false" }, "the probe never lets the OS prompt: ${fake.calls}")
    }

    @Test
    fun `macOS rows - no keychain is Unsupported, a locked keychain depends on interaction, anything else is OperationFailed ENSURE_KEY`() = runBlocking {
        fake.forcedStatus["probe"] = -25307
        assertEquals(StorageError.Unsupported(UnsupportedReason.NO_KEY_STORE), (probe(DesktopOs.MACOS).answer() as StorageResult.Failure).error)
        fake.forcedStatus["probe"] = -25308
        assertEquals(StorageResult.Success(DesktopKeyStore.MACOS_FILE_KEYCHAIN), probe(DesktopOs.MACOS).answer(allowUi = true))
        assertIs<StorageError.Unavailable>((probe(DesktopOs.MACOS).answer(allowUi = false) as StorageResult.Failure).error)
        fake.forcedStatus["probe"] = -77
        assertEquals(
            StorageError.OperationFailed(StorageOperation.ENSURE_KEY, key = null, platformCode = -77),
            (probe(DesktopOs.MACOS).answer() as StorageResult.Failure).error,
        )
    }

    @Test
    fun `Windows answers DPAPI after a one-byte round trip and no session is Unsupported`() = runBlocking {
        assertEquals(StorageResult.Success(DesktopKeyStore.WINDOWS_DPAPI), probe(DesktopOs.WINDOWS).answer())
        assertEquals(listOf("protect", "unprotect"), fake.callNames)
        assertEquals("1 bytes", fake.calls.first().detail)

        fake.dpapiNoSession = true
        assertEquals(StorageError.Unsupported(UnsupportedReason.NO_USER_SESSION), (probe(DesktopOs.WINDOWS).answer() as StorageResult.Failure).error)
        fake.dpapiNoSession = false
        fake.forcedStatus["protect"] = 5
        assertIs<StorageError.Unavailable>((probe(DesktopOs.WINDOWS).answer() as StorageResult.Failure).error)
        Unit
    }

    @Test
    fun `Linux rows - session bus, locked collection, portal and a failing service`() = runBlocking {
        assertEquals(StorageResult.Success(DesktopKeyStore.LINUX_SECRET_SERVICE), probe(DesktopOs.LINUX).answer())
        fake.portal = true
        assertEquals(StorageResult.Success(DesktopKeyStore.LINUX_SECRET_PORTAL), probe(DesktopOs.LINUX).answer())
        fake.defaultCollectionIsLocked = true
        assertEquals(StorageResult.Success(DesktopKeyStore.LINUX_SECRET_PORTAL), probe(DesktopOs.LINUX).answer(allowUi = true))
        assertIs<StorageError.Unavailable>((probe(DesktopOs.LINUX).answer(allowUi = false) as StorageResult.Failure).error)
        assertNoKeyTouched()

        fake.forcedSecretStatus["defaultCollectionLocked"] = RawStatus(1, errorName = "org.freedesktop.DBus.Error.ServiceUnknown")
        assertEquals(StorageError.Unsupported(UnsupportedReason.NO_KEY_STORE), (probe(DesktopOs.LINUX).answer() as StorageResult.Failure).error)
        fake.forcedSecretStatus.clear()

        sessionBus = false
        val callsBefore: Int = fake.callNames.size
        assertEquals(StorageError.Unsupported(UnsupportedReason.NO_USER_SESSION), (probe(DesktopOs.LINUX).answer() as StorageResult.Failure).error)
        assertEquals(callsBefore, fake.callNames.size, "no native call without a session bus")
    }

    @Test
    fun `an unusable binding is the selector's typed answer and a LinkageError is NATIVE_ACCESS_UNAVAILABLE`() = runBlocking {
        val none = FixedBindingSource(BindingSelection.Unsupported(StorageError.Unsupported(UnsupportedReason.UNSUPPORTED_OS)))
        assertEquals(StorageError.Unsupported(UnsupportedReason.UNSUPPORTED_OS), (probe(DesktopOs.MACOS, source = none).answer() as StorageResult.Failure).error)

        fake.before = { throw NoSuchMethodError("Linker.nativeLinker") }
        val error: StorageError = (probe(DesktopOs.MACOS).answer() as StorageResult.Failure).error
        assertEquals(UnsupportedReason.NATIVE_ACCESS_UNAVAILABLE, assertIs<StorageError.Unsupported>(error).reason)
    }

    @Test
    fun `a hung operating system answers Unavailable at the limit and the executor keeps one thread`() = runTest {
        val hang = CountDownLatch(1)
        val entered = CountDownLatch(1)
        fake.before = { name: String ->
            if (name == "probe") {
                entered.countDown()
                assertTrue(hang.await(60, TimeUnit.SECONDS), "the test never released the hung probe")
            }
        }

        val answer: StorageResult<DesktopKeyStore> = probe(DesktopOs.MACOS).answer()

        assertIs<StorageError.Unavailable>((answer as StorageResult.Failure).error)
        assertEquals(0L, entered.count, "control: the native call was running")
        hang.countDown()
    }

    @Test
    fun `cancelling a probe returns promptly and the late result is discarded`() = runBlocking {
        val hang = CountDownLatch(1)
        val entered = CountDownLatch(1)
        fake.before = { name: String ->
            if (name == "probe") {
                entered.countDown()
                assertTrue(hang.await(60, TimeUnit.SECONDS), "the test never released the hung probe")
            }
        }
        val caller = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) { probe(DesktopOs.MACOS).answer() }
        withContext(Dispatchers.IO) { assertTrue(entered.await(60, TimeUnit.SECONDS), "the probe never reached the binding") }

        caller.cancelAndJoin()
        assertFailsWith<CancellationException> { caller.await() }

        hang.countDown()
        fake.before = {}
        assertEquals(StorageResult.Success(DesktopKeyStore.MACOS_FILE_KEYCHAIN), probe(DesktopOs.MACOS).answer())
    }

    @Test
    fun `every factory and option reports its protection without any call`() {
        val directory = java.nio.file.Files.createTempDirectory("kmptoolkit-protection").toFile()
        try {
            for (options: DesktopSecureStorageOptions in listOf(DesktopSecureStorageOptions(), DesktopSecureStorageOptions("label", false))) {
                val store = createSecureKeyValueStorage(directory, StorageConfig("n"), options)
                assertEquals(SecureStorageProtection.OS_KEY_STORE_USER_SCOPED, store.protection)
            }
            assertEquals(0, fake.callNames.size)
            assertTrue(directory.list()!!.isEmpty(), "nothing is created before the first call")
            assertFailsWith<IllegalArgumentException> { createSecureKeyValueStorage(directory, StorageConfig(null)) }
        } finally {
            directory.deleteRecursively()
        }
    }
}
