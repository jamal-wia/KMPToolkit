package io.github.jamal_wia.kmptoolkit.storage

/**
 * The operating-system key store serving desktop secure stores, as [probeDesktopKeyStore] reports it. Each entry's
 * documentation states who else can read the key. May grow in a minor version: a `when` over it needs an `else` that fails
 * closed.
 */
public enum class DesktopKeyStore {
    /**
     * The macOS login (file) keychain. Other code identities of the same user get an access prompt that the user may
     * allow; every `java -jar` tool on the same JDK shares one code identity.
     */
    MACOS_FILE_KEYCHAIN,

    /** Windows DPAPI for the current user: any process of the same user can unwrap the key without a prompt. */
    WINDOWS_DPAPI,

    /**
     * The freedesktop Secret Service (gnome-keyring, KWallet, KeePassXC): any process of the same user can read the key
     * while the collection is unlocked.
     */
    LINUX_SECRET_SERVICE,

    /**
     * libsecret's Flatpak Secret-portal backend, reported only when the probe saw libsecret actually use it: secrets are
     * kept per application inside the sandbox.
     */
    LINUX_SECRET_PORTAL,
}
