package io.github.jamal_wia.kmptoolkit.storage.testing

import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.SecureKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult

/**
 * A map-backed [SecureKeyValueStorage] for testing the code *around* a store.
 *
 * It satisfies [SecureKeyValueStorage], and therefore [KeyValueStorage], so it substitutes for
 * either one. Nothing is encrypted: a test double that encrypted would need a key store, which is
 * the very thing that does not exist in a unit test.
 *
 * It keeps the documented contract — absent reads back as `null`, `""` reads back as `""`, [put]
 * overwrites, [remove] of an absent key succeeds, [clear] empties only this instance — so a test
 * that passes against it is testing behavior the real stores also have.
 *
 * What it adds is control over failure, which no real store gives you on demand:
 *
 * ```kotlin
 * @Test
 * fun `a destroyed key forces a fresh sign-in`() {
 *     val storage = InMemoryKeyValueStorage()
 *     storage.put("token", "abc")
 *     storage.failNextOperationWith = StorageError.Undecryptable("token")
 *
 *     val session = SessionLoader(storage).load()
 *
 *     assertEquals(Session.SignedOut, session)
 * }
 * ```
 *
 * Not thread-safe, matching the real stores' own ordering caveat. Drive it from one thread.
 */
public class InMemoryKeyValueStorage : SecureKeyValueStorage {

    private val entries: MutableMap<String, String> = mutableMapOf()

    /**
     * Makes the next operation — any of the four — fail with this error instead of running, then
     * clears itself.
     *
     * The failing operation has no effect on the contents, matching [StorageResult.Failure]'s
     * contract. Set it again to fail more than one call.
     */
    public var failNextOperationWith: StorageError? = null

    /**
     * Every key this store has ever been asked to write, in call order, including overwrites and
     * writes that were later removed or cleared.
     *
     * For asserting that code wrote what it claimed to — `assertEquals(listOf("token"), writes)` —
     * where reading the final contents cannot tell a value that was written once from one that was
     * written, removed, and written again.
     */
    public val writes: List<String> get() = recordedWrites.toList()

    private val recordedWrites: MutableList<String> = mutableListOf()

    /** A snapshot of the current contents, for asserting on the store as a whole. */
    public val contents: Map<String, String> get() = entries.toMap()

    override fun get(key: String): StorageResult<String?> =
        scripted() ?: StorageResult.Success(entries[key])

    override fun put(key: String, value: String): StorageResult<Unit> {
        scripted()?.let { return it }
        entries[key] = value
        recordedWrites += key
        return StorageResult.Success(Unit)
    }

    override fun remove(key: String): StorageResult<Unit> {
        scripted()?.let { return it }
        entries.remove(key)
        return StorageResult.Success(Unit)
    }

    override fun clear(): StorageResult<Unit> {
        scripted()?.let { return it }
        entries.clear()
        return StorageResult.Success(Unit)
    }

    /** Consumes [failNextOperationWith] if it is armed, so the next call runs normally again. */
    private fun scripted(): StorageResult.Failure? {
        val error: StorageError = failNextOperationWith ?: return null
        failNextOperationWith = null
        return StorageResult.Failure(error)
    }
}
