package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseFileContext
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.db.KmpToolkitUploaderDatabase
import kotlin.coroutines.CoroutineContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.posix.pthread_self

/**
 * Opens the queue on its own database file, under the app's Application Support directory.
 *
 * ```kotlin
 * // Once per process, from your app delegate or a shared component holder.
 * let storage = UploaderStorageKt.createUploaderStorage(config: UploaderDatabaseConfig(name: nil))
 * ```
 *
 * The file is created on first use and migrated forward on an upgrade. Shared code never calls this
 * function: it takes [UploaderStorage.store] and [UploaderStorage.transactionRunner] as parameters, per
 * `docs/01-architecture.md`.
 *
 * Use the `SqlDriver` overload instead if your app already has a SQLite database — putting the
 * queue **in** that database is what lets a domain write and its owed effect commit together. A
 * separate file cannot offer that, and this factory does not pretend otherwise.
 *
 * @param config which file to open. The default names it after the app's own `CFBundleIdentifier` —
 *   see [UploaderDatabaseConfig].
 * @return a storage that owns its driver and closes it in [UploaderStorage.close].
 * A file that is not a database (corruption, a truncated write) is by default **never deleted or
 * recreated**: the open fails with [UploaderStorageException] and the file stays exactly as found,
 * the same contract as Android. With [UploaderDatabaseConfig.recreateOnCorruption] the file is deleted
 * and an empty queue is opened instead.
 *
 * @throws UploaderStorageException if the file cannot be opened, created or migrated.
 */
public fun createUploaderStorage(config: UploaderDatabaseConfig = UploaderDatabaseConfig()): UploaderStorage {
    val name: String = config.name ?: bundleIdentifier()
    val fileName: String = uploaderDatabaseFileName(name)
    val driver: NativeSqliteDriver = mapFailures(UploaderStorageOperation.OPEN) {
        try {
            if (config.recreateOnCorruption) openVerified(fileName) else openNative(fileName)
        } catch (failure: Throwable) {
            if (!config.recreateOnCorruption || !looksCorrupt(failure)) throw failure
            deleteDatabaseFiles(fileName)
            openNative(fileName)
        }
    }
    return standaloneStorage(driver)
}

private fun openNative(fileName: String): NativeSqliteDriver = openOwned(
    NativeSqliteDriver(schema = KmpToolkitUploaderDatabase.Schema, name = fileName),
)

/**
 * [openNative] followed by an integrity probe, for [UploaderDatabaseConfig.recreateOnCorruption].
 *
 * Opening a native driver reads only the file header, so a file damaged below a valid header opens fine and
 * fails on the first read that touches a damaged page — on every launch, forever. Android heals that case
 * through the framework's corruption handler; here the probe (`PRAGMA quick_check`, which walks every page
 * but does not compare index contents) surfaces it at the open, where the recreate decision is made. The
 * queue is small by nature, and the probe runs only for an app that opted in to losing a damaged queue.
 */
private fun openVerified(fileName: String): NativeSqliteDriver {
    val driver: NativeSqliteDriver = openNative(fileName)
    try {
        val verdict: String? = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA quick_check(1)",
            mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
            parameters = 0,
        ).value
        // A verdict other than "ok" is SQLite's own description of the damage; the marker routes it to recreate.
        check(verdict == "ok") { "database disk image is malformed: quick_check reported $verdict" }
    } catch (failure: Throwable) {
        try {
            driver.close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
        throw failure
    }
    return driver
}

/**
 * Deletes the database file and the `-wal`, `-shm` and `-journal` files next to it. Removing the main file
 * alone would leave a dead write-ahead log that SQLite replays into the new file, bringing the old queue
 * back into a database that was meant to start empty.
 */
@OptIn(ExperimentalForeignApi::class)
private fun deleteDatabaseFiles(fileName: String) {
    DatabaseFileContext.deleteDatabase(fileName)
    val path: String = DatabaseFileContext.databasePath(fileName, null)
    SIDECAR_SUFFIXES.forEach { suffix -> NSFileManager.defaultManager.removeItemAtPath(path + suffix, null) }
}

private val SIDECAR_SUFFIXES: List<String> = listOf("-wal", "-shm", "-journal")

/**
 * Whether SQLite reported that the file is not a database or is malformed (`SQLITE_NOTADB`,
 * `SQLITE_CORRUPT`), judged by the messages along the cause chain. Anything else — a migration that
 * threw, a locked file, a full disk — must not cost the app its queue.
 */
private fun looksCorrupt(failure: Throwable): Boolean = generateSequence(failure) { it.cause }.any { error ->
    val message: String = error.message.orEmpty().lowercase()
    CORRUPTION_MARKERS.any { it in message }
}

private val CORRUPTION_MARKERS: List<String> =
    listOf("not a database", "notadb", "malformed", "sqlite_corrupt", "database disk image")

/**
 * One worker thread with its own run loop.
 *
 * `newSingleThreadContext` is `@DelicateCoroutinesApi` because the thread it starts has to be closed
 * by hand — which is exactly what [UploaderStorage.close] does, and why it is the right primitive
 * here rather than a hazard.
 */
@OptIn(DelicateCoroutinesApi::class)
internal actual fun createConfinedDatabaseDispatcher(name: String): CloseableCoroutineDispatcher =
    newSingleThreadContext(name)

/**
 * `pthread_self` rather than anything from Foundation: the database thread is a plain worker with
 * no `NSThread` identity of its own, and this value is only ever compared against another reading
 * of itself.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun currentThreadId(): Long = pthread_self()?.rawValue?.toLong() ?: NO_THREAD_ID

/**
 * What [currentThreadId] returns if `pthread_self` ever handed back nothing.
 *
 * It cannot on a running thread, but a constant that equals no real thread id is a better answer
 * than `!!`: the value is only ever compared for equality, and a comparison that says "not the
 * database thread" fails loudly, whereas a crash here would come from the check rather than from
 * the thing it checks.
 */
private const val NO_THREAD_ID = -1L

internal actual fun <R> runBlockingOnCurrentThread(
    context: CoroutineContext,
    block: suspend () -> R,
): R = runBlocking(context) { block() }

/**
 * The app's `CFBundleIdentifier`, or this module's namespace when there is none.
 *
 * A bundle without an identifier is not a real app — it happens in a test binary and in a bare
 * framework loaded by a host that has one of its own. Falling back keeps the queue openable there
 * instead of failing a test run at startup, and the name it falls back to is still this module's,
 * so it cannot collide with anything a consumer would choose.
 */
private fun bundleIdentifier(): String =
    NSBundle.mainBundle.bundleIdentifier ?: FALLBACK_BUNDLE_IDENTIFIER

private const val FALLBACK_BUNDLE_IDENTIFIER = "io.github.jamal_wia.kmptoolkit.uploader.unbundled"
