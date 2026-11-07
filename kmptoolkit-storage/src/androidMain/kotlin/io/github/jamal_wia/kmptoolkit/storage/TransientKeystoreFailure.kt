package io.github.jamal_wia.kmptoolkit.storage

import android.os.Build
import android.annotation.TargetApi
import android.security.KeyStoreException

/**
 * Whether [error], or anything in its cause chain, is an `android.security.KeyStoreException` that
 * the platform itself reports as a transient failure — a keystore daemon that is restarting, a
 * provider that is briefly busy — as opposed to a key that is gone or a ciphertext that is bad.
 *
 * `KeyStoreException.isTransientFailure()` exists from API 33. Below that this always answers
 * `false`, so behavior on older devices is exactly what it was before this check existed: the
 * caller keeps treating every decrypt failure as permanent. The chain is walked because the
 * keystore fault usually reaches the caller wrapped in an `InvalidKeyException` or a
 * `ProviderException`, never as the top-level exception.
 *
 * @param sdkInt the platform level; a parameter only so the gate can be tested on one Robolectric
 *   level. Production callers use the default.
 */
internal fun isTransientKeystoreFailure(
    error: Throwable,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Boolean {
    if (sdkInt < Build.VERSION_CODES.TIRAMISU) return false
    return TransientKeystoreFailureApi33.inChain(error)
}

/**
 * The only place that names `KeyStoreException`, kept apart so a device below API 33 never has to
 * resolve a class its platform does not have.
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
private object TransientKeystoreFailureApi33 {

    fun inChain(error: Throwable): Boolean {
        val seen: MutableSet<Throwable> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            if (current is KeyStoreException && current.isTransientFailure) return true
            current = current.cause
        }
        return false
    }
}
