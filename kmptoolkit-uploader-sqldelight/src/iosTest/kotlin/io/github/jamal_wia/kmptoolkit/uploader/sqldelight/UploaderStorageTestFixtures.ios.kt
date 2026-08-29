package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import co.touchlab.sqliter.DatabaseFileContext

internal actual fun createDriver(
    schema: SqlSchema<QueryResult.Value<Unit>>,
    name: String?,
): SqlDriver = if (name == null) {
    inMemoryDriver(schema)
} else {
    NativeSqliteDriver(schema = schema, name = name)
}

internal actual fun deleteDatabaseFile(name: String) {
    // Best effort: the simulator's database directory is shared across runs, so leaving files
    // behind would eventually matter even though every check picks a unique name.
    DatabaseFileContext.deleteDatabase(name)
}
