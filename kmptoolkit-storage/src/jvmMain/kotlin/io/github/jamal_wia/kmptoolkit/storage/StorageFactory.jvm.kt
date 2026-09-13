package io.github.jamal_wia.kmptoolkit.storage

import java.io.File

/**
 * Creates a plain [KeyValueStorage] backed by one properties file in [directory].
 *
 * Desktop has no per-application preferences store the way Android and iOS do, and no application
 * identifier to derive one from, so both halves of the location are yours: [directory] is where the
 * app keeps its own files (typically a folder under the user's home or the OS's application-data
 * directory), and [config]'s name — **required here** — names the file inside it. The file is
 * `<name>.kmptoolkit.storage.properties`; nothing else is written to [directory].
 *
 * ```kotlin
 * val storage: KeyValueStorage = createKeyValueStorage(
 *     directory = File(System.getProperty("user.home"), ".myapp"),
 *     config = StorageConfig("com.example.settings"),
 * )
 * ```
 *
 * Every write replaces the file atomically — a temporary file in the same directory, then an atomic
 * rename — so a process killed mid-write leaves the previous contents, never a truncated file. Two
 * instances over the same file in one process share a lock and see each other's writes. Two
 * *processes* writing the same file are not coordinated; keep one writer per file.
 *
 * There is no `createSecureKeyValueStorage` on desktop: the JVM has no platform key store this
 * module could hold a key in without inventing one. See `docs/kmptoolkit-storage/05-platform-notes.md`.
 *
 * @param directory the directory holding the store's file. Created on the first write if absent.
 * @throws IllegalArgumentException if [config] has no name.
 */
public fun createKeyValueStorage(directory: File, config: StorageConfig): KeyValueStorage {
    val name: String = requireNotNull(config.name) {
        "StorageConfig.name is required on desktop: there is no application identifier to derive it from"
    }
    return JvmKeyValueStorage(File(directory, "${plainStoreId(name)}.properties"))
}
