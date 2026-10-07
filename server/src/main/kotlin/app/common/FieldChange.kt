package app.common

/** PATCH field intent: omitted keeps the stored value; Set carries the new value, including an explicit null. */
sealed interface FieldChange<out T> {
    data object Keep : FieldChange<Nothing>
    data class Set<T>(val value: T) : FieldChange<T>
}
