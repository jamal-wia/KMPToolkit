package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess

/**
 * Resolves the access group the Keychain files the app's items under when no group is named (the first entry of
 * the app's `keychain-access-groups`, or its application identifier), so `clear()` of a store with
 * `accessGroup = null` can name it (I-2b, decision D-18 of the 5.1.0 ticket).
 *
 * A delete that names no group matches every group the app belongs to, so with a same-named store in a shared
 * group it would remove that store's items too. The Security framework has no call that returns the default
 * group, so it is learned the way every Keychain client learns it: a probe item is added without a group and the
 * group it was filed under is read from the result.
 *
 * The probe lives in a service of its own ([KeychainQueries.probeService]), so it is never matched by the store's
 * own reads, `remove` or `clear`, and it is removed before this returns. A leftover probe (a process killed
 * between the add and the delete) is found by the lookup that comes first, read for its group and removed, so
 * no probe is ever orphaned for more than one `clear()`.
 *
 * Fails closed: any status that is not success becomes a [StorageResult.Failure] and the caller does not delete
 * anything; there is no fallback to the wider delete. Blocking and not suspending: three Security calls in a row on
 * the caller's thread, holding no lock and keeping no state, so a coroutine cancelled meanwhile cannot strand the
 * probe (the block runs to its end; the cancellation is observed when the operation returns).
 */
@OptIn(ExperimentalForeignApi::class)
internal class DefaultAccessGroup(
    private val keychain: KeychainPort,
    private val queries: KeychainQueries,
) {

    /** The default access group, with the probe gone; or the failure of the first call that went wrong. */
    fun resolve(): StorageResult<String> {
        val found: StorageResult<String> = findOrAddProbe()
        val group: String = when (found) {
            is StorageResult.Success -> found.value
            is StorageResult.Failure -> return found
        }
        // The probe is removed before the group is trusted: a group learned from an orphan that cannot be
        // deleted is reported as a failure, not used, so nothing is left behind silently.
        val removed: Int = queries.probeRemove(group).use { keychain.delete(it) }
        if (removed != errSecSuccess && removed != errSecItemNotFound) return failure(removed)
        return StorageResult.Success(group)
    }

    /**
     * The group of the probe, found as a leftover or added now; the probe still exists on success.
     *
     * Another `clear()` (a second store, an app extension) may add or remove the probe between this call's steps, so a
     * duplicate on add means "look again", bounded: a probe that keeps appearing and disappearing is reported as the
     * duplicate it is, never retried forever.
     */
    private fun findOrAddProbe(): StorageResult<String> {
        var lastStatus: Int = errSecDuplicateItem
        repeat(MAX_ATTEMPTS) {
            val found: KeychainAccessGroup = queries.probeLookup().use { keychain.readAccessGroup(it) }
            when (found.status) {
                errSecSuccess -> return groupOf(found)
                errSecItemNotFound -> Unit
                else -> return failure(found.status)
            }
            val added: KeychainAccessGroup = queries.probeAdd().use { keychain.addReadingAccessGroup(it) }
            when (added.status) {
                errSecSuccess -> return groupOf(added)
                errSecDuplicateItem -> lastStatus = added.status
                else -> return failure(added.status)
            }
        }
        return failure(lastStatus)
    }

    private fun groupOf(read: KeychainAccessGroup): StorageResult<String> {
        val group: String = read.group ?: return unreadableGroup()
        return StorageResult.Success(group)
    }

    private fun failure(status: Int): StorageResult<String> =
        StorageResult.Failure(status.toStorageError(StorageOperation.CLEAR, key = null))

    /** The call succeeded but returned no access group string: the store cannot tell which group is its own. */
    private fun unreadableGroup(): StorageResult<String> =
        StorageResult.Failure(StorageError.OperationFailed(StorageOperation.CLEAR, key = null, platformCode = null))

    private companion object {
        /** Lookup-then-add rounds before giving up on a probe another writer keeps changing. */
        const val MAX_ATTEMPTS: Int = 3
    }
}
