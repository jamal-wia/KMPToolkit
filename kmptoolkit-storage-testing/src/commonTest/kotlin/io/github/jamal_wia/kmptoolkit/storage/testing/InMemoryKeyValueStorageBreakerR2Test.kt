package io.github.jamal_wia.kmptoolkit.storage.testing

import io.github.jamal_wia.kmptoolkit.storage.EncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.errorOrNull
import io.github.jamal_wia.kmptoolkit.storage.getBooleanOr
import io.github.jamal_wia.kmptoolkit.storage.getInt
import io.github.jamal_wia.kmptoolkit.storage.getIntOr
import io.github.jamal_wia.kmptoolkit.storage.getLongOr
import io.github.jamal_wia.kmptoolkit.storage.getStringOr
import io.github.jamal_wia.kmptoolkit.storage.getStringOrNull
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Breaker round 2 of group sto-api (STO-2/STO-3, plan 3.8, D-16, D-26, ticket release-5-1-0): the fixture's
 * text paths and its use through the deprecated `...Or` overloads that a [EncryptedKeyValueStorage]
 * receiver resolves to (the fixture is a `SecureKeyValueStorage`, D-16).
 *
 * Contract: the fixture mirrors the real stores (class KDoc), so a refused `put` carries no value and a
 * result read from it prints no value (D-26); an armed `failNextOperationWith` fails exactly one operation,
 * also when that operation is a deprecated helper; the fixture is safe from any number of threads. Seeds are
 * fixed and named in every failure message; real-time bounds are hang bounds only.
 */
@OptIn(ExperimentalAtomicApi::class)
class InMemoryKeyValueStorageBreakerR2Test {

    private val secret: String = "s3cr3t-session-token-Kp42"

    @Test
    fun `a put refused for an unpaired surrogate carries neither the value nor any part of it`() = runTest {
        val storage = InMemoryKeyValueStorage()
        val value = "$secret\uD800"

        val result: StorageResult<Unit> = storage.put("token", value)

        val error: StorageError.InvalidValue = assertIs(result.errorOrNull(), "answered $result")
        val texts: MutableList<String> = mutableListOf(result.toString(), error.toString())
        var cause: Throwable? = error.cause
        while (cause != null) {
            texts += cause.toString()
            cause = cause.cause
        }
        for (text: String in texts) {
            assertFalse(text.contains(secret), "the refused put carries the value: $text")
        }
        assertTrue(storage.contents.isEmpty())
    }

    @Test
    fun `a result read from the fixture prints no stored value by D-26`() = runTest {
        val storage = InMemoryKeyValueStorage()
        assertTrue(storage.put("token", secret).isSuccessResult())
        assertTrue(storage.put("pin", "918273").isSuccessResult())

        val read: StorageResult<String?> = storage.get("token")
        val pin: StorageResult<Int?> = storage.getInt("pin")

        assertEquals(StorageResult.Success(secret), read, "control: the value is there")
        assertFalse(read.toString().contains(secret), "get() result prints the secret: $read")
        assertFalse(pin.toString().contains("918273"), "getInt() result prints the PIN: $pin")
    }

    @Test
    fun `an assertion that compares a fixture read does not put the secret in its message by D-26`() = runTest {
        val storage = InMemoryKeyValueStorage()
        assertTrue(storage.put("token", secret).isSuccessResult())

        val failure: AssertionError = assertFailsWith<AssertionError> {
            assertEquals(StorageResult.Success("some-other-value"), storage.get("token"))
        }

        assertFalse(failure.message.orEmpty().contains(secret), "assertion message: ${failure.message}")
    }

    @Suppress("DEPRECATION")
    @Test
    fun `a deprecated helper consumes exactly one armed failure`() = runTest {
        val storage = InMemoryKeyValueStorage()
        assertTrue(storage.put("n", "12").isSuccessResult())

        storage.failNextOperationWith = StorageError.Unavailable()
        assertEquals(-1, storage.getIntOr("n", -1), "the armed failure turns into the default")
        assertNull(storage.failNextOperationWith, "the helper did not consume the armed failure")
        assertEquals(12, storage.getIntOr("n", -1), "a second read was also failed: the helper read twice")

        storage.failNextOperationWith = StorageError.Unavailable()
        assertEquals("d", storage.getStringOr("n", "d"))
        assertEquals("12", storage.getStringOrNull("n"))
        storage.failNextOperationWith = StorageError.Unavailable()
        assertEquals(-1L, storage.getLongOr("n", -1L))
        assertEquals(12L, storage.getLongOr("n", -1L))
        storage.failNextOperationWith = StorageError.Unavailable()
        assertNull(storage.getStringOrNull("n"))
        assertEquals(true, storage.getBooleanOr("n", true), "12 is not a boolean, so the default")
        assertNull(storage.failNextOperationWith)
        assertEquals(listOf("n"), storage.writes, "no helper wrote anything")
    }

    @Suppress("DEPRECATION")
    @Test
    fun `the deprecated helpers on one fixture under a storm only ever answer a written value or the default`() =
        runTest(timeout = 180.seconds) {
            val seed = 1301
            val workers = 16
            val steps = 500
            val backing = InMemoryKeyValueStorage.Backing()
            val shared = InMemoryKeyValueStorage(backing = backing)
            val values: List<String> = listOf("1", "-2", "true", "false", "", "x", "2147483648", "007")
            val problems: AtomicInt = AtomicInt(0)
            val firstProblem: Array<String?> = arrayOf(null)
            val armed: AtomicInt = AtomicInt(0)

            fun problem(text: String) {
                if (problems.addAndFetch(1) == 1) firstProblem[0] = text
            }

            withContext(Dispatchers.Default) {
                withTimeout(120.seconds) {
                    coroutineScope {
                        repeat(workers) { worker: Int ->
                            launch {
                                val random = Random(seed * 1000 + worker)
                                repeat(steps) {
                                    val key = "k${random.nextInt(3)}"
                                    when (random.nextInt(9)) {
                                        0, 1 -> {
                                            val result: StorageResult<Unit> = shared.put(key, values[random.nextInt(values.size)])
                                            if (result is StorageResult.Failure && result.error !is StorageError.Unavailable) {
                                                problem("put answered $result")
                                            }
                                        }
                                        2 -> {
                                            val answer: Int = shared.getIntOr(key, Int.MIN_VALUE)
                                            if (answer != Int.MIN_VALUE && answer !in setOf(1, -2, 7)) problem("getIntOr answered $answer")
                                        }
                                        3 -> {
                                            val answer: Long = shared.getLongOr(key, Long.MIN_VALUE)
                                            if (answer != Long.MIN_VALUE && answer !in setOf(1L, -2L, 7L, 2147483648L)) {
                                                problem("getLongOr answered $answer")
                                            }
                                        }
                                        4 -> {
                                            // Neither default distinguishes; both defaults are legal answers.
                                            shared.getBooleanOr(key, random.nextBoolean())
                                        }
                                        5 -> {
                                            val answer: String = shared.getStringOr(key, "<default>")
                                            if (answer != "<default>" && answer !in values) problem("getStringOr answered '$answer'")
                                        }
                                        6 -> {
                                            val answer: String? = shared.getStringOrNull(key)
                                            if (answer != null && answer !in values) problem("getStringOrNull answered '$answer'")
                                        }
                                        7 -> {
                                            shared.failNextOperationWith = StorageError.Unavailable()
                                            armed.addAndFetch(1)
                                        }
                                        else -> {
                                            shared.remove(key)
                                        }
                                    }
                                    yield()
                                }
                            }
                        }
                    }
                }
            }

            if (problems.load() != 0) fail("seed=$seed: ${problems.load()} wrong answer(s), first: ${firstProblem[0]}")
            assertTrue(armed.load() > 0, "seed=$seed: control: the storm armed failures")
            shared.failNextOperationWith = null
            val restarted = InMemoryKeyValueStorage(backing = backing)
            for (key: String in listOf("k0", "k1", "k2")) {
                val read: String? = restarted.get(key).getOrNullResult()
                if (read != null && read !in values) fail("seed=$seed: $key holds '$read', which nobody wrote")
            }
        }

    private fun StorageResult<*>.isSuccessResult(): Boolean = this is StorageResult.Success

    private fun <T> StorageResult<T>.getOrNullResult(): T? = (this as? StorageResult.Success<T>)?.value
}
