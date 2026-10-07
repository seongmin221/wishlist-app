package app.wishlist.shared.data.remote

import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.model.DecimalAmount
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Read-only price reader. The server writes `price` as a JSON number literal (DecimalJsonSerializer);
 * the literal text is parsed directly so no double rounding happens. Absent or null is a valid
 * "no price". A JSON string, boolean, container, or a literal [DecimalAmount.parseOrNull] rejects
 * (exponent notation, NaN) is INVALID_RESPONSE. Nothing here serializes a price.
 */
internal fun readPrice(value: JsonElement?): ClientResult<DecimalAmount?> {
    if (value == null || value is JsonNull) return ClientResult.Success(null)
    if (value !is JsonPrimitive || value.isString) return invalidPrice()
    val amount = DecimalAmount.parseOrNull(value.content) ?: return invalidPrice()
    return ClientResult.Success(amount)
}

private fun invalidPrice() = ClientResult.Failure(ClientError(ErrorKind.INVALID_RESPONSE))
