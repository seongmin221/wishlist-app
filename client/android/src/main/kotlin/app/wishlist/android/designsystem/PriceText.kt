package app.wishlist.android.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import app.wishlist.shared.domain.PriceFormatter

private val priceFormatter = PriceFormatter()

/** Pass ProductSnapshot.price?.canonical directly; shared domain owns parsing and display. */
fun formatPrice(amountText: String?, currency: String?): String? =
    priceFormatter.formatOrNull(amountText, currency)

/** 가격(Plex 700 tabular). 표시 가능한 공통 결과가 없으면 뷰를 만들지 않는다. */
@Composable
fun PriceText(
    amountText: String?,
    currency: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = WLType.price,
    color: Color = LocalWLColors.current.text,
) {
    val text = formatPrice(amountText, currency) ?: return
    WLText(text, style, modifier, color)
}
