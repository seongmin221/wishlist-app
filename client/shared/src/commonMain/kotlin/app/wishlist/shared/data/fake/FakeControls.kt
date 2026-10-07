package app.wishlist.shared.data.fake

import app.wishlist.shared.core.*
import app.wishlist.shared.model.*

/** Fake-only patch vocabulary, independent of unimplemented mutation DTOs. */
sealed interface Patch<out T> {
    data object Unchanged : Patch<Nothing>
    data class Set<T>(val value: T) : Patch<T>
}
data class ItemPatch(
    val name: Patch<String?> = Patch.Unchanged,
    val imageUrl: Patch<String?> = Patch.Unchanged,
    val categoryId: Patch<String?> = Patch.Unchanged,
    val purposeId: Patch<String?> = Patch.Unchanged,
)
enum class ReviewDecision { CONFIRM, DEFER }
data class AnalysisOutcome(
    val status: AnalysisStatus,
    val name: String?,
    val categoryId: String?,
    val missingReason: CategoryMissingReason?,
    val failureCode: String?,
)

/** Explicit development controls; these do not implement mutation wire APIs. */
class FakeControls(private val store: FakeStore) {
    suspend fun completeAnalysis(id: String, analysisGeneration: Int, result: AnalysisOutcome) =
        store.completeAnalysis(id, analysisGeneration, result)
    fun failNext(apiId: ApiId, error: ClientError) = store.failNext(apiId, error)
    fun delayNext(apiId: ApiId, millis: Long) = store.delayNext(apiId, millis)
    suspend fun edit(id: String, expectedVersion: Int, patch: ItemPatch) = store.edit(id, expectedVersion, patch)
    suspend fun manualComplete(id: String, expectedVersion: Int, name: String, categoryId: String) =
        store.manualComplete(id, expectedVersion, name, categoryId)
    suspend fun review(id: String, expectedVersion: Int, decision: ReviewDecision, patch: ItemPatch = ItemPatch()) =
        store.review(id, expectedVersion, decision, patch)
    suspend fun delete(id: String) = store.delete(id)
    suspend fun reanalyze(id: String, attemptRequestId: String) = store.reanalyze(id, attemptRequestId)
}
