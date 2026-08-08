package io.github.jamal_wia.kmptoolkit.outbox.sqldelight

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.github.jamal_wia.kmptoolkit.outbox.sqldelight.db.KmpToolkitOutboxDatabase
import org.robolectric.RuntimeEnvironment

/**
 * A `null` database name is `AndroidSqliteDriver`'s in-memory mode. The Robolectric application is
 * the only `Context` available here, which is why every test class in this source set carries
 * `@RunWith(AndroidJUnit4::class)`: without the runner there is no application and no SQLite at all.
 */
internal actual fun createInMemoryOutboxStorage(): OutboxStorage = standaloneStorage(
    AndroidSqliteDriver(
        schema = KmpToolkitOutboxDatabase.Schema,
        context = application(),
        name = null,
    ),
)

internal actual fun createFileOutboxStorage(name: String): OutboxStorage = standaloneStorage(
    AndroidSqliteDriver(
        schema = KmpToolkitOutboxDatabase.Schema,
        context = application(),
        name = name,
    ),
)

internal actual fun deleteFileOutboxStorage(name: String) {
    application().deleteDatabase(name)
}

private fun application(): Context = RuntimeEnvironment.getApplication()
