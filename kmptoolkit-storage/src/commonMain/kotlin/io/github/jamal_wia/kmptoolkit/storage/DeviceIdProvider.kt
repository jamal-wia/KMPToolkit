package io.github.jamal_wia.kmptoolkit.storage

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A random identifier generated once and kept in a [KeyValueStorage], so it stays the same for as
 * long as that store survives.
 *
 * What it is for: telling "the same phone, signed in again" apart from "a new phone" — support
 * lookups, a device list a user can revoke sessions from, rate limiting per install. It is
 * generated locally and carries nothing about the hardware, the user, or the OS.
 *
 * What it is **not**: an advertising identifier, a fingerprint, or anything to authenticate with.
 * It lives in the plain store rather than the encrypted one precisely because it is not a secret —
 * anything that grants access must be a token from your server, not this.
 *
 * The value survives a logout, an app update, and a process restart. It does **not** survive
 * [KeyValueStorage.clear], a reinstall, or a user clearing app data: the next [current] then
 * generates a new one. That is the intended trade — an id that outlived a reinstall would be a
 * cross-install tracker, which is exactly what platform policy forbids.
 *
 * ```kotlin
 * val deviceId: String = DeviceIdProvider(storage).current()
 * httpClient.header("X-Device-Id", deviceId)
 * ```
 *
 * @param storage where the id is kept. Passing a [SecureKeyValueStorage] works and is not wrong,
 *   only slower and — because a destroyed Keystore key would rotate the id — less stable.
 * @param key the entry name inside [storage]. Override it only to avoid a clash with a key your
 *   own code already uses in the same store.
 */
public class DeviceIdProvider(
    private val storage: KeyValueStorage,
    private val key: String = DEFAULT_KEY,
) {

    /**
     * The device id, generating and persisting one on the first call.
     *
     * Always returns an id — there is no failure case for a caller to handle, because a header that
     * must be sent cannot wait for storage to recover. What can fail is the *persistence*: if the
     * store cannot be written, the returned id is still valid for this call but a later call
     * produces a different one. An id that changes is a degraded signal, not a broken app, which is
     * why this is not a [StorageResult].
     *
     * Deliberately not memoized in memory: the store is the single source of truth, so a
     * [KeyValueStorage.clear] takes effect at the very next call rather than at the next process
     * start. Every call is one small read.
     */
    @OptIn(ExperimentalUuidApi::class)
    public fun current(): String {
        val stored: String? = storage.get(key).getOrNull()
        if (stored != null && stored.isNotBlank()) return stored

        // Reached when the key is absent, when the entry is unreadable, and when it holds a blank
        // value — a store hand-edited in a debug build, or a partial restore. All three mean "there
        // is no usable id here", and overwriting is the only way out of the last two.
        val generated: String = Uuid.random().toString()
        storage.put(key, generated)
        return generated
    }

    public companion object {
        /** `"kmptoolkit.storage.device_id"`. */
        public const val DEFAULT_KEY: String = "kmptoolkit.storage.device_id"
    }
}
