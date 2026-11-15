package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.ExperimentalForeignApi
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
 * The probe has an account of its own per call ([KeychainQueries.newProbeAccount], a random UUID behind a fixed
 * prefix), so the add can never collide with anything: not with the probe of a concurrent `clear()` of this
 * process, not with one an app extension left behind in another group when it was killed. That is the point of
 * the design. A lookup for an existing probe would have to name a group (it cannot: the group is what is being
 * learned) and would then adopt a probe filed in whatever group the other process's default is: the wrong group,
 * and `clear()` would delete a store it does not own. A fresh add never reads anyone else's item: the group in its
 * answer is, by construction, the group this process's adds go to.
 *
 * The probe lives in a service of its own ([KeychainQueries.probeService]), so it is never matched by the store's
 * own reads, `remove` or `clear`, and it is removed before this returns. The removal names the probe service and
 * the resolved group but no account, so it also sweeps a probe of this app's default group that an earlier killed
 * `clear()` of this process left behind (and the probe of a concurrent `clear()` there: that one finds nothing to
 * delete and `errSecItemNotFound` is accepted). A probe in another group is none of this call's business and is
 * never touched.
 *
 * Fails closed: any status that is not success becomes a [StorageResult.Failure] and the caller does not delete
 * anything; there is no fallback to the wider delete. When the add succeeds but its answer names no group (or a blank
 * one), the probe is deleted by its own unique account with no group before the failure is returned, so no failing
 * path of a successful add leaves an item behind. Blocking and not suspending: two Security calls in a row on
 * the caller's thread, holding no lock and keeping no state and with no retry loop (the unique account makes a lost
 * race impossible, so there is nothing to retry), so a coroutine cancelled meanwhile cannot strand the probe (the
 * block runs to its end; the cancellation is observed when the operation returns).
 *
 * @param newProbeAccount the account of this call's probe; a fresh value on every call. A parameter so a test can
 *   prove the accounts differ.
 */
@OptIn(ExperimentalForeignApi::class)
internal class DefaultAccessGroup(
    private val keychain: KeychainPort,
    private val queries: KeychainQueries,
    private val newProbeAccount: () -> String = KeychainQueries::newProbeAccount,
) {

    /** The default access group, with the probe gone; or the failure of the first call that went wrong. */
    fun resolve(): StorageResult<String> {
        val account: String = newProbeAccount()
        val added: KeychainAccessGroup = queries.probeAdd(account).use { keychain.addReadingAccessGroup(it) }
        if (added.status != errSecSuccess) return failure(added.status)
        // A blank group is as unusable as none: a delete that names it would match nothing and report success.
        val group: String = added.group?.takeIf { it.isNotBlank() } ?: return unreadableGroup(account)
        // The probe is removed before the group is trusted: a group whose probe cannot be deleted is reported as a
        // failure, not used, so nothing is left behind silently.
        val removed: Int = queries.probeRemove(group).use { keychain.delete(it) }
        if (removed != errSecSuccess && removed != errSecItemNotFound) return failure(removed)
        return StorageResult.Success(group)
    }

    private fun failure(status: Int): StorageResult<String> =
        StorageResult.Failure(status.toStorageError(StorageOperation.CLEAR, key = null))

    /**
     * The add succeeded but its answer carried no usable access group: the store cannot tell which group is its own.
     * The probe IS in the Keychain, in a group this call never learned, so it is deleted by its own unique account
     * with no group (it can match only this call's probe). A failing cleanup keeps the original failure: there is
     * nothing more to try, and the failure is what the caller must see.
     */
    private fun unreadableGroup(account: String): StorageResult<String> {
        queries.probeRemoveOwn(account).use { keychain.delete(it) }
        return StorageResult.Failure(StorageError.OperationFailed(StorageOperation.CLEAR, key = null, platformCode = null))
    }
}
