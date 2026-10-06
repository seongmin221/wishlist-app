package app.http

import java.math.BigDecimal
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/** Keeps decimal prices exact while retaining the JSON number contract. */
@OptIn(ExperimentalSerializationApi::class)
object DecimalJsonSerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("Decimal", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("Decimal requires JSON")
        json.encodeJsonElement(JsonUnquotedLiteral(value.toPlainString()))
    }

    override fun deserialize(decoder: Decoder): BigDecimal {
        val json = decoder as? JsonDecoder ?: throw SerializationException("Decimal requires JSON")
        val value = json.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("Price must be a JSON number")
        if (value.isString) throw SerializationException("Price must be a JSON number")
        return try {
            BigDecimal(value.content)
        } catch (cause: NumberFormatException) {
            throw SerializationException("Price must be a JSON number", cause)
        }
    }
}
