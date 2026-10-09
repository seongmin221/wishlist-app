package app.persistence

import java.sql.Connection
import javax.sql.DataSource

/** One JDBC transaction. Read-only work uses a repeatable-read snapshot so lists and counts agree. */
fun <T> DataSource.inTransaction(readOnly: Boolean = false, block: (Connection) -> T): T = connection.use { connection ->
    if (readOnly) {
        connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        connection.isReadOnly = true
    }
    connection.autoCommit = false
    try {
        block(connection).also { connection.commit() }
    } catch (cause: Throwable) {
        connection.rollback()
        throw cause
    }
}
