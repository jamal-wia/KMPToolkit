package io.github.jamal_wia.kmptoolkit.outbox.sqldelight

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.github.jamal_wia.kmptoolkit.outbox.sqldelight.db.KmpToolkitOutboxDatabase
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
 * val storage: OutboxStorage = createOutboxStorage(this)
 * ```
 *
 * The file is created on first use and migrated forward on an upgrade. Shared code never calls this
 * function: it takes [OutboxStorage.store] and [OutboxStorage.transactionRunner] as parameters, per
 * `docs/01-architecture.md`.
 *
 * Use the `SqlDriver` overload instead if your app already has a SQLite database — putting the
 * queue **in** that database is what lets a domain write and its owed effect commit together. A
 * separate file cannot offer that, and this factory does not pretend otherwise.
 *
 * @param context any `Context`. Only its application context is retained, so passing an Activity
 *   cannot leak it.
 * @param config which file to open. The default names it after the app's own package — see
 *   [OutboxDatabaseConfig].
 * @return a storage that owns its driver and closes it in [OutboxStorage.close].
 * @throws OutboxStorageException if the file cannot be opened, created or migrated.
 */
public fun createOutboxStorage(
    context: Context,
    config: OutboxDatabaseConfig = OutboxDatabaseConfig(),
): OutboxStorage {
    val applicationContext: Context = context.applicationContext
    val name: String = config.name ?: applicationContext.packageName
    val driver: AndroidSqliteDriver = mapFailures(OutboxStorageOperation.OPEN) {
        AndroidSqliteDriver(
            schema = KmpToolkitOutboxDatabase.Schema,
            context = applicationContext,
            name = outboxDatabaseFileName(name),
        ).also(::forceOpen)
    }
    return standaloneStorage(driver)
}

/**
 * One daemon thread, named so it is identifiable in a thread dump or a strict-mode report.
 *
 * Daemon on purpose: a non-daemon thread would keep a JVM alive after the app's work is done if a
 * consumer forgot to call [OutboxStorage.close], which turns a small leak into a hung process.
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
