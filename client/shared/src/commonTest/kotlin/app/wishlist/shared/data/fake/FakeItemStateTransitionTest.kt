@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package app.wishlist.shared.data.fake

import app.wishlist.shared.core.*
import app.wishlist.shared.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlin.test.*

class FakeItemStateTransitionTest {
    @Test fun processing_rejects_mutations_but_allows_repeat_delete() = runTest {
        val f = FakeFixture(); f.login(); val item = f.create()
        assertEquals(ErrorKind.CONFLICT, f.controls.edit(item.id, 1, ItemPatch(name = Patch.Set("New"))).error().kind)
        assertEquals(ErrorKind.CONFLICT, f.controls.manualComplete(item.id, 1, "New", "C026").error().kind)
        assertEquals(ErrorKind.CONFLICT, f.controls.review(item.id, 1, ReviewDecision.CONFIRM).error().kind)
        assertEquals(ErrorKind.CONFLICT, f.controls.reanalyze(item.id, "attempt").error().kind)
        f.controls.delete(item.id).successValue(); f.controls.delete(item.id).successValue()
        assertEquals(ErrorKind.NOT_FOUND, f.controls.delete("unknown").error().kind)
        f.login("B"); assertEquals(ErrorKind.NOT_FOUND, f.controls.delete(item.id).error().kind)
    }
    @Test fun simultaneous_edits_enforce_version_cas() = runTest {
        val f = FakeFixture(); f.login(); val item = f.create(); f.finish(item)
        val a = async { f.controls.edit(item.id, 2, ItemPatch(name = Patch.Set("A"))) }
        val b = async { f.controls.edit(item.id, 2, ItemPatch(name = Patch.Set("B"))) }
        val results = listOf(a.await(), b.await())
        assertEquals(1, results.count { it is ClientResult.Success })
        assertEquals(3, results.single { it is ClientResult.Failure }.error().currentVersion)
        assertEquals(3, f.repository.get(item.id).successValue().version)
    }
    @Test fun patch_omission_preserves_values_and_explicit_null_clears_them() = runTest {
        val f = FakeFixture(); f.login(); val item = f.create(); f.finish(item)
        val updated = f.controls.edit(item.id, 2, ItemPatch(imageUrl = Patch.Set("https://img.example/a"))).successValue()
        assertEquals("Headphones", updated.product.name)
        val cleared = f.controls.edit(item.id, 3, ItemPatch(imageUrl = Patch.Set(null), categoryId = Patch.Set(null))).successValue()
        assertNull(cleared.product.imageUrl); assertNull(cleared.category.id)
        assertEquals(RequiredAction.INFORMATION_COMPLETION, cleared.requiredAction)
    }
    @Test fun manual_completion_retains_analysis_and_failure_and_blocks_retry() = runTest {
        val f = FakeFixture(); f.login(); val item = f.create()
        f.controls.completeAnalysis(item.id, 1, AnalysisOutcome(AnalysisStatus.FAILED_RETRYABLE,
            null, null, CategoryMissingReason.EXTRACTION_UNRESOLVED, "TEMPORARY_FAILURE")).successValue()
        assertEquals(ErrorKind.VALIDATION, f.controls.manualComplete(item.id, 2, " ", "C026").error().kind)
        val completed = f.controls.manualComplete(item.id, 2, "Manual name", "C026").successValue()
        assertEquals(AnalysisStatus.FAILED_RETRYABLE, completed.analysis.status)
        assertEquals("TEMPORARY_FAILURE", completed.analysis.failureCode)
        assertEquals(fakeTime, completed.manualCompletionAt)
        assertEquals(ReviewStatus.CONFIRMED, completed.reviewStatus)
        assertEquals(setOf(ItemAction.EDIT, ItemAction.DELETE), completed.allowedActions)
        assertEquals(ErrorKind.CONFLICT, f.controls.reanalyze(item.id, "attempt").error().kind)
        assertEquals(ErrorKind.CONFLICT, f.controls.manualComplete(item.id, 3, "Again", "C026").error().kind)
    }
    @Test fun review_defer_and_confirm_cannot_reopen_completed_review() = runTest {
        for (decision in ReviewDecision.entries) {
            val f = FakeFixture(); f.login(); val item = f.create(); f.finish(item)
            val reviewed = f.controls.review(item.id, 2, decision).successValue()
            assertEquals(if(decision == ReviewDecision.DEFER) ReviewStatus.DEFERRED else ReviewStatus.CONFIRMED, reviewed.reviewStatus)
            assertEquals(RequiredAction.NONE, reviewed.requiredAction)
            assertFalse(ItemAction.REVIEW in reviewed.allowedActions)
            assertEquals(ErrorKind.CONFLICT, f.controls.review(item.id, 3, ReviewDecision.CONFIRM).error().kind)
            val edited = f.controls.edit(item.id, 3, ItemPatch(name = Patch.Set("Edited"))).successValue()
            assertEquals(reviewed.reviewStatus, edited.reviewStatus)
            assertEquals(RequiredAction.NONE, edited.requiredAction)
        }
    }
    @Test fun retry_attempt_is_idempotent_and_stale_generation_cannot_overwrite() = runTest {
        val f = FakeFixture(); f.login(); val item = f.create(); f.finish(item, AnalysisStatus.FAILED_RETRYABLE)
        val retry = f.controls.reanalyze(item.id, "attempt-1").successValue()
        assertEquals(3, retry.version); assertEquals(AnalysisStatus.PROCESSING, retry.analysis.status)
        assertEquals(retry, f.controls.reanalyze(item.id, "attempt-1").successValue())
        f.controls.completeAnalysis(item.id, 1, AnalysisOutcome(AnalysisStatus.READY, "Stale", "C026", null, null)).successValue()
        assertEquals(retry, f.repository.get(item.id).successValue())
        f.controls.completeAnalysis(item.id, 2, AnalysisOutcome(AnalysisStatus.READY, "Fresh", "C026", null, null)).successValue()
        val fresh = f.repository.get(item.id).successValue()
        assertEquals("Fresh", fresh.product.name); assertEquals(4, fresh.version)
        assertEquals(fresh, f.controls.reanalyze(item.id, "attempt-1").successValue())
    }
    @Test fun deletion_ignores_late_analysis_result_and_keeps_tombstone_version() = runTest {
        val f = FakeFixture(); f.login(); val command = f.command()
        val item = f.repository.create(command).successValue(); f.controls.delete(item.id).successValue()
        f.finish(item)
        val tombstone = f.repository.create(command).successValue()
        assertEquals(LifecycleStatus.DELETED, tombstone.lifecycleStatus)
        assertEquals(2, tombstone.version); assertEquals(emptySet(), tombstone.allowedActions)
        assertEquals(ErrorKind.NOT_FOUND, f.repository.get(item.id).error().kind)
    }
    @Test fun completed_generation_is_not_applied_twice() = runTest {
        val f = FakeFixture(); f.login(); val item = f.create(); f.finish(item); f.finish(item)
        assertEquals(2, f.repository.get(item.id).successValue().version)
    }
}
