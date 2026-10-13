package app.wishlist.shared.domain

import app.wishlist.shared.model.AnalysisStatus
import app.wishlist.shared.model.LifecycleStatus
import app.wishlist.shared.model.RequiredAction
import app.wishlist.shared.model.itemFixture
import kotlin.test.Test
import kotlin.test.assertEquals

class DetailKindTest {
    @Test fun everyLifecycleAndRequiredActionHasOneKind() {
        for (life in LifecycleStatus.entries) for (action in RequiredAction.entries) {
            val p = DetailKinds.of(itemFixture(lifecycle = life).copy(requiredAction = action))
            val expected = when {
                life == LifecycleStatus.DELETED -> DetailPresentation(DetailKind.GONE, null) // defensive: GET 404s, accept drops it
                life == LifecycleStatus.ARCHIVED -> DetailPresentation(DetailKind.READY, null)
                action == RequiredAction.ANALYSIS_IN_PROGRESS -> DetailPresentation(DetailKind.PROCESSING, null)
                action == RequiredAction.INFORMATION_COMPLETION -> DetailPresentation(DetailKind.INCOMPLETE, DetailNotice.INFORMATION_MISSING)
                action == RequiredAction.CATEGORY_ASSIGNMENT -> DetailPresentation(DetailKind.INCOMPLETE, DetailNotice.CATEGORY_UNDECIDED)
                action == RequiredAction.CATEGORY_REASSIGNMENT -> DetailPresentation(DetailKind.INCOMPLETE, DetailNotice.CATEGORY_DELETED)
                action == RequiredAction.CLASSIFICATION_REVIEW || action == RequiredAction.NONE -> DetailPresentation(DetailKind.READY, null)
                else -> null // UNKNOWN: below
            }
            if (expected != null) assertEquals(expected, p, "$life/$action")
        }
    }

    @Test fun unknownActionFallsBackToTheFields() {
        fun of(analysis: AnalysisStatus = AnalysisStatus.READY, name: String? = "헤드폰", categoryId: String? = "C026") =
            DetailKinds.of(itemFixture(analysis = analysis, name = name, categoryId = categoryId).copy(requiredAction = RequiredAction.UNKNOWN))
        assertEquals(DetailPresentation(DetailKind.PROCESSING, null), of(analysis = AnalysisStatus.PROCESSING))
        assertEquals(DetailPresentation(DetailKind.INCOMPLETE, DetailNotice.INFORMATION_MISSING), of(name = null))
        assertEquals(DetailPresentation(DetailKind.INCOMPLETE, DetailNotice.INFORMATION_MISSING), of(categoryId = null))
        assertEquals(DetailPresentation(DetailKind.READY, null), of())
    }

    @Test fun failedButCompletedItemIsReady() {
        val item = itemFixture(analysis = AnalysisStatus.FAILED_TERMINAL).copy(requiredAction = RequiredAction.NONE)
        assertEquals(DetailPresentation(DetailKind.READY, null), DetailKinds.of(item))
    }
}
