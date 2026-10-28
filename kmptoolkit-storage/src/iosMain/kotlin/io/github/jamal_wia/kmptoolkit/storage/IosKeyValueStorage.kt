package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import platform.Foundation.NSUserDefaults

/**
 * [KeyValueStorage] over a named `NSUserDefaults` suite.
 *
 * A suite, never `standardUserDefaults`: `clear()` is implemented with
 * `removePersistentDomainForName`, which on the standard domain would wipe everything the app and
 * any embedded SDK ever wrote there. Confined to a suite of this module's own naming it can only
 * remove entries this store put in.
 *
 * `NSUserDefaults` has no failing write — `setObject:forKey:` returns nothing and the framework
 * reports no status — so every operation here succeeds. That is not a shortcut: there is genuinely
 * no error to surface, and inventing one would be a lie in the type. The results exist so the same
 * interface can carry the failures [IosSecureKeyValueStorage] really does have.
 *
 * Calls run on [dispatcher]: a suite's first access loads its property list through `cfprefsd`,
 * which is a blocking IPC round trip the caller's thread should not pay for.
 */
internal class IosKeyValueStorage(
    private val suiteName: String,
    private val defaults: NSUserDefaults,
    private val dispatcher: CoroutineDispatcher,
) : KeyValueStorage {

    override suspend fun get(key: String): StorageResult<String?> = withContext(dispatcher) {
        StorageResult.Success(defaults.stringForKey(key))
    }

    override suspend fun put(key: String, value: String): StorageResult<Unit> = withContext(dispatcher) {
        defaults.setObject(value, forKey = key)
        StorageResult.Success(Unit)
    }

    override suspend fun remove(key: String): StorageResult<Unit> = withContext(dispatcher) {
        defaults.removeObjectForKey(key)
        StorageResult.Success(Unit)
    }

    override suspend fun clear(): StorageResult<Unit> = withContext(dispatcher) {
        defaults.removePersistentDomainForName(suiteName)
        StorageResult.Success(Unit)
    }
}
