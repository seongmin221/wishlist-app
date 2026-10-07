package app.wishlist.shared.data.local

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/** Android app-sandbox driver. Behavior is verified on JDBC/Native; device smoke is handed to C3. */
internal class DriverFactory(private val context: Context, private val name: String = "wishlist.db") {
    fun create(): SqlDriver = AndroidSqliteDriver(WishlistDatabase.Schema, context, name)
}
