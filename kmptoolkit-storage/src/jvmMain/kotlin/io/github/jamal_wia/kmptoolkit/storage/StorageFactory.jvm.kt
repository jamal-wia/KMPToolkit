package io.github.jamal_wia.kmptoolkit.storage

import java.io.File
import kotlinx.coroutines.Dispatchers

/**
 * Creates a plain [KeyValueStorage] backed by one properties file in [directory].
 *
 * Desktop has no per-application preferences store the way Android and iOS do, and no application
 * identifier to derive one from, so both halves of the location are yours: [directory] is where the
 * app keeps its own files (typically a folder under the user's home or the OS's application-data
 * directory), and [config]'s name — **required here** — names the file inside it. The file is
 * `<name>.kmptoolkit.storage.properties`. The only other files in [directory] are that file's temporary sibling
 * during a write, which `clear()` removes if a process killed mid-write left it, and its lock file
 * `<name>.kmptoolkit.storage.properties.lock`, an empty file created owner-only by the first write and kept.
 *
 * ```kotlin
 * val storage: KeyValueStorage = createKeyValueStorage(
 *     directory = File(System.getProperty("user.home"), ".myapp"),
 *     config = StorageConfig("com.example.settings"),
 * )
 * ```
 *
 * Every write replaces the file atomically — a temporary file in the same directory, synced to the
 * device, then an atomic rename — so a process killed or a machine losing power mid-write leaves the
 * previous contents, never a truncated file. Two
 * instances over the same file in one process share a lock and see each other's writes. Writes are also
 * serialized across *processes* through `FileChannel.tryLock` on the lock file: a write that cannot get the lock within
 * 10 seconds fails with [StorageError.Unavailable] and changes nothing, so two app instances or two tools never lose each
 * other's updates. The lock is advisory and unreliable on network file systems; keep the store on a local volume.
 *
 * The file, its temporary siblings and the lock file are owner-only (POSIX 0600; on Windows an ACL for the owner and
 * SYSTEM), and a directory this function's writes create is 0700; an existing directory is never changed, and a volume
 * without permissions (FAT, exFAT) ignores the request. Another OS user who could read the file before now cannot, except
 * on macOS, where an extended ACL entry that the parent directory marks inheritable is still inherited by every new file
 * and directory (the JDK cannot clear it): keep the directory under a parent without such entries.
 *
 * This store is plain text: it protects nothing at rest, so do not keep secrets in it. For secrets use
 * [createSecureKeyValueStorage] (the data key in the operating system's key store) or
 * [createAppKeyEncryptedKeyValueStorage] (a key or passphrase your app provides). See
 * `docs/kmptoolkit-storage/05-platform-notes.md` for how each one protects data on desktop.
 *
 * @param directory the directory holding the store's file. Created on the first write if absent.
 * @throws IllegalArgumentException if [config] has no name.
 */
public fun createKeyValueStorage(directory: File, config: StorageConfig): KeyValueStorage {
    val name: String = requireNotNull(config.name) {
        "StorageConfig.name is required on desktop: there is no application identifier to derive it from"
    }
    return JvmKeyValueStorage(File(directory, "${plainStoreId(name)}.properties"), Dispatchers.IO)
}

/**
 * Creates a desktop [SecureKeyValueStorage]: one 256-bit data key kept in the running operating system's key store (the
 * macOS login keychain, Windows DPAPI, or the Linux Secret Service through libsecret), the data in an encrypted file
 * `<name>.kmptoolkit.securestorage.bin` inside [directory] (format v1, rewritten as a whole, atomic, owner-only).
 *
 * - **Never touches the operating system until the first call**, and never falls back to anything weaker: a missing key
 *   store, a locked keychain, a denied prompt, an unusable runtime or a file system without file locks is a typed
 *   [StorageError], never a plain-text write. [SecureKeyValueStorage.protection] is
 *   [SecureStorageProtection.OS_KEY_STORE_USER_SCOPED] and is known without I/O.
 * - **Isolation is per user, not per app.** The key store lets any process of the same user ask for the key (macOS asks the
 *   user once per code identity, Linux only while the collection is unlocked, Windows never asks). Data is encrypted
 *   against other users and against the disk being copied, not against other code the user runs.
 * - **Writes and reads are serialized across processes** with a lock file next to the data file; a call that cannot get
 *   it within 10 seconds answers [StorageError.Unavailable]. A lost key (the OS item was deleted or replaced) is
 *   [StorageError.Undecryptable] and is repaired by `remove` and `clear()`; see the platform notes for the full table.
 * - **Needs a native binding.** JDK 22 or newer uses the FFM binding built into this artifact; JDK 21 needs the optional
 *   `kmptoolkit-storage-jna` artifact; otherwise every call answers [StorageError.Unsupported]. [probeDesktopKeyStore]
 *   tells which key store would serve, without prompting.
 *
 * @param directory where the app keeps its own files; created on the first write if absent, owner-only.
 * @param config the store [StorageConfig.name] is **required** on desktop and names the file and the key-store item.
 * @param options the item label shown by the OS keychain UI and whether a dialog may be shown.
 * @throws IllegalArgumentException if [config] has no name.
 */
public fun createSecureKeyValueStorage(
    directory: File,
    config: StorageConfig,
    options: DesktopSecureStorageOptions = DesktopSecureStorageOptions(),
): SecureKeyValueStorage = JvmSecureKeyValueStorage(requiredName(config), directory, options)

/**
 * Creates a desktop [AppKeyEncryptedKeyValueStorage]: the data key is wrapped by a key your app provides ([key]: a
 * passphrase or a raw 256-bit key), the data lives in `<name>.kmptoolkit.appkeystorage.bin` inside [directory]. No operating
 * system call is involved, so it works on every OS and on JDK 21 without the JNA artifact; the library never chooses a key
 * source for you. See [AppKeyEncryptedKeyValueStorage] for the contract (a wrong key is [StorageError.AccessDenied] on
 * every operation) and [AppEncryptionKey] for how providers are called.
 *
 * @param directory where the app keeps its own files; created on the first write if absent, owner-only.
 * @param config the store [StorageConfig.name] is **required** on desktop.
 * @throws IllegalArgumentException if [config] has no name.
 */
public fun createAppKeyEncryptedKeyValueStorage(
    directory: File,
    config: StorageConfig,
    key: AppEncryptionKey,
): AppKeyEncryptedKeyValueStorage = JvmAppKeyEncryptedKeyValueStorage(requiredName(config), directory, key)

private fun requiredName(config: StorageConfig): String = requireNotNull(config.name) {
    "StorageConfig.name is required on desktop: there is no application identifier to derive it from"
}
