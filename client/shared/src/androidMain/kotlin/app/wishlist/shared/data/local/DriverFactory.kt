package app.wishlist.shared.data.local

import android.content.Context
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * Android app-sandbox driver. AndroidSqliteDriver opens (and creates/migrates) the file lazily on
 * its first statement, so [create] runs one read to do that here, on the caller's thread — the
 * runtime calls it from [LazyDriver] on the io dispatcher. Behavior is verified on JDBC/Native.
 */
internal class DriverFactory(private val context: Context, private val name: String = "wishlist.db") {
    fun create(): SqlDriver = AndroidSqliteDriver(WishlistDatabase.Schema, context, name).also { driver ->
        driver.executeQuery(null, "PRAGMA user_version", { QueryResult.Value(Unit) }, 0)
    }
}
