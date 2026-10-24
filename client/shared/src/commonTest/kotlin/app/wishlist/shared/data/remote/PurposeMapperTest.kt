package app.wishlist.shared.data.remote

import app.wishlist.shared.core.*
import app.wishlist.shared.data.fake.error
import app.wishlist.shared.data.fake.successValue
import app.wishlist.shared.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

internal const val PURPOSE_ID = "44444444-4444-4444-8444-444444444444"
internal val PURPOSE_DETAIL_JSON = """{"id":"$PURPOSE_ID","name":"출퇴근","description":null,"colorKey":"CORAL","iconKey":"MUSIC","candidateCount":0,"membershipVersion":1,"version":1,"activity":{"at":"2026-10-07T03:00:00Z","kind":"CREATED"},"createdAt":"2026-10-07T03:00:00Z","updatedAt":"2026-10-07T03:00:00Z","allowedActions":["EDIT","DELETE","ADD_CANDIDATES"]}"""
internal fun purposeCatalogJson(details: List<String>) = """{"projection":"SUMMARY","purposes":[${details.joinToString(",") { JsonObject(Json.parseToJsonElement(it).jsonObject + ("previews" to JsonArray(emptyList()))).toString() }}],"nextCursor":null,"activeCount":${details.size},"purposeLimit":30,"archiveSummary":{"count":0,"recentTitles":[]}}"""

class PurposeMapperTest {
    @Test fun everyStyleHasExplicitBidirectionalMappingAndFutureKeysSurvive() {
        val colors = listOf("coral" to "CORAL", "mustard" to "MUSTARD", "periwinkle" to "PERIWINKLE", "cyan" to "CYAN", "mint" to "MINT", "pink" to "PINK")
        val icons = listOf("heart" to "HEART", "home" to "HOME", "plane" to "PLANE", "gift" to "GIFT", "tent" to "TENT", "music" to "MUSIC", "star" to "STAR", "book" to "BOOK")
        for ((uiColor, wireColor) in colors) for ((uiIcon, wireIcon) in icons) {
            val command = PurposeCommand("원문", null, uiColor, uiIcon)
            val body = Json.parseToJsonElement(purposeCreateBody(command)).jsonObject
            assertEquals(wireColor, body["colorKey"]!!.jsonPrimitive.content)
            assertEquals(wireIcon, body["iconKey"]!!.jsonPrimitive.content)
            val detail = parsePurposeDetail(PURPOSE_DETAIL_JSON.replace("CORAL", wireColor).replace("MUSIC", wireIcon)).successValue()
            assertEquals(uiColor, detail.colorKey)
            assertEquals(uiIcon, detail.iconKey)
        }
        val future = parsePurposeDetail(PURPOSE_DETAIL_JSON.replace("CORAL", "FUTURE_COLOR").replace("MUSIC", "FUTURE_ICON")).successValue()
        assertEquals("FUTURE_COLOR", future.colorKey)
        assertEquals("FUTURE_ICON", future.iconKey)
        assertNull(PurposeStyleKeys.colorToWire("CORAL"))
    }

    @Test fun patchOmitsUnchangedAndClearsDescriptionWithExplicitNull() {
        assertEquals("""{"expectedVersion":2,"description":null,"iconKey":"BOOK"}""", purposePatchBody(2, PurposePatch(description = DescriptionChange.Set(null), iconKey = "book")))
        assertEquals("""{"name":"원문","colorKey":"CORAL","iconKey":"HEART"}""", purposeCreateBody(PurposeCommand("원문", null, "coral", "heart")))
    }

    @Test fun strictResponseTypesCountsAndProjection() {
        assertEquals(1, parsePurposeCatalog(purposeCatalogJson(listOf(PURPOSE_DETAIL_JSON))).successValue().activeCount)
        val invalid = listOf(
            PURPOSE_DETAIL_JSON.replace("\"version\":1", "\"version\":\"1\""),
            PURPOSE_DETAIL_JSON.replace("\"candidateCount\":0", "\"candidateCount\":-1"),
            PURPOSE_DETAIL_JSON.replace("\"membershipVersion\":1", "\"membershipVersion\":0"),
            PURPOSE_DETAIL_JSON.replace("2026-10-07T03:00:00Z", "yesterday"),
            PURPOSE_DETAIL_JSON.replace("\"allowedActions\":[\"EDIT\",\"DELETE\",\"ADD_CANDIDATES\"]", "\"allowedActions\":[12]"),
        )
        invalid.forEach { assertEquals(ErrorKind.INVALID_RESPONSE, parsePurposeDetail(it).error().kind, it) }
        assertEquals(ErrorKind.INVALID_RESPONSE, parsePurposeCatalog(purposeCatalogJson(emptyList()).replace("SUMMARY", "SELECT")).error().kind)
        assertEquals(ErrorKind.INVALID_RESPONSE, parsePurposeCatalog(purposeCatalogJson(emptyList()).replace("\"nextCursor\":null", "\"nextCursor\":\"more\"")).error().kind)
    }
}
