package app.wishlist.shared.data.fake

import app.wishlist.shared.core.*
import app.wishlist.shared.model.*

/** Fake-only patch vocabulary, independent of unimplemented mutation DTOs. */
internal sealed interface Patch<out T> {
    data object Unchanged : Patch<Nothing>
    data class Set<T>(val value: T) : Patch<T>
}
internal data class ItemPatch(
    val name: Patch<String?> = Patch.Unchanged,
    val imageUrl: Patch<String?> = Patch.Unchanged,
    val categoryId: Patch<String?> = Patch.Unchanged,
    val purposeId: Patch<String?> = Patch.Unchanged,
)
internal enum class ReviewDecision { CONFIRM, DEFER }
internal data class AnalysisOutcome(
    val status: AnalysisStatus,
    val name: String?,
    val categoryId: String?,
    val missingReason: CategoryMissingReason?,
    val failureCode: String?,
)

/** Explicit development controls; these do not implement mutation wire APIs. */
internal class FakeControls(private val store: FakeStore) {
    suspend fun completeAnalysis(id: String, analysisGeneration: Int, result: AnalysisOutcome) =
        store.completeAnalysis(id, analysisGeneration, result)
    fun failNext(apiId: ApiId, error: ClientError) = store.failNext(apiId, error)

    /**
     * C5 DEBUG fixture: [count] READY items in [categoryId] for the 40-item page boundary. Every
     * 10th has a very tall photo and the first has a photo that answers slowly (skeleton timeout);
     * the URLs are placeholders the DEBUG image hosts answer (`seed.wishlist.invalid` otherwise fails).
     */
    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    suspend fun seedLongCategory(categoryId: String, now: kotlin.time.Instant, count: Int = 120): ClientResult<Int> {
        require(count in 1..500) { "count must be 1..500" }
        repeat(count) { n ->
            val at = now - (n + 1).toLong().let { kotlin.time.Duration.parse("${it}s") }
            val image = when {
                n == 0 -> "https://seed.wishlist.invalid/slow/$n.jpg"
                n % 10 == 9 -> "https://seed.wishlist.invalid/tall/$n.jpg"
                else -> null
            }
            val item = WishlistItem(
                id = kotlin.uuid.Uuid.random().toString(), clientSubmissionId = kotlin.uuid.Uuid.random().toString(), version = 1,
                sourceUrl = "https://seed.wishlist.invalid/long/$n",
                product = ProductSnapshot(name = "긴 목록 ${n + 1}", imageUrl = image, price = DecimalAmount.parseOrNull("10000"),
                    currency = "KRW", metadataCheckedAt = now - kotlin.time.Duration.parse("${n}d")),
                category = ItemCategory(categoryId, ValueSource.USER), purpose = ItemPurpose(),
                analysis = ItemAnalysis(AnalysisStatus.READY), reviewStatus = ReviewStatus.CONFIRMED,
                lifecycleStatus = LifecycleStatus.ACTIVE, requiredAction = RequiredAction.NONE, createdAt = at, updatedAt = at,
            )
            when (val put = store.putItem(item)) {
                is ClientResult.Failure -> return put
                is ClientResult.Success -> Unit
            }
        }
        return ClientResult.Success(count)
    }
    fun delayNext(apiId: ApiId, millis: Long) = store.delayNext(apiId, millis)
    suspend fun edit(id: String, expectedVersion: Int, patch: ItemPatch) = store.edit(id, expectedVersion, patch)
    suspend fun manualComplete(id: String, expectedVersion: Int, name: String, categoryId: String) =
        store.manualComplete(id, expectedVersion, name, categoryId)
    suspend fun review(id: String, expectedVersion: Int, decision: ReviewDecision, patch: ItemPatch = ItemPatch()) =
        store.review(id, expectedVersion, decision, patch)
    suspend fun delete(id: String) = store.delete(id)
    suspend fun reanalyze(id: String, attemptRequestId: String) = store.reanalyze(id, attemptRequestId)
}
