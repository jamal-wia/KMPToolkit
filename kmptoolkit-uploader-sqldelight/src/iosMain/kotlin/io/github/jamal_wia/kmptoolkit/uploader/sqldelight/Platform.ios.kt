package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.db.KmpToolkitUploaderDatabase
import kotlin.coroutines.CoroutineContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSBundle
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
 * @throws UploaderStorageException if the file cannot be opened, created or migrated.
 */
public fun createUploaderStorage(config: UploaderDatabaseConfig = UploaderDatabaseConfig()): UploaderStorage {
    val name: String = config.name ?: bundleIdentifier()
    val driver: NativeSqliteDriver = mapFailures(UploaderStorageOperation.OPEN) {
        NativeSqliteDriver(
            schema = KmpToolkitUploaderDatabase.Schema,
            name = uploaderDatabaseFileName(name),
        ).also(::forceOpen)
    }
    return standaloneStorage(driver)
}

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
