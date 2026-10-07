package app.http

import app.wishlist.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class WishlistItemViewMapperTest {
    @Test fun `failure mapping covers public categories missing diagnostics and unknown legacy strings`() {
        val cases = listOf(
            Triple(AnalysisStatus.PARTIAL, "AI_ABSTAINED", "AI_ABSTAINED"),
            Triple(AnalysisStatus.PARTIAL, "AI_UNUSABLE_RESPONSE", "AI_UNUSABLE_RESPONSE"),
            Triple(AnalysisStatus.PARTIAL, "AI_INVALID_CANDIDATE", "AI_INVALID_CANDIDATE"),
            Triple(AnalysisStatus.PARTIAL, "AI_USAGE_OUT_OF_RANGE", "AI_USAGE_OUT_OF_RANGE"),
            Triple(AnalysisStatus.PARTIAL, "AI_BUDGET_EXCEEDED", "AI_BUDGET_EXCEEDED"),
            Triple(AnalysisStatus.FAILED_TERMINAL, "AI_CONFIGURATION_ERROR", "AI_CONFIGURATION_ERROR"),
            Triple(AnalysisStatus.FAILED_TERMINAL, "BLOCKED_ADDRESS", "BLOCKED_ADDRESS"),
            Triple(AnalysisStatus.FAILED_TERMINAL, "UNSUPPORTED_CONTENT", "UNSUPPORTED_CONTENT"),
            Triple(AnalysisStatus.FAILED_TERMINAL, "ACCESS_DENIED", "ACCESS_DENIED"),
            Triple(AnalysisStatus.FAILED_RETRYABLE, null, "ANALYSIS_RETRYABLE_FAILURE"),
            Triple(AnalysisStatus.FAILED_TERMINAL, null, "ANALYSIS_FAILED"),
            Triple(AnalysisStatus.FAILED_TERMINAL, "secret diagnostic", "ANALYSIS_FAILED"),
            Triple(AnalysisStatus.PARTIAL, "secret diagnostic", "ANALYSIS_FAILED"),
            Triple(AnalysisStatus.PARTIAL, null, null),
            Triple(AnalysisStatus.READY, "ACCESS_DENIED", null),
            Triple(AnalysisStatus.PROCESSING, "secret diagnostic", null),
        )
        for ((status, failure, expected) in cases) {
            val dto = WishlistItemViewMapper.map(item(status, failure))
            assertEquals(expected, dto.analysis.failureCode?.name)
            val body = Json.parseToJsonElement(ApiJson.encodeToString(dto)).jsonObject
            assertEquals(expected?.let(::JsonPrimitive) ?: JsonNull, body.getValue("analysis").jsonObject.getValue("failureCode"))
            assertFalse(body.toString().contains("secret diagnostic"))
        }
    }

    @Test fun `archived and deleted snapshots have no actions while preserving actual metadata`() {
        for (lifecycle in listOf(LifecycleStatus.ARCHIVED, LifecycleStatus.DELETED)) {
            val dto = WishlistItemViewMapper.map(item(AnalysisStatus.READY, null, lifecycle))
            assertEquals("보관 상품", dto.product.name)
            assertEquals(ValueSource.USER, dto.product.nameSource)
            assertEquals("https://example.com/image", dto.product.imageUrl)
            assertEquals(RequiredAction.NONE, dto.requiredAction)
            assertEquals(emptySet(), dto.allowedActions)
        }
    }

    private fun item(status: AnalysisStatus, failure: String?, lifecycle: LifecycleStatus = LifecycleStatus.ACTIVE) = WishlistItem(
        storedState = StoredWishlistItemState(UUID.randomUUID(), UUID.randomUUID(), 7, 1,
            WishlistItemState(status, ReviewStatus.CONFIRMED, lifecycle, "보관 상품", "C026", null, null),
            ValueSource.USER, null, ValueSource.UNASSIGNED, ValueSource.USER, ValueSource.AI, emptySet()),
        clientSubmissionId = UUID.randomUUID(), sourceUrl = "https://example.com/item", productImageUrl = "https://example.com/image",
        analysisFailureCode = failure, clientCreatedAt = null, createdAt = Instant.parse("2026-10-06T01:00:00Z"),
        updatedAt = Instant.parse("2026-10-06T02:00:00Z"),
    )
}
