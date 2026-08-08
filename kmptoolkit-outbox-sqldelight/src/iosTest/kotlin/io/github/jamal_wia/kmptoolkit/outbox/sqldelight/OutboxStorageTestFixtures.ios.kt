package io.github.jamal_wia.kmptoolkit.outbox.sqldelight

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import co.touchlab.sqliter.DatabaseFileContext
import io.github.jamal_wia.kmptoolkit.outbox.sqldelight.db.KmpToolkitOutboxDatabase

internal actual fun createInMemoryOutboxStorage(): OutboxStorage =
    standaloneStorage(inMemoryDriver(KmpToolkitOutboxDatabase.Schema))

internal actual fun createFileOutboxStorage(name: String): OutboxStorage = standaloneStorage(
    NativeSqliteDriver(schema = KmpToolkitOutboxDatabase.Schema, name = name),
)

internal actual fun deleteFileOutboxStorage(name: String) {
    // Best effort: the simulator's database directory is shared across runs, so leaving files
    // behind would eventually matter even though every test picks a unique name.
    DatabaseFileContext.deleteDatabase(name)
}
