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
 * This store is plain text: it protects nothing at rest, so do not keep secrets in it. See
 * `docs/kmptoolkit-storage/05-platform-notes.md` for how secrets are stored on desktop.
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
