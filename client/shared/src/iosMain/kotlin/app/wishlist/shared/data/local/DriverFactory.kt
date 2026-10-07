package app.wishlist.shared.data.local

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

/** iOS app-sandbox driver; [basePath] null uses the platform default database directory. */
internal class DriverFactory(private val name: String = "wishlist.db", private val basePath: String? = null) {
    fun create(): SqlDriver = NativeSqliteDriver(
        WishlistDatabase.Schema, name,
        onConfiguration = { config ->
            if (basePath == null) config
            else config.copy(extendedConfig = config.extendedConfig.copy(basePath = basePath))
        },
    )
}
