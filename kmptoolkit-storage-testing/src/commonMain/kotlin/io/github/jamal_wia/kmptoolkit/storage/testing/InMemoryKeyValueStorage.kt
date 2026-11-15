package io.github.jamal_wia.kmptoolkit.storage.testing

import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.SecureKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.SecureStorageProtection
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * A map-backed [SecureKeyValueStorage] for testing the code *around* a store.
 *
 * It satisfies [SecureKeyValueStorage], and therefore [KeyValueStorage], so it substitutes for
 * either one. Nothing is encrypted: a test double that encrypted would need a key store, which is
 * the very thing that does not exist in a unit test. What it claims about that is [protection],
 * [SecureStorageProtection.NONE] by default — the truth about a map — and any other value a test
 * needs to feed code that branches on it.
 *
 * It keeps the documented contract — absent reads back as `null`, `""` reads back as `""`, [put]
 * overwrites, [remove] of an absent key succeeds, [clear] empties the shared [Backing], and [put] of a
 * key or value with an unpaired UTF-16 surrogate fails with [StorageError.InvalidValue] — so a test
 * that passes against it is testing behavior the real stores also have.
 *
 * What it adds is control over failure, which no real store gives you on demand:
 *
 * ```kotlin
 * @Test
 * fun `a destroyed key forces a fresh sign-in`() = runTest {
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
 * [failNextOperationWith] scripts one failure; [failEveryOperationWith] scripts a store that stays
 * broken (a keyring that stays locked as [StorageError.Unavailable], an environment that is
 * [StorageError.Unsupported]).
 *
 * ### Restart and a second process
 *
 * Two instances built with one [Backing] see the same contents, so a test can model a process
 * restart (drop the first instance, build a second over the same backing) or a second process
 * working on the same store. The failure knobs, [protection] and [writes] belong to the instance, not
 * to the backing: a new instance starts with none armed.
 *
 * ### Threads
 *
 * Safe to use from any number of threads and coroutines. The contents and the knobs are immutable
 * snapshots held in `MutableStateFlow`s and changed with `update` (compare-and-set), the idiom of the
 * suite's other fixtures: no call blocks, no write is lost, an armed [failNextOperationWith] fails
 * exactly one of any number of concurrent operations, and two puts never produce a half-written
 * state. Operations on different keys have no defined order, and under concurrent writers the order of
 * [writes] is the order in which the records landed: an entry can be visible in [contents] a moment
 * before its record is.
 *
 * ### Cancellation
 *
 * Like every real store, each operation checks first whether its caller is still active: a cancelled
 * caller gets a `CancellationException` and nothing changes — no entry, no [writes] record, and an
 * armed [failNextOperationWith] stays armed. No operation suspends, so a caller that is not cancelled
 * always sees an operation run completely.
 */
public class InMemoryKeyValueStorage(
    /**
     * What the fake claims; [SecureStorageProtection.NONE], the truth about a map, unless a test needs
     * another value.
     */
    override val protection: SecureStorageProtection = SecureStorageProtection.NONE,
    backing: Backing = Backing(),
) : SecureKeyValueStorage {

    /**
     * The contents of an [InMemoryKeyValueStorage], shared by every instance built with it.
     *
     * Create one, pass it to several instances, and they all read and write the same entries —
     * the way two instances over one file, or one instance before and after a process restart,
     * would. A fresh `InMemoryKeyValueStorage()` gets a backing of its own.
     */
    public class Backing {
        internal val entries: MutableStateFlow<Map<String, String>> = MutableStateFlow(emptyMap())
    }

    /** Everything an instance owns besides the shared entries; replaced whole, never mutated. */
    private data class State(
        val failNext: StorageError? = null,
        val failEvery: StorageError? = null,
        val writes: List<String> = emptyList(),
    )

    private val shared: MutableStateFlow<Map<String, String>> = backing.entries

    private val state: MutableStateFlow<State> = MutableStateFlow(State())

    /**
     * Makes the next operation — any of the four, or [ensureKey] — fail with this error instead of
     * running, then clears itself.
     *
     * The failing operation has no effect on the contents, matching [StorageResult.Failure]'s
     * contract. Set it again to fail more than one call. Checked before [failEveryOperationWith]: while
     * both are set, the next operation gets this error, the one after it the sticky one.
     */
    public var failNextOperationWith: StorageError?
        get() = state.value.failNext
        set(value) {
            state.update { it.copy(failNext = value) }
        }

    /**
     * Makes every operation — any of the four, or [ensureKey] — fail with this error, with no effect on
     * the contents, until it is set back to `null`.
     *
     * For a store that does not recover on its own: a keyring that stays locked, an environment where
     * the store is [StorageError.Unsupported]. Checked after [failNextOperationWith].
     *
     * It fails `remove` and `clear` too. The "key lost" state of an OS-key store — after
     * `Undecryptable(null)` those two still work and the next `put` starts a fresh key — is not
     * modelled by it: to test a recovery path, script the read with
     * `failNextOperationWith = Undecryptable(null)` and let `remove` and `clear` run normally.
     */
    public var failEveryOperationWith: StorageError?
        get() = state.value.failEvery
        set(value) {
            state.update { it.copy(failEvery = value) }
        }

    /**
     * Every key this instance has ever been asked to write, in call order, including overwrites and
     * writes that were later removed or cleared.
     *
     * For asserting that code wrote what it claimed to — `assertEquals(listOf("token"), writes)` —
     * where reading the final contents cannot tell a value that was written once from one that was
     * written, removed, and written again. Per instance: a second instance over the same [Backing]
     * keeps its own list.
     *
     * Under concurrent writers the order is the order in which the records landed, and an entry can be
     * visible in [contents] a moment before its record is here: one `put` updates the shared entries
     * and then this log, and the two are separate atomic steps.
     *
     * Each read returns a copy, like [contents]: the list is the caller's own, so sorting or clearing it
     * (a Java caller can, without a cast) neither rewrites the record nor disturbs a concurrent `put`.
     * The copy is a mutable list whatever the number of records: `toList()` would answer a read-only
     * singleton or empty list for 0 or 1 entries on the JVM, which refuses a Java caller's `clear()`.
     */
    public val writes: List<String> get() = state.value.writes.toMutableList()

    /**
     * A snapshot of the current contents of the shared [Backing], for asserting on the store as a whole.
     * Each read returns a copy that is the caller's own, so changing it never changes the store.
     * The copy is a mutable map whatever the number of entries (`toMap()` would answer a read-only
     * singleton or empty map for 0 or 1 entries on the JVM).
     */
    public val contents: Map<String, String> get() = shared.value.toMutableMap()

    override suspend fun get(key: String): StorageResult<String?> {
        currentCoroutineContext().ensureActive()
        return scripted() ?: StorageResult.Success(shared.value[key])
    }

    override suspend fun put(key: String, value: String): StorageResult<Unit> {
        // First, as in the real stores (which enter their dispatcher before anything else): a cancelled
        // caller gets a CancellationException, changes nothing and consumes no armed knob.
        currentCoroutineContext().ensureActive()
        // Like the real stores, before anything else: a key or value with an unpaired surrogate cannot
        // be stored by any backend, so it fails with InvalidValue, changes nothing and does not
        // consume an armed failNextOperationWith.
        if (hasUnpairedSurrogate(key) || hasUnpairedSurrogate(value)) {
            return StorageResult.Failure(
                StorageError.InvalidValue(
                    key,
                    IllegalArgumentException("Unpaired UTF-16 surrogate in the key or value"),
                ),
            )
        }
        scripted()?.let { return it }
        shared.update { it + (key to value) }
        state.update { it.copy(writes = it.writes + key) }
        return StorageResult.Success(Unit)
    }

    override suspend fun remove(key: String): StorageResult<Unit> {
        currentCoroutineContext().ensureActive()
        scripted()?.let { return it }
        shared.update { it - key }
        return StorageResult.Success(Unit)
    }

    override suspend fun clear(): StorageResult<Unit> {
        currentCoroutineContext().ensureActive()
        scripted()?.let { return it }
        shared.update { emptyMap() }
        return StorageResult.Success(Unit)
    }

    /**
     * Succeeds unless a failure knob is set; there is no key and nothing to prompt for, so it never
     * prompts and changes nothing. Consumes an armed [failNextOperationWith] like any other operation.
     */
    override suspend fun ensureKey(): StorageResult<Unit> {
        currentCoroutineContext().ensureActive()
        return scripted() ?: StorageResult.Success(Unit)
    }

    /**
     * The scripted failure for this call, or null. Consumes [failNextOperationWith] if it is armed and
     * otherwise answers [failEveryOperationWith]; the decision and the clearing are one compare-and-set,
     * so of any number of concurrent calls exactly one gets an armed one-shot failure.
     */
    private fun scripted(): StorageResult.Failure? {
        var answer: StorageError? = null
        // The lambda may run again after a lost compare-and-set, so `answer` is rebuilt on every run and
        // only the run that landed is kept.
        state.update { current: State ->
            val next: StorageError? = current.failNext
            if (next != null) {
                answer = next
                current.copy(failNext = null)
            } else {
                answer = current.failEvery
                current
            }
        }
        return answer?.let { StorageResult.Failure(it) }
    }
}

// A private copy of the real stores' rule (the helper there is internal): the fake mirrors the real
// stores, so it refuses what none of them can keep.
private fun hasUnpairedSurrogate(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val char: Char = text[index]
        when {
            char.isHighSurrogate() -> {
                if (index + 1 < text.length && text[index + 1].isLowSurrogate()) index++ else return true
            }
            char.isLowSurrogate() -> return true
        }
        index++
    }
    return false
}
