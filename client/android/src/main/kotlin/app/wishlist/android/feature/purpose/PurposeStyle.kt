package app.wishlist.android.feature.purpose

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.wishlist.android.designsystem.*

/** Domain keys have already passed the explicit wire mapping; unknown values stay neutral. */
@Composable
internal fun purposeColor(key: String): Color = when (key) {
    "coral" -> WishlistTokens.Purpose.coral
    "mustard" -> WishlistTokens.Purpose.mustard
    "periwinkle" -> WishlistTokens.Purpose.periwinkle
    "cyan" -> WishlistTokens.Purpose.cyan
    "mint" -> WishlistTokens.Purpose.mint
    "pink" -> WishlistTokens.Purpose.pink
    else -> LocalWLColors.current.card
}

internal fun purposeIcon(key: String): WLLineIcon = when (key) {
    "heart" -> WLLineIcon.Heart
    "home" -> WLLineIcon.Home
    "plane" -> WLLineIcon.Plane
    "gift" -> WLLineIcon.Gift
    "tent" -> WLLineIcon.Tent
    "music" -> WLLineIcon.Music
    "star" -> WLLineIcon.Star
    "book" -> WLLineIcon.Book
    else -> WLLineIcon.Purpose
}

internal fun purposePaletteColor(key: String): WLPurposeColor? = when (key) {
    "coral" -> WLPurposeColor.Coral
    "mustard" -> WLPurposeColor.Mustard
    "periwinkle" -> WLPurposeColor.Periwinkle
    "cyan" -> WLPurposeColor.Cyan
    "mint" -> WLPurposeColor.Mint
    "pink" -> WLPurposeColor.Pink
    else -> null
}
