package io.github.jamal_wia.kmptoolkit.storage

/**
 * Who holds an [EncryptedKeyValueStorage]'s key and who is kept out, as a lower bound that is true on
 * every platform that reports it.
 *
 * It is known without any I/O, so shared code can read it before the first call. It is constant for
 * every store except an app-key store, which reports the type of the key it is bound to: a
 * `changeKey` to the other key type (passphrase to raw key, or back) changes it. Each value states the guarantee that always holds; a platform may offer more
 * (the backend kind of a desktop store, for instance, comes from a probe, not from here), but never
 * less, and a lower bound never reads "unknown".
 *
 * The set may grow in a minor version, so give a `when` over it an `else` that refuses — treat an
 * unknown value as the weakest protection, never as the strongest.
 */
public enum class SecureStorageProtection {

    /**
     * An OS key store holds the key; other OS users and other apps of the same user are kept out, and
     * offline copies too, except copies the app opted into: on iOS an accessibility without
     * `THIS_DEVICE_ONLY` lets encrypted device backups carry the items, and `synchronizable` puts them
     * in iCloud Keychain (Android Keystore, iOS Keychain).
     */
    OS_KEY_STORE_APP_ISOLATED,

    /**
     * An OS key store holds the key; offline copies and other OS users are kept out; other programs of
     * the same user may read it, possibly after a prompt (Windows DPAPI, Linux Secret Service, the
     * macOS file keychain).
     */
    OS_KEY_STORE_USER_SCOPED,

    /**
     * The key is derived (PBKDF2-HMAC-SHA256) from a passphrase the app supplies; the store is as
     * strong as the passphrase.
     */
    PASSPHRASE,

    /** The app supplies the raw key; the store is exactly as safe as wherever the app keeps that key. */
    APP_PROVIDED_KEY,

    /** Nothing is encrypted; only test doubles report this. */
    NONE,
}
