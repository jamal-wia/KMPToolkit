package io.github.jamal_wia.kmptoolkit.settings

import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage

/**
 * An [InMemoryKeyValueStorage] that can be told to fail a specific key's reads or writes for as
 * long as the test wants.
 *
 * `InMemoryKeyValueStorage.failNextOperationWith` — the fixture kmptoolkit-storage-testing already
 * ships — fails whichever operation happens to come next, whatever key it targets. That is enough
 * for a test with one store hit and not enough here: loading reads three keys in a row, so
 * "the theme could not be read" has to be expressible without also breaking the other two.
 */
internal class ScriptedKeyValueStorage(
    private val delegate: InMemoryKeyValueStorage = InMemoryKeyValueStorage(),
) : KeyValueStorage by delegate {

    private val readFailures: MutableMap<String, StorageError> = mutableMapOf()
    private val writeFailures: MutableMap<String, StorageError> = mutableMapOf()

    /** Every key [put] has been called with, in order — including writes that were made to fail. */
    val attemptedWrites: List<String> get() = attempts.toList()

    private val attempts: MutableList<String> = mutableListOf()

    fun failReadsOf(key: String, error: StorageError) {
        readFailures[key] = error
    }

    fun failWritesOf(key: String, error: StorageError) {
        writeFailures[key] = error
    }

    /** Lets [key] be written again — a store that was unavailable and came back. */
    fun stopFailingWritesOf(key: String) {
        writeFailures.remove(key)
    }

    override fun get(key: String): StorageResult<String?> =
        readFailures[key]?.let { StorageResult.Failure(it) } ?: delegate.get(key)

    override fun put(key: String, value: String): StorageResult<Unit> {
        attempts += key
        return writeFailures[key]?.let { StorageResult.Failure(it) } ?: delegate.put(key, value)
    }

    /** The stored value, bypassing any scripted read failure. */
    fun stored(key: String): String? = delegate.get(key).let {
        (it as? StorageResult.Success)?.value
    }
}
