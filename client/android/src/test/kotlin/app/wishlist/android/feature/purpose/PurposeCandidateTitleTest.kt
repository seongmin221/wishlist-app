package app.wishlist.android.feature.purpose

import app.wishlist.android.R
import app.wishlist.shared.model.AnalysisStatus
import org.junit.Assert.*
import org.junit.Test

class PurposeCandidateTitleTest {
    @Test fun absentNamesUseAnalysisStatusInsteadOfEmptyCardTitles() {
        assertEquals(R.string.home_processing_title, purposeCandidateTitleFallback(null, AnalysisStatus.PROCESSING))
        assertEquals(R.string.home_processing_title, purposeCandidateTitleFallback("  \n", AnalysisStatus.PROCESSING))
        assertEquals(R.string.purpose_candidate_needs_info, purposeCandidateTitleFallback(null, AnalysisStatus.PARTIAL))
        assertEquals(R.string.purpose_candidate_needs_info, purposeCandidateTitleFallback("", AnalysisStatus.READY))
        assertNull(purposeCandidateTitleFallback("Headphones", AnalysisStatus.PROCESSING))
        assertNull(purposeCandidateTitleFallback(" 이어폰 ", AnalysisStatus.READY))
    }
}
