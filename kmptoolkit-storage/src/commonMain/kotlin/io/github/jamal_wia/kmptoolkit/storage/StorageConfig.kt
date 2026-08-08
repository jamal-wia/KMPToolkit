package io.github.jamal_wia.kmptoolkit.storage

/**
 * Which store a factory opens.
 *
 * One field, because one logical name is all four platform identifiers this module needs: a
 * `SharedPreferences` file name, an `NSUserDefaults` suite name, an AndroidKeyStore alias, and a
 * Keychain service. Each is derived from [name] deterministically, so two stores with different
 * names share nothing on either platform, and the same name opens the same store on both.
 *
 * Nothing in this module hardcodes an identifier of its own. A library that did would collide the
 * moment two libraries built on it — or two versions of one — ended up in the same process, and it
 * would leave an app no way to keep two features' state apart. See `docs/01-architecture.md`.
 *
 * ```kotlin
 * // Default: one store per app, named after the app itself.
 * val storage: KeyValueStorage = createKeyValueStorage(context)
 *
 * // Two independent stores in one app — different names, no shared keys.
 * val session: KeyValueStorage = createKeyValueStorage(context, StorageConfig("com.example.session"))
 * val cache: KeyValueStorage = createKeyValueStorage(context, StorageConfig("com.example.cache"))
 * ```
 *
 * @param name the logical store name. `null` — the default — resolves at runtime to the consuming
 *   app's own identifier: `Context.getPackageName()` on Android, `CFBundleIdentifier` on iOS. That
 *   default is what keeps two apps that both use this library from ever seeing each other's data,
 *   without either of them naming anything. Must not be blank when supplied.
 *
 *   The name is an identifier, not a path: it is used verbatim as part of a file name, a Keychain
 *   service, and a Keystore alias, so keep it to the reverse-DNS-ish characters those all accept.
 *   `/`, `\`, a space and a null character are rejected outright: a `SharedPreferences` file name
 *   containing a separator silently writes outside the preferences directory, and the other two
 *   are accepted by one platform's identifier and mangled by another's.
 */
public data class StorageConfig(public val name: String? = null) {
    init {
        // Validated here rather than reported as a StorageError: the name is a value a developer
        // writes as a literal, so a wrong one is a bug to fix at the call site, not a runtime
        // condition an app can recover from.
        require(name == null || name.isNotBlank()) {
            "name must be null or a non-blank identifier, was '$name'"
        }
        require(name == null || name.none { it in FORBIDDEN_CHARACTERS }) {
            "name must not contain a path separator, a space, or a null character, was '$name'"
        }
    }

    private companion object {
        val FORBIDDEN_CHARACTERS: Set<Char> = setOf('/', '\\', ' ', '\u0000')
    }
}

/**
 * Turns a [StorageConfig] into the concrete identifiers each platform backend needs.
 *
 * Internal on purpose: the exact identifier a store lands on is an implementation detail, and
 * promising it in the public API would freeze the layout of every consumer's data directory. It is
 * nonetheless documented in `docs/kmptoolkit-storage/05-platform-notes.md`, because a consumer
 * debugging with `adb shell` or a Keychain dump has to be able to find their own data.
 *
 * The suffixes matter for one non-obvious reason on iOS: `NSUserDefaults(suiteName:)` returns the
 * *standard* defaults when the suite name equals the app's bundle identifier, and `clear()` would
 * then wipe the app's entire standard domain. Because [plainStoreId] always appends a suffix, the
 * resolved suite name can never equal the bundle id, even when a consumer passes it as [name]
 * explicitly.
 */
internal fun plainStoreId(name: String): String = "$name.kmptoolkit.storage"

/** The identifier of the encrypted store — see [plainStoreId]. Never equal to it. */
internal fun secureStoreId(name: String): String = "$name.kmptoolkit.securestorage"

/** The AndroidKeyStore alias holding the encrypted store's key — see [plainStoreId]. */
internal fun secureKeyAlias(name: String): String = "$name.kmptoolkit.securestorage.key"
