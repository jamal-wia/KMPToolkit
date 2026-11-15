package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi

/**
 * One native binding: the raw operating-system calls of the three desktop key stores, and nothing else. No policy
 * lives here: key lifecycle, the "key lost" rule, error mapping and the cross-process lock sit above this seam,
 * once, for every binding. The FFM binding lives in this artifact (JDK 22 or newer), the JNA binding in the
 * optional `kmptoolkit-storage-jna` artifact.
 *
 * ### Contract for an implementation
 * - A concrete implementation is a class with a public no-argument constructor, loaded by its fixed class name
 *   (`io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm.FfmNativeKeyStoreBinding` or
 *   `io.github.jamal_wia.kmptoolkit.storage.jna.JnaNativeKeyStoreBinding`); there is no `ServiceLoader` and no
 *   other plug-in point. The constructor must not call the operating system: loading is cheap and does not prompt.
 * - Every call is blocking, is called from the one native-executor thread of the process (never from two threads at
 *   once), and reports an operating-system failure as a raw status, never as an exception. A call may still throw a
 *   [LinkageError], an [IllegalCallerException] or a [SecurityException]; the caller maps those to
 *   `Unsupported(NATIVE_ACCESS_UNAVAILABLE)`.
 * - Every call frees every operating-system-allocated output before it returns (`CFRelease`, `LocalFree`,
 *   `secret_password_free`, `g_free`, `GError`) and zeroes its native copy of the key.
 * - The seam has 11 calls in all: 4 macOS, 2 DPAPI, 5 Linux. There is deliberately no "update" call: a key item is
 *   never updated, only deleted and created again.
 * - macOS calls never pass `kSecUseAuthenticationUISkip` (it silently omits items that need UI and answers -25300);
 *   with `allowUi = false` they pass `kSecUseAuthenticationUIFail`; this holds for
 *   copy, add and delete alike (the add and delete calls of the key-creation path run while the store's locks are held).
 */
@ToolkitInternalApi
@SubclassOptInRequired(ToolkitInternalApi::class)
public interface NativeKeyStoreBinding {

    /** The raw Security.framework calls; every status is an `OSStatus`. */
    public fun macOs(): MacKeychainCalls

    /** The raw crypt32 calls; every status is `0` or `GetLastError()`. */
    public fun windows(): DpapiCalls

    /** The raw libsecret and gio calls; every status is a [SecretStatus]. */
    public fun linux(): SecretServiceCalls
}

/**
 * The raw macOS Security.framework calls: generic-password items of the login (file) keychain, addressed by a service
 * and an account string. Every status is an `OSStatus`, `0` for success.
 */
@ToolkitInternalApi
@SubclassOptInRequired(ToolkitInternalApi::class)
@MustUseReturnValues
public interface MacKeychainCalls {

    /**
     * `SecItemCopyMatching` of one generic password with `kSecReturnData`. [allowUi] `false` passes
     * `kSecUseAuthenticationUIFail`. A found item is `Success(data)`; -25300 and every other status are a failure
     * carrying that status, so the caller can tell a definitive not-found from a locked keychain.
     */
    public fun copyGenericPassword(service: String, account: String, allowUi: Boolean): RawResult<ByteArray?>

    /**
     * `SecItemAdd` of a generic password with the data [secret] and the human label [label]; returns the `OSStatus`.
     * With [allowUi] `false` the query carries `kSecUseAuthenticationUI = kSecUseAuthenticationUIFail`, so a locked
     * keychain answers -25308 instead of showing the unlock dialog while the caller holds the store's locks. If
     * `SecItemAdd` ignores the key on the file keychain, the Mac verification records that and the platform notes say
     * so ("an OS that cannot honour it per call says so").
     */
    public fun addGenericPassword(service: String, account: String, label: String, secret: ByteArray, allowUi: Boolean): Int

    /**
     * `SecItemDelete` of the generic password at [service] and [account]; returns the `OSStatus`. With [allowUi] `false`
     * the query carries `kSecUseAuthenticationUI = kSecUseAuthenticationUIFail`, so deleting an item that needs an
     * access or unlock dialog (the item of a previous unsigned build) answers -25308 or -25293 instead of prompting.
     */
    public fun deleteGenericPassword(service: String, account: String, allowUi: Boolean): Int

    /** An attribute-only `SecItemCopyMatching` for a probe service, UI suppressed when [allowUi] is `false`; returns the `OSStatus`. */
    public fun probe(allowUi: Boolean): Int
}

/**
 * The raw Windows DPAPI calls, current user only (`CRYPTPROTECT_UI_FORBIDDEN`, never `LOCAL_MACHINE`). A status is `0`
 * or the value of `GetLastError()`.
 */
@ToolkitInternalApi
@SubclassOptInRequired(ToolkitInternalApi::class)
@MustUseReturnValues
public interface DpapiCalls {

    /** `CryptProtectData` of [data] with the optional [entropy]. */
    public fun protect(data: ByteArray, entropy: ByteArray?): RawResult<ByteArray>

    /** `CryptUnprotectData` of [blob] with the optional [entropy]. */
    public fun unprotect(blob: ByteArray, entropy: ByteArray?): RawResult<ByteArray>
}

/**
 * The raw libsecret and gio calls of the freedesktop Secret Service. Lookup goes through libsecret's backend
 * selection (`secret_backend_get`), so the Flatpak portal and file backends keep working; the Secret Service D-Bus
 * API (`secret_service_search_sync`) is never used for lookup. Needs libsecret 0.19 or newer: a binding resolves
 * the `secret_password_search_sync` symbol explicitly when it loads and reports [RawStatus.SYMBOL_MISSING] for it,
 * never an exception (FFM: `SymbolLookup.find` returning empty; JNA: `NativeLibrary.getFunction` with the
 * `UnsatisfiedLinkError` caught for that one symbol).
 */
@ToolkitInternalApi
@SubclassOptInRequired(ToolkitInternalApi::class)
@MustUseReturnValues
public interface SecretServiceCalls {

    /**
     * `secret_password_search_sync` with `SECRET_SEARCH_ALL`, plus `SECRET_SEARCH_UNLOCK` when [allowUi] is `true`.
     * [SecretLookup.Found] carries the secret read with `secret_retrievable_retrieve_secret_sync`;
     * [SecretLookup.LockedMatch] is a returned `SecretItem` whose `secret_item_get_locked()` is true (file-backend
     * items are never locked); [SecretLookup.NotFound] is an empty list.
     */
    public fun lookup(attributes: Map<String, String>, allowUi: Boolean): RawResult<SecretLookup>

    /** `secret_password_store_sync` into the default collection; returns [RawStatus.SUCCESS] or the failure. */
    public fun store(label: String, attributes: Map<String, String>, secret: ByteArray): SecretStatus

    /** `secret_password_clear_sync` of the items that match every one of [attributes]; returns [RawStatus.SUCCESS] or the failure. */
    public fun clear(attributes: Map<String, String>): SecretStatus

    /**
     * Whether the default collection is locked (the `Locked` property); never prompts. When no collection exists at the
     * `default` alias this is `Success(true)`: storing would prompt to create a keyring, which is a prompt like an
     * unlock. It is never a failure and never `false` for that case.
     */
    public fun defaultCollectionLocked(): RawResult<Boolean>

    /** Whether libsecret selected its Flatpak Secret-portal backend; never prompts. */
    public fun backendIsPortal(): RawResult<Boolean>
}

/** What a [SecretServiceCalls.lookup] found. */
@ToolkitInternalApi
public sealed interface SecretLookup {

    /** An unlocked match; [secret] is the stored secret (base64 text as bytes), owned by the caller, who wipes it. */
    @ToolkitInternalApi
    public class Found(public val secret: ByteArray) : SecretLookup {
        override fun toString(): String = "Found"
    }

    /** At least one matching item exists but stays locked: never "not found". */
    @ToolkitInternalApi
    public data object LockedMatch : SecretLookup

    /** No item matches the attributes in any collection, locked or unlocked. */
    @ToolkitInternalApi
    public data object NotFound : SecretLookup
}

/** The outcome of a raw call that returns data: the data, or a failure status. */
@ToolkitInternalApi
public sealed interface RawResult<out T> {

    /** The call succeeded with [value] (owned by the caller). */
    @ToolkitInternalApi
    public class Success<out T>(public val value: T) : RawResult<T> {
        override fun toString(): String = "Success"
    }

    /** The call failed with [status]. */
    @ToolkitInternalApi
    public class Failure(public val status: RawStatus) : RawResult<Nothing> {
        override fun toString(): String = "Failure($status)"
    }
}

/**
 * A raw operating-system failure: [code] is the `OSStatus` (macOS), the `GetLastError()` value (Windows) or the `GError`
 * code (Linux); [domain] is the `GError` domain (Linux, for example `g-io-error-quark`), [errorName] the D-Bus error
 * name (for example `org.freedesktop.DBus.Error.ServiceUnknown`) when one is known.
 */
@ToolkitInternalApi
public class RawStatus(
    /** The numeric status. */
    public val code: Int,
    /** The `GError` domain, or `null` where the platform has none. */
    public val domain: String? = null,
    /** The D-Bus error name, or `null` where none is known. */
    public val errorName: String? = null,
) {
    /** Whether this is the success status. */
    public val isSuccess: Boolean get() = code == 0 && domain == null && errorName == null

    /** Whether a binding reported that a required native symbol is missing ([SYMBOL_MISSING]). */
    public val isSymbolMissing: Boolean get() = code == SYMBOL_MISSING_CODE && domain == SYMBOL_MISSING_DOMAIN

    override fun equals(other: Any?): Boolean =
        other is RawStatus && other.code == code && other.domain == domain && other.errorName == errorName

    override fun hashCode(): Int = 31 * (31 * code + (domain?.hashCode() ?: 0)) + (errorName?.hashCode() ?: 0)

    override fun toString(): String = "RawStatus(code=$code, domain=$domain, errorName=$errorName)"

    /** The shared statuses. */
    public companion object {
        /** Success: code 0, no domain, no name. */
        public val SUCCESS: RawStatus = RawStatus(0)

        /** The dedicated status a binding reports when a required symbol cannot be resolved (libsecret older than 0.19). */
        public val SYMBOL_MISSING: RawStatus = RawStatus(SYMBOL_MISSING_CODE, SYMBOL_MISSING_DOMAIN)

        private const val SYMBOL_MISSING_CODE: Int = -1_000_001
        private const val SYMBOL_MISSING_DOMAIN: String = "kmptoolkit-binding"
    }
}

/** The status of a libsecret call: [RawStatus.code], the `GError` domain and the D-Bus error name (an alias of [RawStatus]). */
public typealias SecretStatus = RawStatus
