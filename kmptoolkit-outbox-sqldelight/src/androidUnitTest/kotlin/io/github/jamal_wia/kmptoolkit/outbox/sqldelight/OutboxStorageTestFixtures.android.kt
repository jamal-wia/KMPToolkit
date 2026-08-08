package io.github.jamal_wia.kmptoolkit.outbox.sqldelight

import android.content.Context
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import org.robolectric.RuntimeEnvironment

/**
 * A `null` database name is `AndroidSqliteDriver`'s in-memory mode. The Robolectric application is
 * the only `Context` available here, which is why every test class in this source set carries
 * `@RunWith(AndroidJUnit4::class)`: without the runner there is no application and no SQLite at all.
 */
internal actual fun createDriver(
    schema: SqlSchema<QueryResult.Value<Unit>>,
    name: String?,
): SqlDriver = AndroidSqliteDriver(schema = schema, context = application(), name = name)

internal actual fun deleteDatabaseFile(name: String) {
    application().deleteDatabase(name)
}

internal fun application(): Context = RuntimeEnvironment.getApplication()
