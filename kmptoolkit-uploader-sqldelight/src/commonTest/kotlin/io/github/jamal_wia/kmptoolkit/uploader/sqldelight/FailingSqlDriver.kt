package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * A driver where every statement fails with [failure].
 *
 * The only way to check the error-mapping layer honestly: a full disk, a corrupt file or a
 * revoked-permission directory cannot be produced from a unit test, and the guarantee under test —
 * *no platform exception crosses the SPI* — is exactly about what happens when one does.
 *
 * @param failure thrown by every statement. A [kotlin.coroutines.cancellation.CancellationException]
 *   here is how the "cancellation is not a database fault" rule gets checked.
 */
internal class FailingSqlDriver(private val failure: Throwable) : SqlDriver {

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = throw failure

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> = throw failure

    override fun newTransaction(): QueryResult<Transacter.Transaction> = throw failure

    override fun currentTransaction(): Transacter.Transaction? = null

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun notifyListeners(vararg queryKeys: String) = Unit

    override fun close() = Unit
}

/** A platform-shaped failure: not one of ours, and not something a `catch` upstream would expect. */
internal class FakeSqliteException : RuntimeException("disk I/O error")
