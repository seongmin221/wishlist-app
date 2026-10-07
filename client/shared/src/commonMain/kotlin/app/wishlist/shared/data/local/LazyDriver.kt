package app.wishlist.shared.data.local

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * Opens the SQL driver on first suspend use, on [io], never on the caller's (often main) thread
 * and never for a mere facade lookup. Opened at most once; a failed open is retried on next use.
 * The driver is published inside the io block, so a caller cancelled while [open] runs (withContext
 * cancels promptly) still leaves the opened driver for the next use instead of opening a second one.
 */
internal class LazyDriver(private val open: () -> SqlDriver, private val io: CoroutineDispatcher) {
    private val mutex = Mutex()

    @Volatile private var driver: SqlDriver? = null

    suspend fun get(): SqlDriver = driver ?: mutex.withLock {
        driver ?: withContext(io) { open().also { driver = it } }
    }
}
