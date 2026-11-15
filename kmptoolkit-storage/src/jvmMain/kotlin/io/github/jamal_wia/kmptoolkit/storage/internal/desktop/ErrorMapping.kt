package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.DesktopKeyStore
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.UnsupportedReason
import java.io.IOException

/**
 * The answer of a native call, as the key lifecycle uses it: the value, a definitive "no item at the own identity"
 * ([NotFound], the only input of the "key lost" rule 4.4 takes from the OS), or a typed failure that never leads to
 * quarantine or key creation.
 */
internal sealed interface Verdict<out T> {

    /** The call succeeded with [value] (owned by the caller). */
    class Ok<out T>(val value: T) : Verdict<T> {
        override fun toString(): String = "Ok"
    }

    /** Definitive not-found at the store's identity (plan 4.4). Never produced for a locked, denied or hung store. */
    data object NotFound : Verdict<Nothing>

    /** The call failed; [error] is what the store's operation answers. */
    class Failed(val error: StorageError) : Verdict<Nothing> {
        override fun toString(): String = "Failed($error)"
    }
}

/**
 * The error-mapping tables of plan 4.5 (macOS), 4.6 (Windows) and 4.7 (Linux) as pure functions of raw codes. No
 * binding, no I/O, no state: a table test drives every row (T5).
 */
internal object ErrorMapping {
    // OSStatus values of 4.5.
    const val ERR_SEC_ITEM_NOT_FOUND: Int = -25300
    const val ERR_SEC_DUPLICATE_ITEM: Int = -25299
    const val ERR_SEC_INTERACTION_NOT_ALLOWED: Int = -25308
    const val ERR_SEC_USER_CANCELED: Int = -128
    const val ERR_SEC_AUTH_FAILED: Int = -25293
    const val ERR_SEC_NO_DEFAULT_KEYCHAIN: Int = -25307
    const val ERR_SEC_NO_SUCH_KEYCHAIN: Int = -25294
    const val ERR_SEC_NOT_AVAILABLE: Int = -25291
    const val ERR_SEC_MISSING_ENTITLEMENT: Int = -34018

    // Windows (4.6).
    const val ERROR_NO_SUCH_LOGON_SESSION: Int = 1312

    // Linux (4.7).
    const val DBUS_SERVICE_UNKNOWN: String = "org.freedesktop.DBus.Error.ServiceUnknown"
    const val DBUS_SPAWN_CHILD_EXITED: String = "org.freedesktop.DBus.Error.Spawn.ChildExited"
    const val DBUS_NO_REPLY: String = "org.freedesktop.DBus.Error.NoReply"
    const val DBUS_ACCESS_DENIED: String = "org.freedesktop.DBus.Error.AccessDenied"
    const val SECRET_IS_LOCKED: String = "org.freedesktop.Secret.Error.IsLocked"
    const val G_IO_ERROR_DOMAIN: String = "g-io-error-quark"
    const val G_IO_ERROR_CANCELLED: Int = 19
    const val G_IO_ERROR_TIMED_OUT: Int = 24
    const val G_IO_ERROR_FAILED_HANDLED: Int = 46

    /**
     * Whether a macOS -25300 from a call that cannot show a dialog is a definitive not-found. Plan 4.5: only if the Mac
     * verification confirms that `kSecUseAuthenticationUIFail` never reports a locked keychain or another code
     * identity's item as -25300. Until that verification has run, the conservative rule applies: a non-interactive
     * -25300 answers `Unavailable`, never "key lost", so a locked or denied key is never destroyed.
     */
    const val MAC_NON_INTERACTIVE_NOT_FOUND_IS_DEFINITIVE: Boolean = false

    /** The failure every table shares for a status it does not name: the store's operation failed with a platform code. */
    fun operationFailed(operation: StorageOperation, key: String?, code: Int): StorageError =
        StorageError.OperationFailed(operation, key = key, platformCode = code)

    /**
     * macOS `SecItemCopyMatching` of the data key. [interactive] says whether the call could show a dialog
     * (`allowUi = true`); [nonInteractiveNotFoundIsDefinitive] is [MAC_NON_INTERACTIVE_NOT_FOUND_IS_DEFINITIVE] (a
     * parameter so both outcomes of the open verification are testable). A not-found is [Verdict.NotFound]; every other
     * status is the failure the table names.
     */
    fun macCopy(
        status: Int,
        interactive: Boolean,
        operation: StorageOperation,
        key: String?,
        nonInteractiveNotFoundIsDefinitive: Boolean = MAC_NON_INTERACTIVE_NOT_FOUND_IS_DEFINITIVE,
    ): Verdict<Nothing> {
        if (status == ERR_SEC_ITEM_NOT_FOUND) {
            return if (interactive || nonInteractiveNotFoundIsDefinitive) {
                Verdict.NotFound
            } else {
                Verdict.Failed(StorageError.Unavailable())
            }
        }
        return Verdict.Failed(macStatus(status, operation, key))
    }

    /**
     * macOS `SecItemAdd` or `SecItemDelete` of the key item (4.4 step 3). A duplicate after our own delete is a concurrent
     * creator outside the lock: `Unavailable`. For a delete, -25300 means nothing to delete and is fine ([Verdict.Ok]);
     * a refusal on a planted item (-25293, -128) is `AccessDenied` and the caller undoes its step (fail closed).
     */
    fun macDelete(status: Int, operation: StorageOperation, key: String?): Verdict<Unit> = when (status) {
        0, ERR_SEC_ITEM_NOT_FOUND -> Verdict.Ok(Unit)
        else -> Verdict.Failed(macStatus(status, operation, key))
    }

    /** See [macDelete]; a successful add is [Verdict.Ok]. */
    fun macAdd(status: Int, operation: StorageOperation, key: String?): Verdict<Unit> =
        if (status == 0) Verdict.Ok(Unit) else Verdict.Failed(macStatus(status, operation, key))

    /** The OSStatus rows of 4.5 that do not depend on which call produced them. */
    fun macStatus(status: Int, operation: StorageOperation, key: String?): StorageError = when (status) {
        ERR_SEC_DUPLICATE_ITEM -> StorageError.Unavailable()
        ERR_SEC_INTERACTION_NOT_ALLOWED -> StorageError.Unavailable()
        ERR_SEC_NOT_AVAILABLE -> StorageError.Unavailable()
        ERR_SEC_USER_CANCELED -> StorageError.AccessDenied(platformCode = ERR_SEC_USER_CANCELED)
        ERR_SEC_AUTH_FAILED -> StorageError.AccessDenied(platformCode = ERR_SEC_AUTH_FAILED)
        ERR_SEC_NO_DEFAULT_KEYCHAIN, ERR_SEC_NO_SUCH_KEYCHAIN -> StorageError.Unsupported(UnsupportedReason.NO_KEY_STORE)
        ERR_SEC_MISSING_ENTITLEMENT -> StorageError.Unsupported(UnsupportedReason.MISSING_ENTITLEMENT)
        // -25300 outside a copy (an add that reports it) is not a row of the table either.
        else -> operationFailed(operation, key, status)
    }

    /** The macOS probe (4.5): not-found or success is the file keychain; -25307 no store; -25308 depends on interaction. */
    fun macProbe(status: Int, allowUserInteraction: Boolean, operation: StorageOperation = StorageOperation.ENSURE_KEY): Verdict<DesktopKeyStore> = when (status) {
        0, ERR_SEC_ITEM_NOT_FOUND -> Verdict.Ok(DesktopKeyStore.MACOS_FILE_KEYCHAIN)
        ERR_SEC_INTERACTION_NOT_ALLOWED ->
            if (allowUserInteraction) Verdict.Ok(DesktopKeyStore.MACOS_FILE_KEYCHAIN) else Verdict.Failed(StorageError.Unavailable())
        else -> Verdict.Failed(macStatus(status, operation, null))
    }

    /** A DPAPI throwaway round trip of 4.6: it worked, or it failed with [code]. */
    sealed interface RoundTrip {
        /** The 1-byte protect and unprotect both succeeded: DPAPI works for this user. */
        data object Worked : RoundTrip

        /** One of the two calls failed with [code]. */
        class Failed(val code: Int) : RoundTrip
    }

    /**
     * Windows: `CryptUnprotectData` of the header's blob failed with [unprotectCode]; [roundTrip] is the 1-byte throwaway
     * round trip made afterwards (S11). Only a working round trip makes the failure the store's own (`Undecryptable`);
     * otherwise DPAPI itself is the problem and the answer is `Unavailable` (or `Unsupported(NO_USER_SESSION)` for 1312).
     */
    fun windowsUnprotectFailure(unprotectCode: Int, roundTrip: RoundTrip): StorageError = when (roundTrip) {
        RoundTrip.Worked -> StorageError.Undecryptable(key = null, cause = IOException("DPAPI could not unwrap the data key (status $unprotectCode)"))
        is RoundTrip.Failed ->
            if (roundTrip.code == ERROR_NO_SUCH_LOGON_SESSION) {
                StorageError.Unsupported(UnsupportedReason.NO_USER_SESSION)
            } else {
                StorageError.Unavailable(IOException("DPAPI is unavailable (status ${roundTrip.code})"))
            }
    }

    /** Windows `CryptProtectData` failed with [code]: 1312 has no user session, anything else fails the operation. */
    fun windowsProtectFailure(code: Int, operation: StorageOperation, key: String?): StorageError =
        if (code == ERROR_NO_SUCH_LOGON_SESSION) {
            StorageError.Unsupported(UnsupportedReason.NO_USER_SESSION)
        } else {
            operationFailed(operation, key, code)
        }

    /** The Windows probe (4.6): the round trip works (`WINDOWS_DPAPI`) or the mapping of its failure. */
    fun windowsProbe(roundTrip: RoundTrip): Verdict<DesktopKeyStore> = when (roundTrip) {
        RoundTrip.Worked -> Verdict.Ok(DesktopKeyStore.WINDOWS_DPAPI)
        is RoundTrip.Failed ->
            if (roundTrip.code == ERROR_NO_SUCH_LOGON_SESSION) {
                Verdict.Failed(StorageError.Unsupported(UnsupportedReason.NO_USER_SESSION))
            } else {
                Verdict.Failed(StorageError.Unavailable(IOException("DPAPI is unavailable (status ${roundTrip.code})")))
            }
    }

    /**
     * Linux: a failed libsecret call ([status]) by the rows of 4.7. [SymbolMissing][RawStatus.isSymbolMissing] is
     * `Unsupported(NO_KEY_STORE)`, never `NATIVE_ACCESS_UNAVAILABLE`.
     */
    fun linuxStatus(status: RawStatus, operation: StorageOperation, key: String?): StorageError {
        if (status.isSymbolMissing) return StorageError.Unsupported(UnsupportedReason.NO_KEY_STORE)
        return when (status.errorName) {
            DBUS_SERVICE_UNKNOWN -> StorageError.Unsupported(UnsupportedReason.NO_KEY_STORE)
            DBUS_ACCESS_DENIED -> StorageError.Unsupported(UnsupportedReason.NO_KEY_STORE)
            DBUS_SPAWN_CHILD_EXITED, DBUS_NO_REPLY, SECRET_IS_LOCKED -> StorageError.Unavailable()
            else -> when {
                status.domain == G_IO_ERROR_DOMAIN && status.code == G_IO_ERROR_TIMED_OUT -> StorageError.Unavailable()
                // A dismissed unlock prompt (libsecret reports a handled or cancelled GError): the user said no.
                status.domain == G_IO_ERROR_DOMAIN && (status.code == G_IO_ERROR_FAILED_HANDLED || status.code == G_IO_ERROR_CANCELLED) ->
                    StorageError.AccessDenied(platformCode = status.code)
                else -> operationFailed(operation, key, status.code)
            }
        }
    }

    /**
     * Linux lookup (4.7): [SecretLookup.NotFound] is the definitive not-found; a locked match is never "not found" and never
     * leads to quarantine or creation (`AccessDenied` when a prompt was allowed and stayed locked, `Unavailable` without UI);
     * [SecretLookup.Found] is the key and is handled by the caller.
     */
    fun linuxLockedMatch(allowUi: Boolean): StorageError =
        if (allowUi) StorageError.AccessDenied() else StorageError.Unavailable()

    /** Linux with `allowUserInteraction = false`: a locked default collection (or none: storing would prompt) answers `Unavailable` without a prompt. */
    fun linuxCollectionLockedWithoutUi(): StorageError = StorageError.Unavailable()

    /** The Linux probe (4.7) after a clean read of the default collection's `Locked` flag. */
    fun linuxProbe(locked: Boolean, portal: Boolean, allowUserInteraction: Boolean): Verdict<DesktopKeyStore> = when {
        locked && !allowUserInteraction -> Verdict.Failed(StorageError.Unavailable())
        portal -> Verdict.Ok(DesktopKeyStore.LINUX_SECRET_PORTAL)
        else -> Verdict.Ok(DesktopKeyStore.LINUX_SECRET_SERVICE)
    }

    /**
     * Linux: whether a session bus is reachable, checked in milliseconds before any native call. Neither
     * `DBUS_SESSION_BUS_ADDRESS` nor `$XDG_RUNTIME_DIR/bus` is `Unsupported(NO_USER_SESSION)`.
     */
    fun linuxHasSessionBus(dbusSessionBusAddress: String?, xdgRuntimeDir: String?, busSocketExists: (String) -> Boolean): Boolean {
        if (!dbusSessionBusAddress.isNullOrBlank()) return true
        if (xdgRuntimeDir.isNullOrBlank()) return false
        return busSocketExists(xdgRuntimeDir.trimEnd('/') + "/bus")
    }

    /** A native call outside the table: the executor's own outcomes (time limit, unavailable binding, thrown). */
    fun nativeOutcome(outcome: NativeOutcome<*>, operation: StorageOperation, key: String?): StorageError = when (outcome) {
        is NativeOutcome.Completed -> error("a completed call has no error")
        NativeOutcome.TimedOut -> StorageError.Unavailable(IOException("The native key-store call passed its time limit"))
        is NativeOutcome.NativeUnavailable -> StorageError.Unsupported(UnsupportedReason.NATIVE_ACCESS_UNAVAILABLE, outcome.cause)
        is NativeOutcome.Threw -> StorageError.OperationFailed(operation, key = key, cause = outcome.cause)
    }
}
