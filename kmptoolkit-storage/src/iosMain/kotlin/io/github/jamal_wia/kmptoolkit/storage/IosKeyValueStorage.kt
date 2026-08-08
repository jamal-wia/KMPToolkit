package io.github.jamal_wia.kmptoolkit.storage

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
 */
internal class IosKeyValueStorage(
    private val suiteName: String,
    private val defaults: NSUserDefaults,
) : KeyValueStorage {

    override fun get(key: String): StorageResult<String?> =
        StorageResult.Success(defaults.stringForKey(key))

    override fun put(key: String, value: String): StorageResult<Unit> {
        defaults.setObject(value, forKey = key)
        return StorageResult.Success(Unit)
    }

    override fun remove(key: String): StorageResult<Unit> {
        defaults.removeObjectForKey(key)
        return StorageResult.Success(Unit)
    }

    override fun clear(): StorageResult<Unit> {
        defaults.removePersistentDomainForName(suiteName)
        return StorageResult.Success(Unit)
    }
}
