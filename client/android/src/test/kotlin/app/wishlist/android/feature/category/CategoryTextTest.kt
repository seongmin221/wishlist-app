package app.wishlist.android.feature.category

import app.wishlist.android.R
import app.wishlist.android.feature.home.HomeRowText
import app.wishlist.android.feature.home.ResText
import app.wishlist.shared.domain.CardMetaLine
import app.wishlist.shared.domain.PurposeLabel
import app.wishlist.shared.domain.RelativeTime
import app.wishlist.shared.model.CategoryKind
import app.wishlist.shared.presentation.FieldError
import app.wishlist.shared.presentation.FormMessage
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Every C5 value maps to its 문구 표 key, and every `category_*` key exists in ko and en. */
class CategoryTextTest {
    @Test fun everyFormMessageAndFieldErrorHasItsLine() {
        val messages = mapOf(
            FormMessage.LIMIT_REACHED to R.string.category_form_err_limit,
            FormMessage.RATE_LIMITED to R.string.category_form_err_rate,
            FormMessage.SAVE_FAILED to R.string.category_form_err_save,
            FormMessage.CONFLICT_LOADED to R.string.category_form_conflict_loaded,
            FormMessage.CONFLICT_FAILED to R.string.category_form_conflict_failed,
            FormMessage.LOAD_FAILED to R.string.detail_error_network,
        )
        assertEquals(FormMessage.entries.toSet(), messages.keys)
        messages.forEach { (m, res) -> assertEquals(m.name, res, CategoryText.message(m)) }
        val fields = mapOf(
            FieldError.DUPLICATE to R.string.category_form_err_duplicate,
            FieldError.INVALID_CHARS to R.string.category_form_err_chars,
            FieldError.PARENT to R.string.category_form_err_parent,
        )
        assertEquals(FieldError.entries.toSet(), fields.keys)
        fields.forEach { (f, res) -> assertEquals(f.name, res, CategoryText.fieldError(f)) }
    }

    @Test fun cardLineParts() {
        val named = CardMetaLine(PurposeLabel.Named("출퇴근 헤드폰", "CORAL"), RelativeTime.Weeks(1))
        assertNull(CategoryText.purposePart(named))
        assertEquals(ResText(R.string.category_card_checked, HomeRowText.time(RelativeTime.Weeks(1))), CategoryText.checkedPart(named))
        val bare = CardMetaLine(PurposeLabel.None, null)
        assertEquals(R.string.category_card_no_purpose, CategoryText.purposePart(bare))
        assertNull(CategoryText.checkedPart(bare)) // no check time: no " · …확인" part
        assertEquals(R.string.category_list_custom_suffix, CategoryText.listSubtitle(CategoryKind.CUSTOM))
        assertNull(CategoryText.listSubtitle(CategoryKind.PUBLIC))
        assertNull(CategoryText.listSubtitle(CategoryKind.UNKNOWN))
    }

    @Test fun categoryKeysExistInBothLanguages() {
        fun keys(folder: String): Set<String> =
            Regex("<string name=\"(category_[a-z_]+)\"").findAll(File("src/main/res/$folder/strings.xml").readText()).map { it.groupValues[1] }.toSet()
        val ko = keys("values")
        assertEquals(56, ko.size)
        assertEquals(ko, keys("values-en"))
    }
}
