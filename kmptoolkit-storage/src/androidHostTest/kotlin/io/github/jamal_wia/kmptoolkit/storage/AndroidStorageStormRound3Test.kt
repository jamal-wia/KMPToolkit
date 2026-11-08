package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKey
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Android stores under storms from many real threads over several instances of one preferences
 * file: put, get, remove and clear together, a key that is replaced while values are written, and a
 * device id requested by callers that are cancelled while they wait.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidStorageStormRound3Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun uniqueName(prefix: String): String = "$prefix${UUID.randomUUID().toString().replace("-", "")}"

    private fun joinAll(threads: List<Thread>, what: String) {
        val deadline: Long = System.currentTimeMillis() + 60_000
        threads.forEach { it.join(maxOf(1L, deadline - System.currentTimeMillis())) }
        assertTrue(threads.none { it.isAlive }, "$what: a thread is still blocked after 60 s")
    }

    private fun stormOver(instances: List<KeyValueStorage>, what: String) {
        val failures: MutableList<String> = CopyOnWriteArrayList()
        val clearing = AtomicBoolean(true)
        val go = CountDownLatch(1)
        fun check(op: String, result: StorageResult<*>) {
            if (result is StorageResult.Failure) failures += "$op: ${result.error}"
        }
        val clearer = thread(name = "clearer") {
            go.await()
            runBlocking {
                var spins = 0
                while (clearing.get() && spins < 400) {
                    check("clear", instances[spins % instances.size].clear())
                    spins++
                }
            }
        }
        val workers: List<Thread> = (0 until 8).map { worker: Int ->
            thread(name = "worker-$worker") {
                go.await()
                runBlocking {
                    val storage: KeyValueStorage = instances[worker % instances.size]
                    repeat(150) { index: Int ->
                        val value = "w$worker-$index-" + "é".repeat(index % 40)
                        check("put", storage.put("k$worker", value))
                        val read: StorageResult<String?> = storage.get("k$worker")
                        check("get", read)
                        val seen: String? = read.getOrNull()
                        // Only this worker writes its key: what is there is one of its own values, or nothing (cleared).
                        if (seen != null && !seen.startsWith("w$worker-")) failures += "get: foreign value '$seen'"
                        if (index % 7 == 0) check("remove", storage.remove("k$worker"))
                    }
                }
            }
        }
        go.countDown()
        joinAll(workers, "$what workers")
        clearing.set(false)
        joinAll(listOf(clearer), "$what clearer")
        assertEquals(emptyList(), failures.take(5), "$what: ${failures.size} operations failed")
        runBlocking {
            for (worker: Int in 0 until 8) {
                assertEquals(StorageResult.Success(Unit), instances[0].put("final$worker", "x$worker"), what)
            }
            for (worker: Int in 0 until 8) {
                assertEquals("x$worker", instances[instances.size - 1].get("final$worker").getOrNull(), what)
            }
        }
    }

    @Test
    fun `plain put get remove and clear through three instances of one file never fail and never mix values`() {
        val config = StorageConfig(uniqueName("plainstorm"))
        stormOver(List(3) { createKeyValueStorage(context, config) }, "plain")
    }

    @Test
    fun `secure put get remove and clear through three instances of one file never fail and never mix values`() {
        val name: String = uniqueName("securestorm")
        val key: SecretKey = TestKeys.generate()
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val instances: List<KeyValueStorage> = List(3) {
            AndroidSecureKeyValueStorage(
                preferences = preferences,
                cipher = KeystoreValueCipher(TestKeys.fixedSource(key)),
                dispatcher = Dispatchers.IO,
            )
        }
        stormOver(instances, "secure")
    }

    // -- A key replaced while values are written -------------------------------------------------------------

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
    fun `two secure instances writing while the platform replaces the key never return a foreign plaintext and recover afterwards`() {
        val alias: String = uniqueName("storm.alias.")
        val preferences = context.getSharedPreferences(uniqueName("rotate"), Context.MODE_PRIVATE)
        var mortal = MortalKey(TestKeys.generate())
        val keyStore = SwitchableKeyStore(mortal)

        fun instance(): KeyValueStorage = AndroidSecureKeyValueStorage(
            preferences = preferences,
            cipher = KeystoreValueCipher(AndroidKeystoreKeySource(alias, keyStore)),
            dispatcher = Dispatchers.IO,
        )

        val instances: List<KeyValueStorage> = listOf(instance(), instance())
        val violations: MutableList<String> = CopyOnWriteArrayList()
        val rotating = AtomicBoolean(true)
        val rotator = thread(name = "rotator") {
            repeat(250) {
                val previous: MortalKey = mortal
                val next = MortalKey(TestKeys.generate())
                mortal = next
                keyStore.current = next
                previous.dead = true
                Thread.sleep(1)
            }
            rotating.set(false)
        }
        val workers: List<Thread> = (0 until 4).map { worker: Int ->
            thread(name = "rotating-worker-$worker") {
                runBlocking {
                    val storage: KeyValueStorage = instances[worker % 2]
                    var index = 0
                    while (rotating.get() && index < 2_000) {
                        val value = "w$worker-$index"
                        storage.put("k$worker", value)
                        val read: StorageResult<String?> = storage.get("k$worker")
                        val seen: String? = read.getOrNull()
                        if (seen != null && !seen.startsWith("w$worker-")) violations += "foreign plaintext '$seen'"
                        if (read is StorageResult.Failure && read.error !is StorageError.Undecryptable &&
                            read.error !is StorageError.Unavailable
                        ) {
                            violations += "read failed as ${read.error}"
                        }
                        index++
                    }
                }
            }
        }
        joinAll(workers + rotator, "rotation storm")
        assertEquals(emptyList(), violations.take(5), "${violations.size} violations")

        // The platform settles on one key: every instance must work again, within a bound.
        runBlocking {
            withTimeout(10_000) {
                for ((index: Int, storage: KeyValueStorage) in instances.withIndex()) {
                    val put: StorageResult<Unit> = storage.put("after$index", "settled$index")
                    assertEquals(StorageResult.Success(Unit), put, "instance $index could not write once the key settled")
                    assertEquals("settled$index", storage.get("after$index").getOrNull(), "instance $index")
                    val other: KeyValueStorage = instances[1 - index]
                    assertEquals("settled$index", other.get("after$index").getOrNull(), "instance ${1 - index} reading instance $index")
                }
            }
        }
    }

    // -- A device id requested by callers that are cancelled while they wait -----------------------------------

    private class SlowReads(private val inner: KeyValueStorage) : KeyValueStorage by inner {
        override suspend fun get(key: String): StorageResult<String?> {
            withContext(Dispatchers.IO) { Thread.sleep(2) }
            return inner.get(key)
        }
    }

    @Test
    fun `a device id requested by 100 callers half of them cancelled while they wait is one id and the lock is free afterwards`() {
        val config = StorageConfig(uniqueName("idcancel"))
        repeat(15) { round: Int ->
            val key = "id-$round"
            val storages: List<KeyValueStorage> = List(3) { SlowReads(createKeyValueStorage(context, config)) }
            val providers: List<DeviceIdProvider> = storages.map { DeviceIdProvider(it, key) }
            val ids: List<String> = runBlocking(Dispatchers.Default) {
                withTimeout(30_000) {
                    val callers = List(100) { index: Int ->
                        async {
                            providers[index % 3].current()
                        }
                    }
                    // Cancel every second caller after a random short delay.
                    callers.forEachIndexed { index: Int, caller ->
                        if (index % 2 == 1) launch {
                            delay((index % 7).toLong())
                            caller.cancel()
                        }
                    }
                    callers.mapIndexedNotNull { index: Int, caller ->
                        if (index % 2 == 1) {
                            runCatching { caller.await() }.getOrNull()
                        } else {
                            caller.await()
                        }
                    }
                }
            }
            assertEquals(1, ids.toSet().size, "round $round: callers disagree: ${ids.toSet()}")
            val persisted: String? = runBlocking { storages[0].get(key).getOrNull() }
            assertEquals(ids.first(), persisted, "round $round")
            assertEquals(persisted, runBlocking { withTimeout(5_000) { providers[1].current() } }, "round $round: the lock stayed taken or the id changed")
        }
    }
}
