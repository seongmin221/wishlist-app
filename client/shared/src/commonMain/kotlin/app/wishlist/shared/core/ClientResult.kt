package app.wishlist.shared.core

sealed interface ClientResult<out T> {
    data class Success<T>(val value: T) : ClientResult<T>
    data class Failure(val error: ClientError) : ClientResult<Nothing>
}
