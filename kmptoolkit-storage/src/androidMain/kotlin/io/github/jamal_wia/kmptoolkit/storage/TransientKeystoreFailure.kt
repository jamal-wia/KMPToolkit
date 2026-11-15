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

/**
 * Whether [error], or anything in its cause chain, says the device is locked or the user is not authenticated: the
 * platform refuses to use a key that needs the device unlocked ([AndroidSecureStorageOptions.unlockedDeviceRequired])
 * while the device is locked. A state that passes when the user unlocks, so it is reported as [StorageError.Unavailable]
 * and never leads to a key being dropped or replaced.
 *
 * The platform reports it in different forms by level, all recognised here:
 * - API 24-30 (keystore 1): `UserNotAuthenticatedException`.
 * - API 33 and later: an `android.security.KeyStoreException` whose `requiresUserAuthentication()` is true. That is
 *   what keystore 2 reports for a locked device (KeyMint DEVICE_LOCKED, -72, wrapped in a plain `InvalidKeyException`
 *   with no `UserNotAuthenticatedException`) and for an unauthenticated user (-26).
 * - API 31 and 32: the same plain `InvalidKeyException`, but nothing public reads its code there. The cipher marks that
 *   shape as a [DeviceLockedKeyException] for a store that needs the device unlocked ([isKeystore2LockedShape]), which
 *   is recognised here.
 *
 * The API 31+ form is read from the platform source and framework bytecode, not observed on a device (see the A4 list in
 * the platform notes).
 *
 * @param sdkInt the platform level; a parameter only so the gate can be tested on one Robolectric level. Production
 *   callers use the default.
 */
internal fun isDeviceLockedFailure(error: Throwable, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
    val seen: MutableSet<Throwable> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    var current: Throwable? = error
    while (current != null && seen.add(current)) {
        if (current is android.security.keystore.UserNotAuthenticatedException || current is DeviceLockedKeyException) return true
        current = current.cause
    }
    return sdkInt >= Build.VERSION_CODES.TIRAMISU && DeviceLockedApi33.inChain(error)
}

/** The only place that names `requiresUserAuthentication()`, public from API 33; kept apart for the same reason as [TransientKeystoreFailureApi33]. */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
private object DeviceLockedApi33 {

    fun inChain(error: Throwable): Boolean {
        val seen: MutableSet<Throwable> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            if (current is KeyStoreException && current.requiresUserAuthentication()) return true
            current = current.cause
        }
        return false
    }
}

/** The message of the `InvalidKeyException` the keystore-2 provider makes of every cipher-init error it has no named class for. */
internal const val KEYSTORE2_OPERATION_FAILED_MESSAGE: String = "Keystore operation failed"

/**
 * Whether [error] has the shape in which API 31 and 32 (keystore 2, before `KeyStoreException.requiresUserAuthentication()`
 * is public) report a refused key operation such as a locked device: exactly an `InvalidKeyException` (not one of its
 * named subclasses: `UserNotAuthenticatedException`, `KeyPermanentlyInvalidatedException`, the not-yet-valid and expired
 * ones, which keep their own handling) with the message [KEYSTORE2_OPERATION_FAILED_MESSAGE] and an
 * `android.security.KeyStoreException` as its direct cause. The class is matched by name because it is hidden on the
 * levels this is for, and no hidden method is read: the code of the `KeyStoreException` cannot be told here, so this is
 * only a shape, and it is applied only for a key that needs the device unlocked.
 *
 * Always `false` outside API 31 and 32: below, the platform reports a locked device as `UserNotAuthenticatedException`;
 * from 33 [isDeviceLockedFailure] asks the platform.
 *
 * @param sdkInt the platform level; a parameter only so the gate can be tested on one Robolectric level.
 */
internal fun isKeystore2LockedShape(error: Throwable, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    (sdkInt == Build.VERSION_CODES.S || sdkInt == Build.VERSION_CODES.S_V2) &&
        error.javaClass == java.security.InvalidKeyException::class.java &&
        error.message == KEYSTORE2_OPERATION_FAILED_MESSAGE &&
        error.cause?.javaClass?.name == "android.security.KeyStoreException"

/**
 * Whether [error], or anything in its cause chain, is `StrongBoxUnavailableException`: the device has no StrongBox
 * secure element. The class exists from API 28; below it this always answers `false`, and the caller never asks for a
 * StrongBox there.
 */
internal fun isStrongBoxUnavailableFailure(
    error: Throwable,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Boolean {
    if (sdkInt < Build.VERSION_CODES.P) return false
    return StrongBoxFailureApi28.inChain(error)
}

/** The only place that names `StrongBoxUnavailableException`, kept apart so a device below API 28 never resolves it. */
@TargetApi(Build.VERSION_CODES.P)
private object StrongBoxFailureApi28 {

    fun inChain(error: Throwable): Boolean {
        val seen: MutableSet<Throwable> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            if (current is android.security.keystore.StrongBoxUnavailableException) return true
            current = current.cause
        }
        return false
    }
}
