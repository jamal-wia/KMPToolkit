package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKey
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Android stores when the app is restarted or the DI graph is rebuilt over one preferences file:
 * several instances written from several threads, a device id requested through three providers at
 * once, a writer that is cancelled mid-write, and a second instance that replaces the key the first
 * one holds. Robolectric keeps `SharedPreferences` in process memory, so none of these reload the
 * file from disk; they hold the stores to what they promise about concurrent and repeated use.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidStorageAdversarialTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun uniqueName(prefix: String): String = "$prefix${UUID.randomUUID().toString().replace("-", "")}"

    // -- Two instances over one file, many writers --------------------------------------------------

    @Test
    fun `two instances over one name written from 8 threads lose no key and read the same values`() {
        val name: String = uniqueName("restart")
        val config = StorageConfig(name)
        val first: KeyValueStorage = createKeyValueStorage(context, config)
        val second: KeyValueStorage = createKeyValueStorage(context, config)

        val writers: List<Thread> = (0 until 8).map { writer: Int ->
            thread(name = "storage-writer-$writer") {
                val storage: KeyValueStorage = if (writer % 2 == 0) first else second
                runBlocking {
                    repeat(200) { index: Int ->
                        storage.put("w$writer-k$index", "value-$writer-$index")
                    }
                }
            }
        }
        writers.forEach { it.join(20_000) }
        assertTrue(writers.none { it.isAlive }, "a writer is still blocked after 20 s")

        val reader: KeyValueStorage = createKeyValueStorage(context, config)
        runBlocking {
            for (storage: KeyValueStorage in listOf(first, second, reader)) {
                for (writer: Int in 0 until 8) {
                    for (index: Int in 0 until 200) {
                        assertEquals(
                            "value-$writer-$index",
                            storage.get("w$writer-k$index").getOrNull(),
                            "key w$writer-k$index",
                        )
                    }
                }
            }
        }
    }

    // -- A device id through three providers over three instances ---------------------------------------

    private class FlakyReads(private val inner: KeyValueStorage, private val failing: AtomicBoolean) :
        KeyValueStorage by inner {
        override suspend fun get(key: String): StorageResult<String?> =
            if (failing.get()) StorageResult.Failure(StorageError.Unavailable()) else inner.get(key)
    }

    @Test
    fun `three providers over three instances of one file on three threads agree on one id for 50 rounds`() {
        val config = StorageConfig(uniqueName("deviceid"))
        repeat(50) { round: Int ->
            val key = "device-id-$round"
            val flaky = AtomicBoolean(round % 5 == 0)
            val providers: List<DeviceIdProvider> = listOf(
                DeviceIdProvider(createKeyValueStorage(context, config), key),
                DeviceIdProvider(createKeyValueStorage(context, config), key),
                DeviceIdProvider(FlakyReads(createKeyValueStorage(context, config), flaky), key),
            )
            val ids: Array<String?> = arrayOfNulls(3)
            val threads: List<Thread> = providers.mapIndexed { index: Int, provider: DeviceIdProvider ->
                thread(name = "device-id-$round-$index") { ids[index] = runBlocking { provider.current() } }
            }
            threads.forEach { it.join(10_000) }
            assertTrue(threads.none { it.isAlive }, "round $round: a provider never returned")

            val persisted: String? = runBlocking { createKeyValueStorage(context, config).get(key).getOrNull() }
            assertTrue(persisted != null, "round $round: no id was persisted")
            // A provider whose store could not be read answers a fresh id for that call only; the two
            // healthy providers and the persisted entry agree on one id for ever.
            assertEquals(persisted, ids[0], "round $round: provider 0 (persisted=$persisted, ids=${ids.toList()})")
            assertEquals(persisted, ids[1], "round $round: provider 1 (persisted=$persisted, ids=${ids.toList()})")
            if (!flaky.get()) assertEquals(persisted, ids[2], "round $round: provider 2")
            flaky.set(false)
            assertEquals(persisted, runBlocking { providers[2].current() }, "round $round: id stable after the fault")
        }
    }

    // -- A writer cancelled mid-write -----------------------------------------------------------------------

    @Test
    fun `a put cancelled right after it was launched leaves the value absent or whole never torn`() = runTest {
        val storage: KeyValueStorage = createKeyValueStorage(context, StorageConfig(uniqueName("cancel")))
        val whole: String = "x".repeat(64 * 1024)

        repeat(200) { round: Int ->
            val key = "k$round"
            withContext(Dispatchers.Default) {
                val writer = launch(start = CoroutineStart.UNDISPATCHED) { storage.put(key, whole) }
                writer.cancel()
                writer.join()
            }
            val stored: String? = withContext(Dispatchers.Default) {
                withTimeout(5_000) { storage.get(key).getOrNull() }
            }
            assertTrue(stored == null || stored == whole, "round $round: a torn value of ${stored?.length} chars")
        }
        // The store is not wedged by the cancelled writers.
        // A real-time bound: withTimeout directly under runTest would run on the virtual clock.
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                assertEquals(Unit, storage.put("after", "ok").getOrNull())
                assertEquals("ok", storage.get("after").getOrNull())
                assertNull(storage.get("missing").getOrNull())
            }
        }
    }

    // -- A second instance replaces the key the first one holds ------------------------------------------------

    /** A key that works until [kill] and then is refused by every cipher, as a key the platform destroyed. */
    private class MortalKey(private val real: SecretKey) : SecretKey {
        @Volatile
        var dead: Boolean = false

        override fun getAlgorithm(): String = real.algorithm
        override fun getFormat(): String = real.format
        override fun getEncoded(): ByteArray = if (dead) ByteArray(5) else real.encoded
    }

    private class SwitchableKeyStore(@Volatile var current: SecretKey) : AliasedKeyStore {
        override fun existingKey(alias: String, mayDelete: Boolean): SecretKey = current
        override fun generateKey(alias: String): SecretKey = error("an existing key is always answered")
    }

    @Test
    fun `an instance holding a key the platform destroyed recovers when another instance replaces it`() = runTest {
        val alias: String = uniqueName("b10.alias.")
        val preferences = context.getSharedPreferences(uniqueName("secure"), Context.MODE_PRIVATE)
        val mortal = MortalKey(TestKeys.generate())
        val replacement: SecretKey = TestKeys.generate()
        val keyStore = SwitchableKeyStore(mortal)

        fun instance(): AndroidSecureKeyValueStorage = AndroidSecureKeyValueStorage(
            preferences = preferences,
            cipher = KeystoreValueCipher(AndroidKeystoreKeySource(alias, keyStore)),
            dispatcher = Dispatchers.Unconfined,
        )

        val first: AndroidSecureKeyValueStorage = instance()
        assertIs<StorageResult.Success<Unit>>(first.put("old", "written-with-the-old-key"))

        // The platform destroys the key the first instance has cached; the alias now holds another.
        mortal.dead = true
        keyStore.current = replacement
        val second: AndroidSecureKeyValueStorage = instance()
        assertIs<StorageResult.Success<Unit>>(second.put("new", "written-by-the-second-instance"))

        // The first instance may report the old entry once as lost, never as another plaintext, and
        // it must read what the second wrote and write again: it is not broken for the process's life.
        val oldRead: StorageResult<String?> = first.get("old")
        assertTrue(
            oldRead is StorageResult.Failure &&
                (oldRead.error is StorageError.Undecryptable || oldRead.error is StorageError.Unavailable),
            "the entry of the destroyed key must read as lost, was $oldRead",
        )
        assertEquals("written-by-the-second-instance", first.get("new").getOrNull())
        assertIs<StorageResult.Success<Unit>>(first.put("again", "written-by-the-first-instance"))
        assertEquals("written-by-the-first-instance", second.get("again").getOrNull())
    }

    // -- Attacks beyond the catalogue ---------------------------------------------------------------------------

    @Test
    fun `64 coroutines asking one provider for the id at once all get the one persisted id`() = runTest {
        val storage: KeyValueStorage = createKeyValueStorage(context, StorageConfig(uniqueName("many")))
        val provider = DeviceIdProvider(storage, "many-id")

        val ids: List<String> = withContext(Dispatchers.Default) {
            withTimeout(10_000) { (0 until 64).map { async { provider.current() } }.awaitAll() }
        }

        assertEquals(1, ids.toSet().size, "ids: ${ids.toSet()}")
        assertEquals(ids.first(), storage.get("many-id").getOrNull())
    }

    @Test
    fun `clear racing 8 writers leaves a store that reads back whatever was written last and is not wedged`() {
        val storage: KeyValueStorage = createKeyValueStorage(context, StorageConfig(uniqueName("clear")))
        val writers: List<Thread> = (0 until 8).map { writer: Int ->
            thread(name = "clear-writer-$writer") {
                runBlocking {
                    repeat(100) { index: Int ->
                        storage.put("k$writer", "v$index")
                        if (writer == 0 && index % 10 == 0) storage.clear()
                    }
                }
            }
        }
        writers.forEach { it.join(20_000) }
        assertTrue(writers.none { it.isAlive }, "a writer is blocked")

        runBlocking {
            // Each surviving key holds a value that was really written, never a mixture.
            for (writer: Int in 0 until 8) {
                val value: String? = storage.get("k$writer").getOrNull()
                assertTrue(value == null || value.matches(Regex("v\\d+")), "k$writer holds $value")
            }
            storage.put("final", "ok")
            assertEquals("ok", storage.get("final").getOrNull())
        }
    }

    @Test
    fun `a get on a key written as another type from 8 threads reports a failure every time and never throws`() {
        val name: String = uniqueName("typed")
        context.getSharedPreferences(plainStoreId(name), Context.MODE_PRIVATE).edit().putInt("k", 7).commit()
        val storage: KeyValueStorage = createKeyValueStorage(context, StorageConfig(name))
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val crashes = java.util.concurrent.atomic.AtomicInteger()

        val readers: List<Thread> = (0 until 8).map {
            thread {
                runBlocking {
                    repeat(100) {
                        try {
                            if (storage.get("k") is StorageResult.Failure) failures.incrementAndGet()
                        } catch (error: Throwable) {
                            crashes.incrementAndGet()
                        }
                    }
                }
            }
        }
        readers.forEach { it.join(20_000) }

        assertEquals(0, crashes.get())
        assertEquals(800, failures.get())
    }

    private class HangsOnce(private val inner: KeyValueStorage) : KeyValueStorage by inner {
        val hang: AtomicBoolean = AtomicBoolean(true)
        val entered: kotlinx.coroutines.CompletableDeferred<Unit> = kotlinx.coroutines.CompletableDeferred()

        override suspend fun get(key: String): StorageResult<String?> {
            if (hang.getAndSet(false)) {
                entered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
            return inner.get(key)
        }
    }

    @Test
    fun `a device id caller cancelled while its read hangs does not wedge the process-wide lock for the next caller`() =
        runTest {
            val config = StorageConfig(uniqueName("wedge"))
            val hanging = HangsOnce(createKeyValueStorage(context, config))
            val first: kotlinx.coroutines.Job = launch(Dispatchers.Default) {
                DeviceIdProvider(hanging, "wedge-id").current()
            }
            withContext(Dispatchers.Default) { withTimeout(5_000) { hanging.entered.await() } }

            first.cancel()
            withContext(Dispatchers.Default) { withTimeout(5_000) { first.join() } }

            val next: String = withContext(Dispatchers.Default) {
                withTimeout(5_000) { DeviceIdProvider(createKeyValueStorage(context, config), "wedge-id").current() }
            }
            assertTrue(next.isNotBlank())
        }
}
