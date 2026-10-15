package app.http

import app.category.CategoryRef
import app.common.FieldChange
import app.wishlist.ItemChanges
import app.wishlist.ReviewDecision
import java.util.UUID
import kotlin.test.*

class WishlistItemMutationRequestParserTest {
    private val media = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val purpose = UUID.fromString("00000000-0000-4000-8000-000000000002")
    private val custom = UUID.fromString("00000000-0000-4000-8000-000000000003")

    private fun patchFields(raw: String) = assertIs<ItemParseResult.Invalid>(parseItemPatchRequest(raw), raw).fields

    @Test fun `patch keeps omitted fields and separates explicit nulls`() {
        val request = assertIs<ItemParseResult.Valid<ItemPatchRequest>>(parseItemPatchRequest(
            """{"expectedVersion":3,"brand":null,"purposeId":null,"mediaId":"$media","categoryId":"C026"}""",
        )).request
        assertEquals(3, request.expectedVersion)
        assertEquals(ItemChanges(
            brand = FieldChange.Set(null), category = FieldChange.Set(CategoryRef.Public("C026")),
            purpose = FieldChange.Set(null), media = FieldChange.Set(media),
        ), request.changes)
        val named = assertIs<ItemParseResult.Valid<ItemPatchRequest>>(parseItemPatchRequest(
            """{"expectedVersion":1,"name":"이름","brand":"브랜드","categoryId":"$custom","purposeId":"$purpose"}""",
        )).request.changes
        assertEquals(FieldChange.Set("이름"), named.name)
        assertEquals(FieldChange.Set("브랜드"), named.brand)
        assertEquals(FieldChange.Set(CategoryRef.Custom(custom)), named.category)
        assertEquals(FieldChange.Set(purpose), named.purpose)
        assertEquals(FieldChange.Keep, named.media)
    }

    @Test fun `patch does not apply text length rules before the stored value is known`() {
        val long = "가".repeat(260) + "\n줄바꿈"
        val request = assertIs<ItemParseResult.Valid<ItemPatchRequest>>(parseItemPatchRequest(
            """{"expectedVersion":1,"name":${ApiJson.encodeToString(kotlinx.serialization.serializer<String>(), long)},"brand":""}""",
        )).request
        assertEquals(FieldChange.Set(long), request.changes.name)
        assertEquals(FieldChange.Set(""), request.changes.brand)
    }

    @Test fun `patch rejects structure type and null violations`() {
        for ((raw, fields) in listOf(
            """{"expectedVersion":1}""" to emptySet(),
            """{"expectedVersion":1,"price":10}""" to emptySet(),
            """{"expectedVersion":1,"currency":"KRW"}""" to emptySet(),
            """{"expectedVersion":1,"sourceUrl":"https://a.example"}""" to emptySet(),
            """{"expectedVersion":1,"merchant":"m"}""" to emptySet(),
            """{"expectedVersion":1,"name":"a","extra":1}""" to emptySet(),
            "[]" to emptySet(),
            "{" to emptySet(),
            """{"name":"a"}""" to setOf("expectedVersion"),
            """{"expectedVersion":0,"name":"a"}""" to setOf("expectedVersion"),
            """{"expectedVersion":"1","name":"a"}""" to setOf("expectedVersion"),
            """{"expectedVersion":1.5,"name":"a"}""" to setOf("expectedVersion"),
            """{"expectedVersion":1,"name":null}""" to setOf("name"),
            """{"expectedVersion":1,"name":5}""" to setOf("name"),
            """{"expectedVersion":1,"brand":7}""" to setOf("brand"),
            """{"expectedVersion":1,"categoryId":null}""" to setOf("categoryId"),
            """{"expectedVersion":1,"categoryId":"not-a-category"}""" to setOf("categoryId"),
            """{"expectedVersion":1,"categoryId":"C999"}""" to setOf("categoryId"),
            """{"expectedVersion":1,"purposeId":"not-uuid"}""" to setOf("purposeId"),
            """{"expectedVersion":1,"mediaId":"${media.toString().uppercase()}x"}""" to setOf("mediaId"),
            """{"expectedVersion":1,"mediaId":3,"purposeId":4}""" to setOf("mediaId", "purposeId"),
        )) assertEquals(fields, patchFields(raw), raw)
    }

    @Test fun `manual completion requires name and category and accepts optional fields`() {
        val request = assertIs<ItemParseResult.Valid<ManualCompletionRequest>>(parseManualCompletionRequest(
            """{"expectedVersion":2,"name":"제품","categoryId":"C026","mediaId":null}""",
        )).request
        assertEquals(2, request.expectedVersion)
        assertEquals(ItemChanges(name = FieldChange.Set("제품"), category = FieldChange.Set(CategoryRef.Public("C026")),
            media = FieldChange.Set(null)), request.changes)
        for ((raw, fields) in listOf(
            """{"expectedVersion":2,"categoryId":"C026"}""" to setOf("name"),
            """{"expectedVersion":2,"name":"제품"}""" to setOf("categoryId"),
            """{"expectedVersion":2,"name":null,"categoryId":null}""" to setOf("categoryId", "name"),
            """{"name":"제품","categoryId":"C026"}""" to setOf("expectedVersion"),
            """{"expectedVersion":2,"name":"제품","categoryId":"C026","decision":"CONFIRM"}""" to emptySet(),
        )) assertEquals(fields, assertIs<ItemParseResult.Invalid>(parseManualCompletionRequest(raw), raw).fields, raw)
    }

    @Test fun `review decision accepts only category and purpose changes`() {
        val request = assertIs<ItemParseResult.Valid<ReviewDecisionRequest>>(parseReviewDecisionRequest(
            """{"expectedVersion":4,"decision":"DEFER","purposeId":null}""",
        )).request
        assertEquals(ReviewDecision.DEFER, request.decision)
        assertEquals(ItemChanges(purpose = FieldChange.Set(null)), request.changes)
        assertEquals(ReviewDecision.CONFIRM, assertIs<ItemParseResult.Valid<ReviewDecisionRequest>>(
            parseReviewDecisionRequest("""{"expectedVersion":4,"decision":"CONFIRM"}""")).request.decision)
        for ((raw, fields) in listOf(
            """{"expectedVersion":4}""" to setOf("decision"),
            """{"expectedVersion":4,"decision":"confirm"}""" to setOf("decision"),
            """{"expectedVersion":4,"decision":null}""" to setOf("decision"),
            """{"expectedVersion":4,"decision":"CONFIRM","categoryId":null}""" to setOf("categoryId"),
            """{"expectedVersion":4,"decision":"CONFIRM","name":"a"}""" to emptySet(),
            """{"expectedVersion":4,"decision":"CONFIRM","mediaId":null}""" to emptySet(),
            """{"decision":"CONFIRM"}""" to setOf("expectedVersion"),
        )) assertEquals(fields, assertIs<ItemParseResult.Invalid>(parseReviewDecisionRequest(raw), raw).fields, raw)
    }

    @Test fun `reanalysis body is empty or an empty object`() {
        assertTrue(isValidReanalysisBody(""))
        assertTrue(isValidReanalysisBody("  "))
        assertTrue(isValidReanalysisBody("{}"))
        assertFalse(isValidReanalysisBody("""{"force":true}"""))
        assertFalse(isValidReanalysisBody("[]"))
        assertFalse(isValidReanalysisBody("{"))
    }
}
