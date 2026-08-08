package io.github.jamal_wia.kmptoolkit.storage

/**
 * A map-backed [KeyValueStorage] for this module's own suite.
 *
 * Deliberately not the published `InMemoryKeyValueStorage` from `kmptoolkit-storage-testing`: that
 * module depends on this one, so depending back on it would be a project cycle. The duplication is
 * a few lines and it keeps the fixture module's behavior under test by its own suite rather than by
 * this one.
 */
internal class FakeKeyValueStorage : KeyValueStorage {

    val entries: MutableMap<String, String> = mutableMapOf()

    /** Makes every [get] fail, standing in for a store whose values cannot be read back. */
    var failReadsWith: StorageError? = null

    /** Makes every [put] fail, standing in for a store that cannot be written to. */
    var failWritesWith: StorageError? = null

    override fun get(key: String): StorageResult<String?> =
        failReadsWith?.let { StorageResult.Failure(it) } ?: StorageResult.Success(entries[key])

    override fun put(key: String, value: String): StorageResult<Unit> {
        failWritesWith?.let { return StorageResult.Failure(it) }
        entries[key] = value
        return StorageResult.Success(Unit)
    }

    override fun remove(key: String): StorageResult<Unit> {
        entries.remove(key)
        return StorageResult.Success(Unit)
    }

    override fun clear(): StorageResult<Unit> {
        entries.clear()
        return StorageResult.Success(Unit)
    }
}
