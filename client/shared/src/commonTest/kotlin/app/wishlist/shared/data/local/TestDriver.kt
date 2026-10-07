package app.wishlist.shared.data.local

import app.cash.sqldelight.db.SqlDriver

/** Per-runtime file-backed SQLite: JDBC on androidHostTest, Native on iosTest. */
internal expect fun newTestDbPath(): String
internal expect fun openTestDriver(path: String): SqlDriver
internal expect fun deleteTestDb(path: String)
