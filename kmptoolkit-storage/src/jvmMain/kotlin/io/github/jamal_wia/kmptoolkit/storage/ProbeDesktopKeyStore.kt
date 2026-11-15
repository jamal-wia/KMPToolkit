@file:MustUseReturnValues

package io.github.jamal_wia.kmptoolkit.storage

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.BindingSelector
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.DesktopKeyStoreProbe
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.NativeExecutor
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.SecureStoreEnvironment

/**
 * Which operating-system key store a desktop secure store ([createSecureKeyValueStorage]) would use now, or why none can:
 * [StorageResult.Success] with the [DesktopKeyStore], or a typed failure ([StorageError.Unsupported] for no key store, no
 * user session or an unusable runtime, [StorageError.Unavailable] for a keychain that is locked while [options] forbids
 * a dialog or a service that does not answer in time).
 *
 * Never prompts, never reads, creates or deletes a key, and answers within a bound even when the operating system hangs:
 * it is safe to call at startup to decide whether to offer a secure store or to ask the user for a passphrase instead.
 * The first call selects the native binding once per process. A failure that is none of the typed answers is
 * [StorageError.OperationFailed] with [StorageOperation.ENSURE_KEY] and `key = null`.
 *
 * @param options the same options the store would be created with; only [DesktopSecureStorageOptions.allowUserInteraction]
 *   matters here (a locked keychain or collection answers `Unavailable` when `false`, success when `true`).
 */
public suspend fun probeDesktopKeyStore(
    options: DesktopSecureStorageOptions = DesktopSecureStorageOptions(),
): StorageResult<DesktopKeyStore> =
    DesktopKeyStoreProbe(BindingSelector.process, NativeExecutor.shared, SecureStoreEnvironment()).probe(options)
