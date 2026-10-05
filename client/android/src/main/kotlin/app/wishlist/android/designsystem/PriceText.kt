package app.wishlist.android.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * "KRW 549,000", "USD 299", "USD 19.99". 로케일과 무관하게 ','로 묶는다. 소수는 0이 아닐 때만 두 자리, KRW는 소수 없음.
 * (C2에서 KMP domain으로 옮긴다. 지금은 컴포넌트 미리보기용.)
 */
fun formatPrice(amount: BigDecimal, currency: String): String {
    val scale = if (currency == "KRW") 0 else 2
    val v = amount.setScale(scale, RoundingMode.HALF_UP)
    val showFraction = scale > 0 && v.stripTrailingZeros().scale() > 0
    val plain = v.abs().toPlainString()
    val whole = plain.substringBefore('.')
    val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
    val sign = if (v.signum() < 0) "-" else ""
    val fraction = if (showFraction) "." + plain.substringAfter('.') else ""
    return "$currency $sign$grouped$fraction"
}

/** 가격(Plex 700 tabular). 기본 `WLType.price`. */
@Composable
fun PriceText(
    amount: BigDecimal,
    currency: String,
    modifier: Modifier = Modifier,
    style: TextStyle = WLType.price,
    color: Color = LocalWLColors.current.text,
) {
    WLText(formatPrice(amount, currency), style, modifier, color)
}
