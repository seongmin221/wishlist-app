package app.wishlist.shared.domain

import app.wishlist.shared.model.ReviewStatus
import app.wishlist.shared.model.WishlistItem
import kotlin.time.Instant

/** The purpose part of a list card's last line (C5 D8): a named purpose with its wire color key, or "no purpose". */
sealed interface PurposeLabel {
    data class Named(val name: String, val colorKey: String?) : PurposeLabel
    data object None : PurposeLabel
}

/**
 * A list card's last line: purpose · "checked N ago". [checked] is null when the item has no
 * `metadataCheckedAt`; the platform then shows the purpose part alone without the separator.
 * Creation or classification time is never used instead (server read contract).
 */
data class CardMetaLine(val purpose: PurposeLabel, val checked: RelativeTime?)

fun cardMetaLine(item: WishlistItem, now: Instant, utcOffsetSeconds: (Instant) -> Int): CardMetaLine {
    val name = item.purpose.name?.takeIf { item.purpose.id != null && it.isNotBlank() }
    val purpose = if (name != null) PurposeLabel.Named(name, item.purpose.colorKey) else PurposeLabel.None
    val checked = item.product.metadataCheckedAt?.let { DisplayFormat.relative(it, now, utcOffsetSeconds) }
    return CardMetaLine(purpose, checked)
}

/** D13: the ✳ "category and purpose not confirmed" mark for pending and deferred reviews. */
fun isUnconfirmed(item: WishlistItem): Boolean =
    item.reviewStatus == ReviewStatus.PENDING || item.reviewStatus == ReviewStatus.DEFERRED
