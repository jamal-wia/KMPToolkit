package io.github.jamal_wia.kmptoolkit.storage

/**
 * Options of a desktop secure store ([createSecureKeyValueStorage] on desktop) for every operating system in one value;
 * only the running OS's part applies. Validated at construction: a wrong value is a bug at the call site, so it throws
 * [IllegalArgumentException] here and never arrives later as a [StorageError]. A plain class, not a data class, so a field
 * can be added in a minor version.
 *
 * @param itemLabel the label the OS keychain UI shows for the key item (Keychain Access, Seahorse). `null` uses the store's
 *   identifier. Ignored on Windows, where there is no OS item. Must not be blank.
 * @param allowUserInteraction `false`: a call that would need an OS dialog (a keychain unlock, an access prompt) answers
 *   [StorageError.Unavailable] at once instead of showing it. For CLI tools, ssh sessions and services. `true` (the default)
 *   lets the OS ask the user.
 */
public class DesktopSecureStorageOptions(
    public val itemLabel: String? = null,
    public val allowUserInteraction: Boolean = true,
) {
    init {
        require(itemLabel == null || itemLabel.isNotBlank()) { "itemLabel must be null or not blank" }
        require(itemLabel == null || itemLabel.none { it == '\u0000' }) { "itemLabel must not contain a null character" }
    }

    override fun toString(): String = "DesktopSecureStorageOptions(itemLabel=$itemLabel, allowUserInteraction=$allowUserInteraction)"
}
