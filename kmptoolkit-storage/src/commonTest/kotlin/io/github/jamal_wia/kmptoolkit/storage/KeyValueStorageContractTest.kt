package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contract from `docs/kmptoolkit-storage/01-overview.md`, asserted against a real store.
 *
 * Every real implementation is a subclass — the plain and the encrypted store on each platform —
 * so that the encrypted one is held to exactly the same behavior as the plain one: the difference
 * between them is meant to be a guarantee about what is on disk, not something a caller observes.
 * It lives in `commonTest` rather than in a platform test source set for that reason; the platform
 * subclasses supply nothing but a factory.
 */
abstract class KeyValueStorageContractTest {

    /** A store over [config]. Two calls with equal configs must open the same underlying store. */
    protected abstract fun create(config: StorageConfig = StorageConfig(NAME)): KeyValueStorage

    /**
     * Empties every store these tests touch.
     *
     * Needed because a real store outlives the test that wrote to it: an `NSUserDefaults` suite and
     * a Keychain service both persist in the simulator between runs, so without this a value from
     * an earlier run would satisfy an assertion that the current run never actually met.
     */
    @BeforeTest
    fun emptyStoresUnderTest() {
        NAMES.forEach { create(StorageConfig(it)).clear() }
    }

    @Test
    fun `a key that was never written reads back as absent`() {
        assertEquals(StorageResult.Success(null), create().get("missing"))
    }

    @Test
    fun `a written value reads back`() {
        val storage: KeyValueStorage = create()

        storage.write("k", "v")

        assertEquals(StorageResult.Success("v"), storage.get("k"))
    }

    @Test
    fun `an empty value is stored rather than treated as absent`() {
        val storage: KeyValueStorage = create()

        storage.write("k", "")

        assertEquals(StorageResult.Success(""), storage.get("k"))
    }

    @Test
    fun `writing a key twice keeps the second value`() {
        val storage: KeyValueStorage = create()
        storage.write("k", "first")

        storage.write("k", "second")

        assertEquals(StorageResult.Success("second"), storage.get("k"))
    }

    @Test
    fun `remove deletes the value`() {
        val storage: KeyValueStorage = create()
        storage.write("k", "v")

        assertTrue(storage.remove("k").isSuccess)

        assertEquals(StorageResult.Success(null), storage.get("k"))
    }

    @Test
    fun `removing a key that was never written succeeds and changes nothing`() {
        val storage: KeyValueStorage = create()
        storage.write("kept", "v")

        assertTrue(storage.remove("never-written").isSuccess)

        assertEquals(StorageResult.Success("v"), storage.get("kept"))
    }

    @Test
    fun `clear empties the store`() {
        val storage: KeyValueStorage = create()
        storage.write("a", "1")
        storage.write("b", "2")

        assertTrue(storage.clear().isSuccess)

        assertEquals(StorageResult.Success(null), storage.get("a"))
        assertEquals(StorageResult.Success(null), storage.get("b"))
    }

    @Test
    fun `clear on an already empty store succeeds`() {
        assertTrue(create().clear().isSuccess)
    }

    @Test
    fun `a value written by one instance is readable by another over the same config`() {
        // The stand-in for a process restart: durability is part of the contract, and apply()
        // instead of commit() would make this pass while still losing the value on a kill.
        create().write("k", "v")

        assertEquals(StorageResult.Success("v"), create().get("k"))
    }

    @Test
    fun `two stores with different names do not share keys`() {
        val first: KeyValueStorage = create(StorageConfig("$NAME.first"))
        val second: KeyValueStorage = create(StorageConfig("$NAME.second"))

        first.write("same-key", "from-first")
        second.write("same-key", "from-second")

        assertEquals(StorageResult.Success("from-first"), first.get("same-key"))
        assertEquals(StorageResult.Success("from-second"), second.get("same-key"))
    }

    @Test
    fun `clearing one store leaves a differently named one untouched`() {
        val first: KeyValueStorage = create(StorageConfig("$NAME.first"))
        val second: KeyValueStorage = create(StorageConfig("$NAME.second"))
        first.write("k", "1")
        second.write("k", "2")

        first.clear()

        assertEquals(StorageResult.Success(null), first.get("k"))
        assertEquals(StorageResult.Success("2"), second.get("k"))
    }

    @Test
    fun `keys are independent of one another`() {
        val storage: KeyValueStorage = create()
        storage.write("a", "1")
        storage.write("b", "2")

        storage.remove("a")

        assertNull(storage.get("a").getOrNull())
        assertEquals("2", storage.get("b").getOrNull())
    }

    @Test
    fun `a long value round-trips`() {
        val storage: KeyValueStorage = create()
        val value: String = "x".repeat(100_000)

        storage.write("k", value)

        assertEquals(value, storage.get("k").getOrNull())
    }

    @Test
    fun `a value with non-ASCII characters round-trips`() {
        val storage: KeyValueStorage = create()
        val value = "مرحبا — Привет — 🙂"

        storage.write("k", value)

        assertEquals(value, storage.get("k").getOrNull())
    }

    /**
     * [KeyValueStorage.put], asserting that it succeeded.
     *
     * Every arrange step in this class goes through it. A bare `put` whose failure is ignored would
     * turn "the value could not be written" into "the value read back as absent", which several
     * tests here would then report as a wrong *read* — the encrypted store's first run against a
     * missing key store failed exactly that way.
     */
    protected fun KeyValueStorage.write(key: String, value: String) {
        val result: StorageResult<Unit> = put(key, value)
        assertTrue(result.isSuccess, "put($key) failed: $result")
    }

    protected companion object {
        /** The store every test here uses unless it is specifically about two stores. */
        const val NAME = "io.github.jamal_wia.kmptoolkit.storage.test"

        /** Every store name used by this class, emptied before each test. */
        val NAMES: List<String> = listOf(NAME, "$NAME.first", "$NAME.second")
    }
}
