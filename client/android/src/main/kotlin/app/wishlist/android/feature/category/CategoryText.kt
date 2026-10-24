package app.wishlist.android.feature.category

import androidx.annotation.StringRes
import app.wishlist.android.R
import app.wishlist.android.feature.home.HomeRowText
import app.wishlist.android.feature.home.ResText
import app.wishlist.shared.domain.CardMetaLine
import app.wishlist.shared.domain.PurposeLabel
import app.wishlist.shared.model.CategoryKind
import app.wishlist.shared.presentation.FieldError
import app.wishlist.shared.presentation.FormMessage

/** Maps the shared C5 values to the 문구 표 keys (pure; the shared code only classifies). */
object CategoryText {
    /** A form-level line. */
    @StringRes
    fun message(message: FormMessage): Int = when (message) {
        FormMessage.LIMIT_REACHED -> R.string.category_form_err_limit
        FormMessage.RATE_LIMITED -> R.string.category_form_err_rate
        FormMessage.SAVE_FAILED -> R.string.category_form_err_save
        FormMessage.CONFLICT_LOADED -> R.string.category_form_conflict_loaded
        FormMessage.CONFLICT_FAILED -> R.string.category_form_conflict_failed
        FormMessage.LOAD_FAILED -> R.string.detail_error_network
    }

    /** An input field's error line. */
    @StringRes
    fun fieldError(error: FieldError): Int = when (error) {
        FieldError.DUPLICATE -> R.string.category_form_err_duplicate
        FieldError.INVALID_CHARS -> R.string.category_form_err_chars
        FieldError.PARENT -> R.string.category_form_err_parent
    }

    /** The list header's second line format: " · 직접 만든 카테고리" around the parent name for a custom one; null = the parent name alone. */
    @StringRes
    fun listSubtitle(kind: CategoryKind): Int? = if (kind == CategoryKind.CUSTOM) R.string.category_list_custom_suffix else null

    /** The card's purpose part: null = the purpose's own name; otherwise "목적 미지정". */
    @StringRes
    fun purposePart(line: CardMetaLine): Int? = when (line.purpose) {
        is PurposeLabel.Named -> null
        PurposeLabel.None -> R.string.category_card_no_purpose
    }

    /** "N일 전 확인", or null without a check time (then the purpose part stands alone, no separator). */
    fun checkedPart(line: CardMetaLine): ResText? = line.checked?.let { ResText(R.string.category_card_checked, HomeRowText.time(it)) }
}
