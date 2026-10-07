package app.wishlist.shared.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.time.Instant

fun interface Clock {
    fun now(): Instant
}

fun interface IdGenerator {
    fun newId(): String
}

internal data class RuntimeDispatchers(
    val default: CoroutineDispatcher,
    val io: CoroutineDispatcher,
)
