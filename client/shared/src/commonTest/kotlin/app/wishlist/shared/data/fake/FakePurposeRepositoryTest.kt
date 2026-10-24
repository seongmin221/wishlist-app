@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package app.wishlist.shared.data.fake

import app.wishlist.shared.core.*
import app.wishlist.shared.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.uuid.Uuid

internal class FakePurposeRepositoryTest : PurposeRepositoryContractTest() {
    @Test fun delayedSnapshotCannotWriteAnotherAccount() = assertDelayedFakeSnapshot()
    override fun repository(session: AuthSession, store: FakeStore): app.wishlist.shared.repository.PurposeRepository = FakePurposeRepository(store)
    override fun lists(session: AuthSession, store: FakeStore): app.wishlist.shared.repository.ItemListRepository = FakeItemListRepository(store)
}

internal abstract class PurposeRepositoryContractTest {
    protected val session = MutableAuthSession()
    @BeforeTest fun signIn() = kotlinx.coroutines.test.runTest { session.changeAccount("A") }
    private var now = fakeTime
    protected val store = FakeStore(session, Clock { now }, IdGenerator { Uuid.random().toString() })
    protected abstract fun repository(session: AuthSession, store: FakeStore): app.wishlist.shared.repository.PurposeRepository
    protected abstract fun lists(session: AuthSession, store: FakeStore): app.wishlist.shared.repository.ItemListRepository
    private val repo by lazy { repository(session, store) }
    private val items by lazy { lists(session, store) }
    private fun key() = Uuid.random().toString()
    private val command = PurposeCommand("  출퇴근 😀  ", "줄1\r\n줄2", "coral", "music")

    @Test fun createReplayReadsCurrentAndChecksPayloadBeforeLimits() = runTest {
        val key = key()
        val first = repo.create(command, key).successValue()
        assertEquals(1, first.activeCount)
        assertEquals(30, first.purposeLimit)
        assertEquals(0, first.purpose.candidateCount)
        assertEquals(1, first.purpose.membershipVersion)
        assertEquals(setOf("EDIT", "DELETE", "ADD_CANDIDATES"), first.purpose.allowedActions)
        val edited = repo.update(first.purpose.id, 1, PurposePatch(name = "편집")).successValue()
        assertEquals(edited, repo.create(command, key).successValue().purpose)
        assertEquals("IDEMPOTENCY_KEY_REUSED", repo.create(command.copy(name = "다름"), key).error().code)
        assertEquals(command.description, edited.description)
        assertEquals(command.colorKey, edited.colorKey)
        assertEquals("편집", repo.summary().successValue().purposes.single().name)
    }

    @Test fun casNoopAndEditPreserveActivityOrderAndMembership() = runTest {
        val a = repo.create(command, key()).successValue().purpose
        now += 1.seconds
        val b = repo.create(command, key()).successValue().purpose
        val changed = repo.update(a.id, 1, PurposePatch(description = DescriptionChange.Set(null), iconKey = "book")).successValue()
        assertEquals(2, changed.version)
        assertNull(changed.description)
        assertEquals(a.activity, changed.activity)
        assertEquals(a.membershipVersion, changed.membershipVersion)
        assertEquals(listOf(b.id, a.id), repo.summary().successValue().purposes.map { it.id })
        assertEquals(changed, repo.update(a.id, 2, PurposePatch(name = changed.name)).successValue())
        val error = repo.update(a.id, 1, PurposePatch(name = "다름")).error()
        assertEquals("PURPOSE_VERSION_CONFLICT", error.code)
        assertEquals(2, error.currentVersion)
    }

    @Test fun receiptRateAndThirtyPurposeLimit() = runTest {
        val receiptKey = key()
        repo.create(command, receiptKey).successValue()
        repeat(9) { repo.create(command, key()).successValue() }
        now += 23.seconds
        val rate = repo.create(command, key()).error()
        assertEquals("PURPOSE_CREATE_RATE_LIMITED", rate.code)
        assertEquals(37L, rate.retryAfterSeconds)
        repo.create(command, receiptKey).successValue()
        now += 37.seconds
        repeat(20) { i -> if (i == 10) now += 60.seconds; repo.create(command, key()).successValue() }
        now += 60.seconds
        assertEquals("PURPOSE_LIMIT_REACHED", repo.create(command, key()).error().code)
        assertEquals(30, repo.summary().successValue().purposes.size)
        repo.create(command, receiptKey).successValue()
    }

    @Test fun membershipIncludesUnnamedActiveItemsAndFreshPurposeDisplay() = runTest {
        val p = repo.create(command, key()).successValue().purpose
        val active = contractItem(1, "C024", null, LifecycleStatus.ACTIVE).copy(purpose = ItemPurpose(p.id, ValueSource.USER))
        store.putItem(active).successValue()
        store.putItem(contractItem(2, "C024", "끝", LifecycleStatus.DELETED).copy(purpose = ItemPurpose(p.id, ValueSource.USER))).successValue()
        store.putItem(contractItem(3, "C024", "다른", LifecycleStatus.ACTIVE)).successValue()
        assertEquals(1, repo.detail(p.id).successValue().candidateCount)
        assertTrue("ARCHIVE" in repo.detail(p.id).successValue().allowedActions)
        val summary = repo.summary().successValue().purposes.single()
        assertEquals(listOf(active.id), summary.previews.map { it.itemId })
        assertEquals("CANDIDATE_ADDED", summary.activity.kind)
        val page = items.byPurpose(p.id, limit = 1).successValue()
        assertEquals(1, page.totalCount)
        assertEquals(listOf(active.id), page.items.map { it.id })
        repo.update(p.id, 1, PurposePatch(name = "이름", colorKey = "mint")).successValue()
        val displayed = items.byPurpose(p.id).successValue().items.single()
        assertEquals("이름", displayed.purpose.name)
        assertEquals("MINT", displayed.purpose.colorKey)
        assertEquals(active.version, displayed.version)
        store.delete(active.id).successValue()
        assertEquals(summary.activity, repo.detail(p.id).successValue().activity)
        assertEquals(0, repo.detail(p.id).successValue().candidateCount)
    }

    @Test fun validationAndOwnerIsolation() = runTest {
        val p = repo.create(command, key()).successValue().purpose
        assertEquals("INVALID_PURPOSE_INPUT", repo.create(command.copy(colorKey = "CORAL"), key()).error().code)
        assertEquals(setOf("name"), repo.create(command.copy(name = "\u200b"), key()).error().fields)
        assertEquals("INVALID_PURPOSE_INPUT", repo.update(p.id, 1, PurposePatch()).error().code)
        session.changeAccount("B")
        assertEquals(0, repo.summary().successValue().activeCount)
        assertEquals("PURPOSE_NOT_FOUND", repo.detail(p.id).error().code)
        session.changeAccount(null)
        assertEquals(ErrorKind.UNAUTHENTICATED, repo.summary().error().kind)
    }

    @Test fun retryAfterRoundsUpTheFullFractionalSecond() = runTest {
        repeat(10) { repo.create(command, key()).successValue() }
        now += 59.seconds - 1.nanoseconds
        assertEquals(2L, repo.create(command, key()).error().retryAfterSeconds)
    }

    @Test fun purposePagesUseScopedKeysetAndIncludeEveryActiveCandidate() = runTest {
        val p = repo.create(command, key()).successValue().purpose
        val other = repo.create(command, key()).successValue().purpose
        repeat(43) { seq -> store.putItem(contractItem(seq, "C024", if (seq % 2 == 0) null else "상품", LifecycleStatus.ACTIVE)
            .copy(purpose = ItemPurpose(p.id, ValueSource.USER))).successValue() }
        val first = items.byPurpose(p.id).successValue()
        assertEquals(43, first.totalCount)
        assertEquals(40, first.items.size)
        val cursor = assertNotNull(first.nextCursor)
        val second = items.byPurpose(p.id, cursor).successValue()
        assertEquals(3, second.items.size)
        assertNull(second.nextCursor)
        assertEquals(43, (first.items + second.items).map { it.id }.toSet().size)
        assertEquals("INVALID_WISHLIST_ITEM_CURSOR", items.byPurpose(other.id, cursor).error().code)
        assertEquals(first.items.take(4).map { it.id }, repo.summary().successValue().purposes.first { it.id == p.id }.previews.map { it.itemId })
        assertEquals(44, repo.detail(p.id).successValue().membershipVersion)
    }

    protected fun assertDelayedFakeSnapshot() = runTest {
        store.delayNext(ApiId.PUR_02, 100)
        val pending = async { repo.create(command, key()) }
        runCurrent()
        session.changeAccount("B")
        assertEquals(ErrorKind.SESSION_CHANGED, pending.await().error().kind)
        assertEquals(0, repo.summary().successValue().activeCount)
        session.changeAccount("A")
        assertEquals(0, repo.summary().successValue().activeCount)
    }
}
