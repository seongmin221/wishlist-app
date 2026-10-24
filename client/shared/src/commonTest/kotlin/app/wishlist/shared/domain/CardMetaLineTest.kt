package app.wishlist.shared.domain

import app.wishlist.shared.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class CardMetaLineTest {
    private val kst: (Instant) -> Int = { 9 * 3600 }
    private val now = fixtureTime + 10.days

    private fun item(purpose: ItemPurpose = ItemPurpose(), checked: Instant? = null, review: ReviewStatus = ReviewStatus.CONFIRMED) =
        itemFixture(review = review).let { it.copy(purpose = purpose, product = it.product.copy(metadataCheckedAt = checked)) }

    private val commute = ItemPurpose("p1", ValueSource.USER, "출퇴근 헤드폰", "CORAL", "HEART")

    @Test fun purposeAndCheckedTime() {
        val line = cardMetaLine(item(commute, now - 2.days), now, kst)
        assertEquals(PurposeLabel.Named("출퇴근 헤드폰", "CORAL"), line.purpose)
        assertEquals(RelativeTime.Days(2), line.checked)
    }

    @Test fun noPurposeKeepsTheLabel() {
        val line = cardMetaLine(item(checked = now - 7.days), now, kst)
        assertEquals(PurposeLabel.None, line.purpose)
        assertEquals(RelativeTime.Weeks(1), line.checked)
    }

    @Test fun missingCheckedTimeDropsTheTimePart() {
        assertNull(cardMetaLine(item(commute), now, kst).checked)
        val bare = cardMetaLine(item(), now, kst)
        assertEquals(PurposeLabel.None, bare.purpose)
        assertNull(bare.checked)
    }

    @Test fun linkedPurposeWithoutAName() {
        // An older response may link a purpose without display values: it reads as no purpose, never a blank name.
        assertEquals(PurposeLabel.None, cardMetaLine(item(ItemPurpose("p1", ValueSource.AI, name = " ")), now, kst).purpose)
        assertEquals(PurposeLabel.None, cardMetaLine(item(ItemPurpose("p1", ValueSource.AI)), now, kst).purpose)
    }

    @Test fun pendingAndDeferredAreUnconfirmed() {
        assertTrue(isUnconfirmed(item(review = ReviewStatus.PENDING)))
        assertTrue(isUnconfirmed(item(review = ReviewStatus.DEFERRED)))
        assertFalse(isUnconfirmed(item(review = ReviewStatus.CONFIRMED)))
        assertFalse(isUnconfirmed(item(review = ReviewStatus.NOT_REQUIRED)))
    }
}
