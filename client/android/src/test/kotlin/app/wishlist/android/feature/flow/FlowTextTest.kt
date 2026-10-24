package app.wishlist.android.feature.flow

import app.wishlist.android.R
import app.wishlist.android.feature.home.ResText
import app.wishlist.android.testing.StringsXml
import app.wishlist.shared.model.AnalysisStatus
import app.wishlist.shared.model.RequiredAction
import app.wishlist.shared.model.ValueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Flow wording keys (no Android Resources on the JVM: the ko values are read from the XML). */
class FlowTextTest {
    private val ko = StringsXml.load("src/main/res/values/home_flow_strings.xml")

    @Test fun summaries_count_this_sessions_passes_only() {
        assertEquals(ResText(R.string.flow_review_summary, 3), HomeFlowText.passedSummary(3))
        assertEquals(ResText(R.string.flow_fill_summary, 2), HomeFlowText.skippedSummary(2))
        assertEquals("넘김 3", ko.format("flow_review_summary", 3))
        assertEquals("건너뜀 2", ko.format("flow_fill_summary", 2))
    }

    @Test fun position_reads_n_of_total() {
        assertEquals(ResText(R.string.flow_position, args = listOf(2, 5)), HomeFlowText.position(2, 5))
        assertEquals("2 / 5", ko.format("flow_position", 2, 5))
    }

    @Test fun ai_line_follows_the_category_and_purpose_sources() {
        assertEquals(R.string.flow_ai_both, HomeFlowText.aiLine(ValueSource.AI, ValueSource.AI))
        assertEquals(R.string.flow_ai_category_no_purpose, HomeFlowText.aiLine(ValueSource.AI, ValueSource.UNASSIGNED))
        assertEquals(R.string.flow_ai_category, HomeFlowText.aiLine(ValueSource.AI, ValueSource.USER))
        assertEquals(R.string.flow_ai_purpose, HomeFlowText.aiLine(ValueSource.USER, ValueSource.AI))
        assertNull(HomeFlowText.aiLine(ValueSource.USER, ValueSource.USER))
        assertNull(HomeFlowText.aiLine(null, ValueSource.UNASSIGNED))
        assertEquals("AI가 카테고리와 목적을 연결했어요", ko.format("flow_ai_both"))
    }

    @Test fun fill_reason_follows_the_required_action() {
        assertEquals(R.string.flow_fill_reason_info, HomeFlowText.fillReason(RequiredAction.INFORMATION_COMPLETION, nameMissing = true, AnalysisStatus.FAILED_TERMINAL))
        assertEquals(R.string.flow_fill_reason_name, HomeFlowText.fillReason(RequiredAction.INFORMATION_COMPLETION, nameMissing = true, AnalysisStatus.PARTIAL))
        assertEquals(R.string.flow_fill_reason_info, HomeFlowText.fillReason(RequiredAction.INFORMATION_COMPLETION, nameMissing = false, AnalysisStatus.PARTIAL))
        assertEquals(R.string.flow_fill_reason_category, HomeFlowText.fillReason(RequiredAction.CATEGORY_ASSIGNMENT, nameMissing = false, AnalysisStatus.READY))
        assertEquals(R.string.flow_fill_reason_category_deleted, HomeFlowText.fillReason(RequiredAction.CATEGORY_REASSIGNMENT, nameMissing = false, AnalysisStatus.READY))
        assertEquals("카테고리가 삭제됐어요", ko.format("flow_fill_reason_category_deleted"))
    }

    @Test fun the_new_review_hint_says_saving_comes_later() {
        assertEquals("확정·보류 저장은 다음 단계에서 연결돼요", ko.format("flow_review_not_saved"))
    }
}
