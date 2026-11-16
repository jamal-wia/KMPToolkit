package io.github.jamal_wia.kmptoolkit.smoke.storage

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.MacKeychainCalls
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawStatus

/** A keychain in a map. Records the calls and the allowUi values; every failure mode is a field. */
internal class FakeKeychain : MacKeychainCalls {
    val items: MutableMap<Pair<String, String>, ByteArray> = mutableMapOf()
    val calls: MutableList<String> = mutableListOf()
    val allowUiValues: MutableList<Boolean> = mutableListOf()
    var deleteStatus: Int = 0
    var deleteRemovesItem: Boolean = true
    var copyFailure: RawStatus? = null
    var beforeCopy: () -> Unit = {}

    override fun copyGenericPassword(service: String, account: String, allowUi: Boolean): RawResult<ByteArray?> {
        calls += "copy"
        allowUiValues += allowUi
        beforeCopy()
        copyFailure?.let { return RawResult.Failure(it) }
        val found: ByteArray? = items[service to account]
        return if (found == null) RawResult.Failure(RawStatus(-25300)) else RawResult.Success(found)
    }

    override fun addGenericPassword(service: String, account: String, label: String, secret: ByteArray, allowUi: Boolean): Int {
        items[service to account] = secret
        return 0
    }

    override fun deleteGenericPassword(service: String, account: String, allowUi: Boolean): Int {
        calls += "delete"
        allowUiValues += allowUi
        if (deleteStatus != 0) return deleteStatus
        if (deleteRemovesItem) items.remove(service to account)
        return 0
    }

    override fun probe(allowUi: Boolean): Int = 0
}
