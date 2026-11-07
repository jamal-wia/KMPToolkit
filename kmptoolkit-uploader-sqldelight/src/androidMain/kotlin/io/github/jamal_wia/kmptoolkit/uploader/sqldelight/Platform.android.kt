package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.db.KmpToolkitUploaderDatabase
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking

/**
 * Opens the queue on its own database file, in the app's private `databases/` directory.
 *
 * ```kotlin
 * // Application.onCreate — the queue outlives every Activity, so nothing shorter-lived owns it.
 * val storage: UploaderStorage = createUploaderStorage(this)
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
 * @param context any `Context`. Only its application context is retained, so passing an Activity
 *   cannot leak it.
 * @param config which file to open. The default names it after the app's own package — see
 *   [UploaderDatabaseConfig].
 * @return a storage that owns its driver and closes it in [UploaderStorage.close].
 * A file that is not a database (corruption, a truncated write) is by default **never deleted or
 * recreated**: the open fails with [UploaderStorageException] and the file stays exactly as found.
 * Android's own behavior would silently delete it and start an empty queue, dropping every effect
 * still owed. The app decides — typically by reporting it, then `Context.deleteDatabase` with the file
 * name `"<name>.kmptoolkit.uploader.db"` (`<name>` being [UploaderDatabaseConfig.name], or the package
 * name by default) to start afresh — or opts in to the old behavior with
 * [UploaderDatabaseConfig.recreateOnCorruption], which deletes the file and opens an empty queue.
 *
 * @throws UploaderStorageException if the file cannot be opened, created or migrated.
 */
public fun createUploaderStorage(
    context: Context,
    config: UploaderDatabaseConfig = UploaderDatabaseConfig(),
): UploaderStorage {
    val applicationContext: Context = context.applicationContext
    val name: String = config.name ?: applicationContext.packageName
    val driver: AndroidSqliteDriver = mapFailures(UploaderStorageOperation.OPEN) {
        openOwned(
            AndroidSqliteDriver(
                schema = KmpToolkitUploaderDatabase.Schema,
                context = applicationContext,
                name = uploaderDatabaseFileName(name),
                callback = if (config.recreateOnCorruption) {
                    // The framework's own handling: it deletes the corrupt file and opens a new one.
                    AndroidSqliteDriver.Callback(KmpToolkitUploaderDatabase.Schema)
                } else {
                    NonDestructiveCallback()
                },
            ),
        )
    }
    return standaloneStorage(driver)
}

/**
 * The schema callback with Android's destructive corruption handling removed.
 *
 * The framework's default (`DefaultDatabaseErrorHandler`) deletes a database file it finds corrupt and
 * lets the open continue on a fresh empty one, so a damaged file silently dropped every queued effect.
 * Throwing from [onCorruption] instead fails the open, which [mapFailures] reports as
 * [UploaderStorageException], and leaves the file for the app to deal with. It is a
 * `SQLiteException`, so the framework closes the half-opened database before it propagates.
 */
private class NonDestructiveCallback : AndroidSqliteDriver.Callback(KmpToolkitUploaderDatabase.Schema) {
    override fun onCorruption(db: SupportSQLiteDatabase) {
        throw SQLiteDatabaseCorruptException(
            "The uploader database file is corrupt; it was left untouched (path: ${db.path}).",
        )
    }
}

/**
 * One daemon thread, named so it is identifiable in a thread dump or a strict-mode report.
 *
 * Daemon on purpose: a non-daemon thread would keep a JVM alive after the app's work is done if a
 * consumer forgot to call [UploaderStorage.close], which turns a small leak into a hung process.
 */
internal actual fun createConfinedDatabaseDispatcher(name: String): CloseableCoroutineDispatcher {
    val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }.asCoroutineDispatcher()
    return dispatcher
}

internal actual fun currentThreadId(): Long = Thread.currentThread().id

internal actual fun <R> runBlockingOnCurrentThread(
    context: CoroutineContext,
    block: suspend () -> R,
): R = runBlocking(context) { block() }
