package app.wishlist.android.feature.home

import app.wishlist.android.R
import app.wishlist.android.designsystem.WLLineIcon
import app.wishlist.android.testing.StringsXml
import app.wishlist.android.testing.TestItems
import app.wishlist.shared.model.HomeActionGroup
import app.wishlist.shared.model.ItemCategory
import app.wishlist.shared.model.RequiredAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FHome todo cards: header caption, each group's title/subtitle/icon and the carousel caption (board FHome). */
class HomeTodoTextTest {
    private val ko = StringsXml.load("src/main/res/values/strings.xml")
    private val flowKo = StringsXml.load("src/main/res/values/home_flow_strings.xml")

    @Test fun header_caption_counts_every_todo() {
        assertEquals(ResText(R.plurals.home_todo_count, 8, count = 8), HomeRowText.todoCount(8))
        assertEquals("할 일 8개", ko.plural("home_todo_count", 8))
    }

    @Test fun each_group_has_the_board_title_subtitle_and_icon() {
        assertEquals(R.string.home_group_review_title, HomeTodoText.title(HomeActionGroup.CLASSIFICATION_REVIEW))
        assertEquals(R.string.home_group_fill_title, HomeTodoText.title(HomeActionGroup.INFORMATION_COMPLETION))
        assertEquals(R.string.home_processing_title, HomeTodoText.title(HomeActionGroup.ANALYSIS_IN_PROGRESS))
        assertEquals(R.string.home_group_review_subtitle, HomeTodoText.subtitle(HomeActionGroup.CLASSIFICATION_REVIEW))
        assertEquals(R.string.home_group_fill_subtitle, HomeTodoText.subtitle(HomeActionGroup.INFORMATION_COMPLETION))
        assertEquals(R.string.home_processing_subtitle, HomeTodoText.subtitle(HomeActionGroup.ANALYSIS_IN_PROGRESS))
        assertEquals(WLLineIcon.Pending, HomeTodoText.icon(HomeActionGroup.CLASSIFICATION_REVIEW))
        assertEquals(WLLineIcon.Pen, HomeTodoText.icon(HomeActionGroup.INFORMATION_COMPLETION))
        assertEquals(WLLineIcon.Sorting, HomeTodoText.icon(HomeActionGroup.ANALYSIS_IN_PROGRESS))
        assertEquals("분류·목적 확인", flowKo.format("home_group_review_title"))
        assertEquals("이름과 카테고리만 알려 주세요", flowKo.format("home_group_fill_subtitle"))
    }

    @Test fun only_review_and_fill_open_a_flow() {
        assertTrue(HomeTodoText.opensFlow(HomeActionGroup.CLASSIFICATION_REVIEW))
        assertTrue(HomeTodoText.opensFlow(HomeActionGroup.INFORMATION_COMPLETION))
        assertFalse(HomeTodoText.opensFlow(HomeActionGroup.ANALYSIS_IN_PROGRESS))
    }

    @Test fun carousel_caption_is_the_category_or_the_reason() {
        val named = TestItems.item("a", category = ItemCategory(id = "c", name = "헤드폰"))
        assertEquals(PreviewCaption.Text("헤드폰"), HomeTodoText.caption(named, HomeActionGroup.CLASSIFICATION_REVIEW))
        val noCategory = TestItems.item("b", url = "https://www.ohou.se/p/1", requiredAction = RequiredAction.CATEGORY_ASSIGNMENT)
        assertEquals(PreviewCaption.Res(R.string.home_preview_no_category), HomeTodoText.caption(noCategory, HomeActionGroup.INFORMATION_COMPLETION))
        val deleted = TestItems.item("c", requiredAction = RequiredAction.CATEGORY_REASSIGNMENT)
        assertEquals(PreviewCaption.Res(R.string.home_preview_category_deleted), HomeTodoText.caption(deleted, HomeActionGroup.INFORMATION_COMPLETION))
        val info = TestItems.item("d", url = "https://www.coupang.com/vp/1", requiredAction = RequiredAction.INFORMATION_COMPLETION)
        assertEquals(PreviewCaption.Text("coupang.com"), HomeTodoText.caption(info, HomeActionGroup.INFORMATION_COMPLETION))
        // A review card without a category name falls back to the host.
        assertEquals(PreviewCaption.Text("ohou.se"), HomeTodoText.caption(noCategory, HomeActionGroup.CLASSIFICATION_REVIEW))
    }
}
