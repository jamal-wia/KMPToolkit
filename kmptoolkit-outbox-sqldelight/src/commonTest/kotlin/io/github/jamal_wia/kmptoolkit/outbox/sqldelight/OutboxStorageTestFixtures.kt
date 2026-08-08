package io.github.jamal_wia.kmptoolkit.outbox.sqldelight

/**
 * A storage over a database that exists only for the duration of the test.
 *
 * In-memory on both platforms, so every call gets a genuinely empty queue without a file to clean
 * up — which is what [OutboxStoreContract][io.github.jamal_wia.kmptoolkit.outbox.testing.OutboxStoreContract]
 * requires of its factory.
 */
internal expect fun createInMemoryOutboxStorage(): OutboxStorage

/**
 * A storage over a real file, so a test can close it and open it again.
 *
 * The whole point of this module is that a queue survives the process that wrote it, and an
 * in-memory database cannot demonstrate that — reopening one gives you a different, empty database
 * and the test would pass for a store that persisted nothing.
 *
 * @param name a name unique to the test using it; two tests sharing one would see each other's rows.
 */
internal expect fun createFileOutboxStorage(name: String): OutboxStorage

/** Removes the file [createFileOutboxStorage] opened, if the platform can. */
internal expect fun deleteFileOutboxStorage(name: String)

/**
 * A file name no other run of this test will pick.
 *
 * Not a fixed name: a leftover file from a failed run would otherwise make the *next* run start
 * with rows it did not write, and the failure would look like a store bug.
 */
internal fun uniqueDatabaseName(prefix: String): String =
    "$prefix-${kotlin.random.Random.nextLong(from = 0L, until = Long.MAX_VALUE)}.db"
