package app.extraction

import java.math.BigDecimal
import java.util.Currency
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.nodes.Document

data class ParsedProduct(
    val title: String?, val description: String?, val imageUrl: String?, val brand: String?,
    val price: BigDecimal?, val currency: String?, val merchant: String?, val declaredCanonical: String?,
)

/** Reads structured product facts only; no heuristics or AI guesses for brand, price or merchant. */
object ProductMetadataParser {
    private const val MAX_LABEL_LENGTH = 200
    private val AMOUNT = Regex("""^\d{1,15}(\.\d{1,4})?$""")
    private val CURRENCIES = Currency.getAvailableCurrencies().map { it.currencyCode }.toSet()

    fun parse(document: Document, truncated: Boolean): ParsedProduct {
        val products = document.select("script[type=application/ld+json]")
            .flatMap { script -> runCatching { collectProducts(Json.parseToJsonElement(script.data())) }.getOrDefault(emptyList()) }
        val product = products.firstOrNull()
        val ambiguous = products.mapNotNull { text(it["name"]) }.distinct().size > 1
        val title = text(product?.get("name")) ?: meta(document, "og:title")
            ?: if (truncated) null else document.title().trim().takeIf { it.isNotEmpty() }
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() }
        val image = document.selectFirst("meta[property=og:image]")?.attr("abs:content")?.takeIf { it.isNotBlank() }
        val offers = product?.get("offers")?.let(::offerObjects).orEmpty()
        val structuredPrice = if (product != null && product["offers"] != null) singlePrice(offers) else null
        val price = if (ambiguous) null else if (product?.get("offers") != null) structuredPrice
            else validPrice(meta(document, "product:price:amount"), meta(document, "product:price:currency"))
        val brand = if (ambiguous) null else label(brandName(product?.get("brand")) ?: meta(document, "product:brand"))
        val seller = offers.firstNotNullOfOrNull { offer -> brandName(offer["seller"]) }
        return ParsedProduct(
            title = title, description = description, imageUrl = image, brand = brand,
            price = price?.first, currency = price?.second,
            merchant = label(seller ?: meta(document, "og:site_name")),
            declaredCanonical = document.selectFirst("link[rel=canonical]")?.attr("href")?.takeIf { it.isNotBlank() }
                ?: meta(document, "og:url"),
        )
    }

    private fun collectProducts(element: JsonElement): List<JsonObject> = when (element) {
        is JsonArray -> element.flatMap(::collectProducts)
        is JsonObject -> if (types(element).any { it == "product" }) listOf(element) else element["@graph"]?.let(::collectProducts).orEmpty()
        else -> emptyList()
    }

    private fun types(element: JsonObject): List<String> = when (val type = element["@type"]) {
        is JsonPrimitive -> listOf(type.content.lowercase())
        is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.content?.lowercase() }
        else -> emptyList()
    }

    private fun offerObjects(element: JsonElement): List<JsonObject> = when (element) {
        is JsonObject -> listOf(element)
        is JsonArray -> element.filterIsInstance<JsonObject>()
        else -> emptyList()
    }

    /** A price exists only when every offer is a plain Offer with the same valid amount and currency. */
    private fun singlePrice(offers: List<JsonObject>): Pair<BigDecimal, String>? {
        if (offers.isEmpty() || offers.any { "aggregateoffer" in types(it) }) return null
        val prices = offers.map { validPrice(text(it["price"]), text(it["priceCurrency"])) ?: return null }
        return prices.distinctBy { it.first.stripTrailingZeros() to it.second }.singleOrNull()
    }

    private fun validPrice(amount: String?, currency: String?): Pair<BigDecimal, String>? {
        val code = currency?.trim()?.uppercase()?.takeIf { it in CURRENCIES } ?: return null
        val value = amount?.trim()?.takeIf { AMOUNT.matches(it) } ?: return null
        return BigDecimal(value) to code
    }

    private fun brandName(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> text(element)
        is JsonObject -> text(element["name"])
        is JsonArray -> element.firstNotNullOfOrNull(::brandName)
        else -> null
    }

    private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun meta(document: Document, property: String): String? =
        document.selectFirst("meta[property=$property]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

    private fun label(value: String?): String? = value?.takeIf { it.length <= MAX_LABEL_LENGTH }
}
