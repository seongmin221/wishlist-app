package app.wishlist.shared.data.fake

import app.wishlist.shared.core.Clock
import app.wishlist.shared.core.IdGenerator
import app.wishlist.shared.core.MutableAuthSession
import app.wishlist.shared.model.AnalysisStatus
import app.wishlist.shared.model.WishlistItem
import app.wishlist.shared.repository.CreateItemCommand
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val ACCOUNT_A = "fake-google-0001"
private const val ACCOUNT_B = "fake-apple-0001"

private class DriverHarness {
    val session = MutableAuthSession()
    var now: Instant = Instant.parse("2026-10-07T00:00:00Z")
    val clock = Clock { now }
    private var count = 0
    val ids = IdGenerator { "00000000-0000-4000-8000-" + (++count).toString().padStart(12, '0') }
    val store = FakeStore(session, clock, ids)
    val driver = DebugAnalysisDriver(store, clock)
    val firstLeaf: String = BoardSeeds.create(clock, ids).categories.first { it.parentId != null }.id

    suspend fun signIn(account: String) {
        session.changeAccount(account)
        store.seed(BoardSeeds.create(clock, ids)).successValue()
    }

    suspend fun share(url: String = "https://www.Musinsa.com/p/1"): WishlistItem =
        store.create(CreateItemCommand(ids.newId(), url, now)).successValue()

    suspend fun status(item: WishlistItem): AnalysisStatus = store.get(item.id).successValue().analysis.status
}

private fun runDriverTest(block: suspend (DriverHarness) -> Unit): TestResult = runTest { block(DriverHarness()) }

class DebugAnalysisDriverTest {
    @Test fun processingYoungerThanFiveSecondsIsLeftAlone() = runDriverTest { h ->
        h.signIn(ACCOUNT_A)
        val item = h.share()
        h.now += 4.seconds
        assertEquals(0, h.driver.advance().successValue())
        assertEquals(AnalysisStatus.PROCESSING, h.status(item))
    }

    @Test fun processingFiveSecondsOldBecomesReadyWithHostNameAndFirstSeedLeaf() = runDriverTest { h ->
        h.signIn(ACCOUNT_A)
        val item = h.share()
        h.now += 5.seconds
        assertEquals(1, h.driver.advance().successValue())
        val done = h.store.get(item.id).successValue()
        assertEquals(AnalysisStatus.READY, done.analysis.status)
        assertEquals("musinsa.com", done.product.name)
        assertEquals(h.firstLeaf, done.category.id)
        // Explicit control, no timer: a second pass finds nothing left to complete.
        assertEquals(0, h.driver.advance().successValue())
    }

    @Test fun deletedItemsAreNotTouched() = runDriverTest { h ->
        h.signIn(ACCOUNT_A)
        val item = h.share()
        h.store.delete(item.id).successValue()
        h.now += 10.seconds
        assertEquals(0, h.driver.advance().successValue())
    }

    @Test fun otherAccountsItemsAreNotTouched() = runDriverTest { h ->
        h.signIn(ACCOUNT_A)
        val item = h.share()
        h.signIn(ACCOUNT_B)
        h.now += 10.seconds
        assertEquals(0, h.driver.advance().successValue())
        h.session.changeAccount(ACCOUNT_A)
        assertEquals(AnalysisStatus.PROCESSING, h.status(item))
    }
}
