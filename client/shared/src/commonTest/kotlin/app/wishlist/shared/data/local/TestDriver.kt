package app.wishlist.shared.data.local

import app.cash.sqldelight.db.SqlDriver

/** Per-runtime file-backed SQLite: JDBC on androidHostTest, Native on iosTest. */
internal expect fun newTestDbPath(): String

/** Opens [path] with [WishlistDatabase.Schema]: creates a new file, migrates an older user_version. */
internal expect fun openTestDriver(path: String): SqlDriver

/** Opens [path] without any schema (Native: user_version 1 with empty create/upgrade) for migration fixtures. */
internal expect fun openRawDriver(path: String): SqlDriver
internal expect fun deleteTestDb(path: String)
