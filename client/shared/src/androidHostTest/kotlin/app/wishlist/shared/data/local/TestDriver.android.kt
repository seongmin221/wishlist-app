package app.wishlist.shared.data.local

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

internal actual fun newTestDbPath(): String =
    File.createTempFile("wishlist-local", ".db").also { it.deleteOnExit() }.absolutePath

internal actual fun openTestDriver(path: String): SqlDriver {
    val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
    val version = driver.executeQuery(null, "PRAGMA user_version", { cursor ->
        cursor.next()
        QueryResult.Value(cursor.getLong(0) ?: 0L)
    }, 0).value
    if (version == 0L) {
        WishlistDatabase.Schema.create(driver).value
        driver.execute(null, "PRAGMA user_version = ${WishlistDatabase.Schema.version}", 0)
    }
    return driver
}

internal actual fun deleteTestDb(path: String) {
    listOf("", "-journal", "-wal", "-shm").forEach { File(path + it).delete() }
}
