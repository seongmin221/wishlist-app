package app.wishlist.shared.data.local

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private fun dirOf(path: String) = path.substringBeforeLast('/')
private fun nameOf(path: String) = path.substringAfterLast('/')

@OptIn(ExperimentalUuidApi::class)
internal actual fun newTestDbPath(): String = NSTemporaryDirectory().trimEnd('/') + "/wishlist-${Uuid.random()}.db"

internal actual fun openTestDriver(path: String): SqlDriver =
    DriverFactory(name = nameOf(path), basePath = dirOf(path)).create()

internal actual fun openRawDriver(path: String): SqlDriver = NativeSqliteDriver(
    DatabaseConfiguration(
        name = nameOf(path),
        version = 1,
        create = {},
        upgrade = { _, _, _ -> },
        extendedConfig = DatabaseConfiguration.Extended(basePath = dirOf(path)),
    ),
)

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal actual fun deleteTestDb(path: String) {
    listOf("", "-journal", "-wal", "-shm").forEach { NSFileManager.defaultManager.removeItemAtPath(path + it, null) }
}
