package app.wishlist.shared.data.remote

import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.data.fake.error
import app.wishlist.shared.data.fake.successValue
import app.wishlist.shared.model.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

/*
 * Fixture baseline: hand-written from server develop 1c6d949 (WishlistItemDtos.kt,
 * WishlistItemViewMapper.kt, DecimalJsonSerializer.kt, CategoryDtos.kt, AnalysisFailureCode.kt;
 * last changed in fd55d1c). Never generated from FakeStore or evaluateItem.
 */
internal const val BASE_ITEM_JSON = """{
 "id":"00000000-0000-4000-8000-000000000101",
 "clientSubmissionId":"00000000-0000-4000-8000-000000000003",
 "version":2,
 "sourceUrl":"https://shop.example/headphone",
 "product":{"name":"Headphones","imageUrl":"https://img.example/h.png","price":129000.50,"currency":"KRW",
   "brand":"Acme","merchant":"shop.example","metadataCheckedAt":"2026-10-07T00:00:05Z","nameSource":"AI","imageSource":"AI"},
 "category":{"id":"C026","source":"AI","missingReason":null,"name":"헤드폰","parentId":"C020","kind":"PUBLIC"},
 "purpose":{"id":null,"source":"UNASSIGNED"},
 "analysis":{"status":"READY","failureCode":null},
 "reviewStatus":"PENDING","lifecycleStatus":"ACTIVE","requiredAction":"CLASSIFICATION_REVIEW",
 "createdAt":"2026-10-07T00:00:00Z","updatedAt":"2026-10-07T00:00:05Z","manualCompletionAt":null,
 "allowedActions":["EDIT","DELETE","REVIEW"],"clientCreatedAt":"2026-10-06T23:59:00.123Z"}"""

/** Replaces (raw JSON fragment) or removes (null) a dotted path of [BASE_ITEM_JSON]. */
internal fun itemJson(vararg edits: Pair<String, String?>, base: String = BASE_ITEM_JSON): String {
    fun edit(obj: JsonObject, path: List<String>, raw: String?): JsonObject {
        val head = path.first()
        val updated = when {
            path.size > 1 -> edit(obj.getValue(head) as JsonObject, path.drop(1), raw)
            raw == null -> null
            else -> Json.parseToJsonElement(raw)
        }
        return JsonObject(obj.toMutableMap().also { if (updated == null) it.remove(head) else it[head] = updated })
    }
    var root = Json.parseToJsonElement(base) as JsonObject
    edits.forEach { (path, raw) -> root = edit(root, path.split('.'), raw) }
    return root.toString()
}

private fun mapped(vararg edits: Pair<String, String?>) = parseItem(itemJson(*edits))
private fun dtoFixture(
    reviewStatus: String = "CONFIRMED",
    analysisStatus: String = "READY",
    requiredAction: String = "NONE",
    allowedActions: List<String> = listOf("EDIT", "DELETE"),
) = WishlistItemDto(
    id = "i", clientSubmissionId = "k", version = JsonPrimitive(1), sourceUrl = "https://shop.example/x",
    product = ProductDto(), category = CategoryDto(), purpose = PurposeDto(),
    analysis = AnalysisDto(analysisStatus), reviewStatus = reviewStatus, lifecycleStatus = "ACTIVE",
    requiredAction = requiredAction, createdAt = "2026-10-07T00:00:00Z", updatedAt = "2026-10-07T00:00:00Z",
    allowedActions = allowedActions,
)

private fun assertInvalid(result: ClientResult<*>, label: String = "") =
    assertEquals(ErrorKind.INVALID_RESPONSE, (result as? ClientResult.Failure)?.error?.kind, label)

class ItemMapperTest {
    @Test fun maps_every_field_of_a_full_item_and_keeps_server_policy() {
        val item = mapped().successValue()
        assertEquals("00000000-0000-4000-8000-000000000101", item.id)
        assertEquals("00000000-0000-4000-8000-000000000003", item.clientSubmissionId)
        assertEquals(2, item.version)
        assertEquals("https://shop.example/headphone", item.sourceUrl)
        assertEquals("Headphones", item.product.name)
        assertEquals("https://img.example/h.png", item.product.imageUrl)
        assertEquals(DecimalAmount.parseOrNull("129000.5"), item.product.price)
        assertEquals("KRW", item.product.currency)
        assertEquals("Acme", item.product.brand)
        assertEquals("shop.example", item.product.merchant)
        assertEquals(Instant.parse("2026-10-07T00:00:05Z"), item.product.metadataCheckedAt)
        assertEquals(ValueSource.AI, item.product.nameSource)
        assertEquals(ValueSource.AI, item.product.imageSource)
        assertEquals(ItemCategory("C026", ValueSource.AI, null, "헤드폰", "C020", "PUBLIC"), item.category)
        assertEquals(ItemPurpose(null, ValueSource.UNASSIGNED), item.purpose)
        assertEquals(ItemAnalysis(AnalysisStatus.READY, null), item.analysis)
        assertEquals(ReviewStatus.PENDING, item.reviewStatus)
        assertEquals(LifecycleStatus.ACTIVE, item.lifecycleStatus)
        assertEquals(RequiredAction.CLASSIFICATION_REVIEW, item.requiredAction)
        assertEquals(setOf(ItemAction.EDIT, ItemAction.DELETE, ItemAction.REVIEW), item.allowedActions)
        assertEquals(Instant.parse("2026-10-07T00:00:00Z"), item.createdAt)
        assertEquals(Instant.parse("2026-10-07T00:00:05Z"), item.updatedAt)
        assertNull(item.manualCompletionAt)
        assertEquals(Instant.parse("2026-10-06T23:59:00.123Z"), item.clientCreatedAt)
    }

    @Test fun server_policy_is_not_overwritten_by_local_evaluation() {
        // READY + pending review + no category: a local evaluator would say something else.
        val item = mapped(
            "category" to """{"id":null,"source":null,"missingReason":null}""",
            "requiredAction" to "\"CATEGORY_ASSIGNMENT\"", "allowedActions" to """["DELETE","MANUAL_COMPLETE"]""",
        ).successValue()
        assertEquals(RequiredAction.CATEGORY_ASSIGNMENT, item.requiredAction)
        assertEquals(setOf(ItemAction.DELETE, ItemAction.MANUAL_COMPLETE), item.allowedActions)
    }

    @Test fun absent_nullable_metadata_and_extra_fields_are_accepted() {
        val item = mapped(
            "product" to "{}", "category" to "{}", "purpose" to "{}", "manualCompletionAt" to null,
            "clientCreatedAt" to null, "allowedActions" to null, "futureField" to """{"a":1}""",
            "analysis.surprise" to "true",
        ).successValue()
        assertNull(item.product.name); assertNull(item.product.price); assertNull(item.product.metadataCheckedAt)
        assertNull(item.category.id); assertNull(item.category.kind)
        assertEquals(ValueSource.UNASSIGNED, item.purpose.source)
        assertNull(item.clientCreatedAt)
        assertEquals(emptySet(), item.allowedActions)
    }

    @Test fun descriptive_unknown_keeps_server_actions() {
        val item = mapItem(dtoFixture(reviewStatus = "NEW_REVIEW_VALUE",
            allowedActions = listOf("EDIT", "DELETE", "NEW_ACTION"))).successValue()
        assertEquals(setOf(ItemAction.EDIT, ItemAction.DELETE), item.allowedActions)
        assertEquals(ReviewStatus.UNKNOWN, item.reviewStatus)
    }

    @Test fun branching_unknown_preserves_server_delete_only() {
        val item = mapItem(dtoFixture(analysisStatus = "NEW_ANALYSIS_VALUE",
            allowedActions = listOf("EDIT", "DELETE"))).successValue()
        assertEquals(setOf(ItemAction.DELETE), item.allowedActions)
        assertEquals(AnalysisStatus.UNKNOWN, item.analysis.status)
        val action = mapItem(dtoFixture(requiredAction = "NEW_ACTION", allowedActions = listOf("EDIT", "DELETE", "REVIEW")))
            .successValue()
        assertEquals(RequiredAction.UNKNOWN, action.requiredAction)
        assertEquals(setOf(ItemAction.DELETE), action.allowedActions)
    }

    @Test fun branching_unknown_never_invents_a_delete() {
        val item = mapItem(dtoFixture(analysisStatus = "NEW_ANALYSIS_VALUE", allowedActions = listOf("EDIT")))
            .successValue()
        assertEquals(emptySet(), item.allowedActions)
        val required = mapItem(dtoFixture(requiredAction = "NEW", allowedActions = emptyList())).successValue()
        assertEquals(emptySet(), required.allowedActions)
    }

    @Test fun unknown_lifecycle_status_is_invalid_response() {
        assertInvalid(mapped("lifecycleStatus" to "\"PAUSED\""))
    }

    @Test fun unknown_source_and_missing_reason_become_unknown_and_keep_known_actions() {
        val item = mapped(
            "product.nameSource" to "\"ROBOT\"", "product.imageSource" to "\"ROBOT\"",
            "category" to """{"id":null,"source":"ROBOT","missingReason":"NEW_REASON"}""",
            "purpose" to """{"id":"P1","source":"ROBOT"}""",
        ).successValue()
        assertEquals(ValueSource.UNKNOWN, item.product.nameSource)
        assertEquals(ValueSource.UNKNOWN, item.product.imageSource)
        assertEquals(ValueSource.UNKNOWN, item.category.source)
        assertEquals(CategoryMissingReason.UNKNOWN, item.category.missingReason)
        assertEquals(ValueSource.UNKNOWN, item.purpose.source)
        assertEquals(setOf(ItemAction.EDIT, ItemAction.DELETE, ItemAction.REVIEW), item.allowedActions)
    }

    @Test fun every_known_missing_reason_and_review_status_maps() {
        listOf("EXTRACTION_UNRESOLVED", "AI_ABSTAINED", "AI_RESPONSE_UNUSABLE", "CUSTOM_CATEGORY_DELETED").forEach {
            val reason = mapped("category" to """{"id":null,"missingReason":"$it"}""").successValue().category.missingReason
            assertEquals(CategoryMissingReason.valueOf(it), reason)
        }
        listOf("NOT_REQUIRED", "PENDING", "CONFIRMED", "DEFERRED").forEach {
            assertEquals(ReviewStatus.valueOf(it), mapped("reviewStatus" to "\"$it\"").successValue().reviewStatus)
        }
    }

    @Test fun unknown_allowed_actions_are_dropped() {
        val item = mapped("allowedActions" to """["EDIT","TELEPORT","DELETE"]""").successValue()
        assertEquals(setOf(ItemAction.EDIT, ItemAction.DELETE), item.allowedActions)
    }

    @Test fun server_without_delete_yields_an_empty_set_for_branching_unknown() {
        val item = mapped("analysis.status" to "\"NEW\"", "allowedActions" to """["EDIT","REVIEW"]""").successValue()
        assertEquals(emptySet(), item.allowedActions)
    }

    @Test fun known_and_unknown_failure_codes_are_preserved_raw() {
        val known = mapped("analysis" to """{"status":"FAILED_TERMINAL","failureCode":"BLOCKED_ADDRESS"}""").successValue()
        assertEquals(ItemAnalysis(AnalysisStatus.FAILED_TERMINAL, "BLOCKED_ADDRESS"), known.analysis)
        val unknown = mapped("analysis" to """{"status":"FAILED_RETRYABLE","failureCode":"BRAND_NEW_CODE"}""").successValue()
        assertEquals(ItemAnalysis(AnalysisStatus.FAILED_RETRYABLE, "BRAND_NEW_CODE"), unknown.analysis)
    }

    @Test fun deleted_tombstone_and_archived_keep_server_values() {
        val tombstone = mapped("lifecycleStatus" to "\"DELETED\"", "requiredAction" to "\"NONE\"",
            "allowedActions" to "[]", "version" to "3").successValue()
        assertEquals(LifecycleStatus.DELETED, tombstone.lifecycleStatus)
        assertEquals(emptySet(), tombstone.allowedActions)
        val archived = mapped("lifecycleStatus" to "\"ARCHIVED\"", "requiredAction" to "\"NONE\"",
            "allowedActions" to """["DELETE"]""").successValue()
        assertEquals(LifecycleStatus.ARCHIVED, archived.lifecycleStatus)
        assertEquals(setOf(ItemAction.DELETE), archived.allowedActions)
    }

    @Test fun price_keeps_exact_raw_decimal() {
        val big = mapped("product.price" to "12345678901234567890.123456789").successValue()
        assertEquals("12345678901234567890.123456789", big.product.price?.canonical)
        assertEquals("0", mapped("product.price" to "0.00").successValue().product.price?.canonical)
        assertEquals("-5", mapped("product.price" to "-5").successValue().product.price?.canonical)
        assertNull(mapped("product.price" to "null").successValue().product.price)
        assertNull(mapped("product.price" to null).successValue().product.price)
    }

    @Test fun price_json_string_boolean_container_and_exponent_are_rejected() {
        listOf("\"10\"", "true", "[1]", """{"v":1}""", "1e3").forEach {
            assertInvalid(parseItem(itemJson("product.price" to it)))
        }
    }

    @Test fun read_price_covers_each_json_shape() {
        assertNull(readPrice(null).successValue())
        assertNull(readPrice(JsonNull).successValue())
        assertEquals("10.5", readPrice(JsonPrimitive(10.5)).successValue()?.canonical)
        assertInvalid(readPrice(JsonPrimitive("10")))
        assertInvalid(readPrice(JsonPrimitive(true)))
        assertInvalid(readPrice(JsonArray(emptyList())))
        assertInvalid(readPrice(JsonObject(emptyMap())))
    }

    @Test fun missing_required_fields_are_invalid_response() {
        listOf("id", "clientSubmissionId", "version", "sourceUrl", "product", "category", "purpose", "analysis",
            "analysis.status", "reviewStatus", "lifecycleStatus", "requiredAction", "createdAt", "updatedAt").forEach {
            assertInvalid(mapped(it to null))
        }
    }

    @Test fun wrong_json_types_are_invalid_response() {
        listOf(
            "version" to "\"2\"", "version" to "2.5", "version" to "null", "id" to "5", "id" to "null",
            "sourceUrl" to "[]", "product" to "[]", "product" to "\"x\"", "product.name" to "5",
            "category" to "7", "analysis" to "\"READY\"", "analysis.status" to "1", "reviewStatus" to "true",
            "allowedActions" to "\"EDIT\"", "allowedActions" to "[1]", "createdAt" to "123",
            "product.currency" to "{}", "purpose" to "null",
        ).forEach { (path, raw) -> assertInvalid(mapped(path to raw), "$path=$raw") }
    }

    @Test fun invalid_instants_are_invalid_response() {
        listOf("createdAt", "updatedAt", "manualCompletionAt", "clientCreatedAt", "product.metadataCheckedAt").forEach {
            assertInvalid(mapped(it to "\"yesterday\""))
        }
    }

    @Test fun model_invariant_violations_are_invalid_response_not_exceptions() {
        assertInvalid(mapped("version" to "0"))
        assertInvalid(mapped("version" to "-1"))
        assertInvalid(mapped("category" to """{"id":"C026","missingReason":"AI_ABSTAINED"}"""))
        assertInvalid(mapped("category" to """{"id":"C026","missingReason":"NEW_REASON"}"""))
        assertInvalid(mapItem(dtoFixture().copy(version = JsonPrimitive(0))))
    }

    @Test fun garbage_bodies_never_throw() {
        listOf("", "not json", "[]", "null", "42", "\"x\"", "{", "{}").forEach { assertInvalid(parseItem(it)) }
    }
}
