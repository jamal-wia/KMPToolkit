package io.github.jamal_wia.kmptoolkit.storage.testing

import io.github.jamal_wia.kmptoolkit.storage.SecureStorageProtection
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.UnsupportedReason
import io.github.jamal_wia.kmptoolkit.storage.errorOrNull
import io.github.jamal_wia.kmptoolkit.storage.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest

/**
 * The fixture's sharing and failure knobs, from plan 3.8 and the test plan's T15: one `Backing` shared
 * by instances models a restart or a second process; `failEveryOperationWith` is a store that stays
 * broken and is checked after `failNextOperationWith`; `ensureKey` follows both knobs; the knobs belong
 * to the instance, not to the backing.
 */
class InMemoryKeyValueStorageBackingTest {

    private val locked: StorageError = StorageError.Unsupported(UnsupportedReason.NO_KEY_STORE)

    @Test
    fun `two instances over one backing see each others writes removes and clear`() = runTest {
        val backing = InMemoryKeyValueStorage.Backing()
        val first = InMemoryKeyValueStorage(backing = backing)
        val second = InMemoryKeyValueStorage(backing = backing)

        first.put("a", "1")
        assertEquals("1", second.get("a").getOrNull())
        second.put("b", "2")
        assertEquals(mapOf("a" to "1", "b" to "2"), first.contents)

        first.remove("a")
        assertNull(second.get("a").getOrNull())

        second.clear()
        assertEquals(emptyMap(), first.contents)
    }

    @Test
    fun `an instance built without a backing is independent of every other`() = runTest {
        val first = InMemoryKeyValueStorage()
        val second = InMemoryKeyValueStorage()

        first.put("k", "v")

        assertNull(second.get("k").getOrNull())
        assertEquals(emptyMap(), second.contents)
    }

    @Test
    fun `a process restart is a new instance over the old backing and finds the data`() = runTest {
        val backing = InMemoryKeyValueStorage.Backing()
        val beforeRestart = InMemoryKeyValueStorage(backing = backing)
        beforeRestart.put("token", "abc")
        beforeRestart.failEveryOperationWith = locked

        val afterRestart = InMemoryKeyValueStorage(backing = backing)

        // The data survived; the old instance's armed failure and its write log did not follow it.
        assertEquals(StorageResult.Success("abc"), afterRestart.get("token"))
        assertNull(afterRestart.failEveryOperationWith)
        assertNull(afterRestart.failNextOperationWith)
        assertEquals(emptyList(), afterRestart.writes)
        assertEquals(listOf("token"), beforeRestart.writes)
    }

    @Test
    fun `the knobs of one instance do not reach another over the same backing`() = runTest {
        val backing = InMemoryKeyValueStorage.Backing()
        val broken = InMemoryKeyValueStorage(backing = backing)
        val healthy = InMemoryKeyValueStorage(backing = backing)
        healthy.put("k", "v")

        broken.failEveryOperationWith = locked

        assertEquals(locked, broken.get("k").errorOrNull())
        assertEquals(StorageResult.Success("v"), healthy.get("k"))
    }

    @Test
    fun `protection defaults to NONE and is the instances own`() {
        val backing = InMemoryKeyValueStorage.Backing()

        assertEquals(SecureStorageProtection.NONE, InMemoryKeyValueStorage(backing = backing).protection)
        assertEquals(
            SecureStorageProtection.PASSPHRASE,
            InMemoryKeyValueStorage(SecureStorageProtection.PASSPHRASE, backing).protection,
        )
    }

    @Test
    fun `a sticky failure fails every operation kind again and again without any effect`() = runTest {
        val storage = InMemoryKeyValueStorage()
        storage.put("k", "v")
        storage.failEveryOperationWith = locked

        repeat(3) {
            assertEquals(locked, storage.get("k").errorOrNull())
            assertEquals(locked, storage.put("k", "w").errorOrNull())
            assertEquals(locked, storage.remove("k").errorOrNull())
            assertEquals(locked, storage.clear().errorOrNull())
            assertEquals(locked, storage.ensureKey().errorOrNull())
        }

        assertEquals(mapOf("k" to "v"), storage.contents)
        assertEquals(listOf("k"), storage.writes)
    }

    @Test
    fun `setting the sticky failure back to null restores the store`() = runTest {
        val storage = InMemoryKeyValueStorage()
        storage.failEveryOperationWith = locked
        assertEquals(locked, storage.put("k", "v").errorOrNull())

        storage.failEveryOperationWith = null

        assertEquals(StorageResult.Success(Unit), storage.put("k", "v"))
        assertEquals(StorageResult.Success("v"), storage.get("k"))
        assertEquals(StorageResult.Success(Unit), storage.ensureKey())
    }

    @Test
    fun `an armed one-shot failure comes before the sticky one and is then cleared`() = runTest {
        val storage = InMemoryKeyValueStorage()
        val once: StorageError = StorageError.AccessDenied(platformCode = -128)
        storage.failNextOperationWith = once
        storage.failEveryOperationWith = locked

        assertEquals(once, storage.get("k").errorOrNull())
        assertNull(storage.failNextOperationWith)
        assertEquals(locked, storage.get("k").errorOrNull())
        assertEquals(locked, storage.ensureKey().errorOrNull())
    }

    @Test
    fun `ensureKey follows the one-shot knob and the sticky knob and never writes`() = runTest {
        val storage = InMemoryKeyValueStorage()

        assertEquals(StorageResult.Success(Unit), storage.ensureKey())

        storage.failNextOperationWith = StorageError.Unavailable()
        assertEquals(StorageError.Unavailable(), storage.ensureKey().errorOrNull())
        assertEquals(StorageResult.Success(Unit), storage.ensureKey())

        assertEquals(emptyMap(), storage.contents)
        assertEquals(emptyList(), storage.writes)
    }

    @Test
    fun `a surrogate put is rejected before the knobs and consumes neither`() = runTest {
        val storage = InMemoryKeyValueStorage()
        storage.failNextOperationWith = StorageError.Unavailable()
        storage.failEveryOperationWith = locked

        val rejected: StorageError? = storage.put("k", "a\uD800b").errorOrNull()

        assertTrue(rejected is StorageError.InvalidValue && rejected.key == "k", "was $rejected")
        assertEquals(StorageError.Unavailable(), storage.failNextOperationWith)
        assertEquals(locked, storage.failEveryOperationWith)
    }

    @Test
    fun `a caller that is already cancelled never leaves a half done operation`() = runTest {
        val storage = InMemoryKeyValueStorage()
        val outcomes: MutableList<StorageResult<Unit>> = mutableListOf()

        val job = launch(Dispatchers.Unconfined) {
            currentCoroutineContext().cancel()
            // No operation suspends, so the call runs to completion in a cancelled coroutine.
            outcomes += storage.put("k", "v")
        }
        job.join()

        assertEquals(listOf<StorageResult<Unit>>(StorageResult.Success(Unit)), outcomes)
        assertEquals(mapOf("k" to "v"), storage.contents)
        assertEquals(listOf("k"), storage.writes)
    }
}
