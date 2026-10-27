@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package app.wishlist.shared.repository

import app.wishlist.shared.core.*
import app.wishlist.shared.data.fake.FakeHomeRepository
import app.wishlist.shared.data.fake.FakeStore
import app.wishlist.shared.data.fake.fakeTime
import app.wishlist.shared.data.fake.successValue
import app.wishlist.shared.data.remote.AuthTokenProvider
import app.wishlist.shared.data.remote.RemoteHomeRepository
import app.wishlist.shared.data.remote.ScriptedHomeServer
import app.wishlist.shared.data.remote.transportOf
import app.wishlist.shared.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The item states that land in (or stay out of) each home group. */
internal enum class HomeKind { REVIEW, PROCESSING, NAME_MISSING, CATEGORY_ASSIGNMENT, CATEGORY_REASSIGNMENT, DONE }

/** A stored item of [kind]; a larger [seq] is newer. The store applies the policy (requiredAction). */
internal fun homeItem(seq: Int, kind: HomeKind, purposeId: String? = null): WishlistItem {
    val at = fakeTime + seq.seconds
    val named = kind != HomeKind.NAME_MISSING && kind != HomeKind.PROCESSING
    val category = when (kind) {
        HomeKind.REVIEW, HomeKind.DONE -> ItemCategory("C026", ValueSource.AI)
        HomeKind.CATEGORY_ASSIGNMENT -> ItemCategory(source = ValueSource.AI, missingReason = CategoryMissingReason.AI_ABSTAINED)
        HomeKind.CATEGORY_REASSIGNMENT -> ItemCategory(source = ValueSource.USER, missingReason = CategoryMissingReason.CUSTOM_CATEGORY_DELETED)
        HomeKind.NAME_MISSING -> ItemCategory(missingReason = CategoryMissingReason.EXTRACTION_UNRESOLVED)
        HomeKind.PROCESSING -> ItemCategory()
    }
    return WishlistItem(
        id = Uuid.random().toString(), clientSubmissionId = Uuid.random().toString(), version = 1,
        sourceUrl = "https://shop.example/home/$seq", product = ProductSnapshot(name = if (named) "상품 $seq" else null),
        category = category, purpose = purposeId?.let { ItemPurpose(it, ValueSource.USER) } ?: ItemPurpose(),
        analysis = ItemAnalysis(when (kind) {
            HomeKind.PROCESSING -> AnalysisStatus.PROCESSING
            HomeKind.NAME_MISSING, HomeKind.CATEGORY_ASSIGNMENT -> AnalysisStatus.PARTIAL
            else -> AnalysisStatus.READY
        }),
        reviewStatus = when (kind) { HomeKind.REVIEW -> ReviewStatus.PENDING; HomeKind.DONE -> ReviewStatus.CONFIRMED; else -> ReviewStatus.NOT_REQUIRED },
        lifecycleStatus = LifecycleStatus.ACTIVE, requiredAction = RequiredAction.NONE, createdAt = at, updatedAt = at,
    )
}

/** One backend shared by every owner of a scenario; items are placed directly (no item API is under test). */
interface HomeContractFixture {
    suspend fun repositoryFor(ownerId: String): HomeRepository
    /** Puts [item] for [ownerId]; returns its id. */
    suspend fun place(ownerId: String, item: WishlistItem): String
    /** Deletes the item (it leaves every home group). */
    suspend fun remove(ownerId: String, id: String)
    /** Creates an empty ACTIVE purpose of [ownerId] at the current clock; returns its id. */
    suspend fun createPurpose(ownerId: String, name: String): String
    fun advanceSeconds(seconds: Long)
    /** Runs [call] while the client session switches to [to] before the answer is published. */
    suspend fun <T> switchingDuring(to: String, call: suspend () -> ClientResult<T>): ClientResult<T>
    suspend fun close() {}
}

/** HOME-01/02 behaviour, run on Fake and on Remote (MockEngine). This is wire handling, not a server check. */
abstract class HomeRepositoryContractTest {
    protected abstract fun createFixture(): HomeContractFixture

    private val ownerA = "00000000-0000-4000-8000-00000000000a"
    private val ownerB = "00000000-0000-4000-8000-00000000000b"
    private var seq = 0

    private suspend fun scenario(block: suspend (HomeContractFixture) -> Unit) {
        val fixture = createFixture()
        try { block(fixture) } finally { fixture.close() }
    }

    private suspend fun HomeContractFixture.placeAll(count: Int, kind: HomeKind, owner: String = ownerA): List<String> =
        (1..count).map { place(owner, homeItem(++seq, kind)) }

    @Test fun summaryFixedOrderWithEmptyGroups() = runTest {
        scenario { f ->
            val repo = f.repositoryFor(ownerA)
            val empty = repo.summary().successValue()
            assertEquals(HomeSummary(HomeActionGroup.entries.map { HomeGroupSummary(it, 0, emptyList()) }, emptyList()), empty)
            f.placeAll(1, HomeKind.REVIEW)
            f.placeAll(2, HomeKind.DONE)
            val one = repo.summary().successValue()
            assertEquals(HomeActionGroup.entries, one.groups.map { it.group })
            assertEquals(listOf(0, 0, 1), one.groups.map { it.count })
        }
    }

    @Test fun summaryAtMostFourPreviewsNewestFirst() = runTest {
        scenario { f ->
            val newest = f.placeAll(6, HomeKind.REVIEW).reversed()
            f.placeAll(3, HomeKind.REVIEW, owner = ownerB)
            val deleted = f.placeAll(1, HomeKind.REVIEW).single()
            f.remove(ownerA, deleted)
            val review = f.repositoryFor(ownerA).summary().successValue().groups.single { it.group == HomeActionGroup.CLASSIFICATION_REVIEW }
            assertEquals(6, review.count)
            assertEquals(newest.take(4), review.previews.map { it.item.id })
            assertTrue(review.previews.all { it.anchorCursor.isNotEmpty() })
        }
    }

    @Test fun summaryRecentPurposesAtMostThreeIncludesEmpty() = runTest {
        scenario { f ->
            val purposes = (1..4).map { f.advanceSeconds(1); f.createPurpose(ownerA, "목적 $it") }
            f.advanceSeconds(1)
            val candidate = f.place(ownerA, homeItem(++seq, HomeKind.DONE, purposeId = purposes[0]))
            val recent = f.repositoryFor(ownerA).summary().successValue().recentPurposes
            // Activity order: the candidate moved the first purpose to the front; the empty ones stay.
            assertEquals(listOf(purposes[0], purposes[3], purposes[2]), recent.map { it.id })
            assertEquals(listOf(1, 0, 0), recent.map { it.candidateCount })
            assertEquals(listOf(candidate), recent[0].previews.map { it.itemId })
            assertEquals("coral" to "heart", recent[0].colorKey to recent[0].iconKey)
        }
    }

    @Test fun previewAnchorOpensGroupWindow() = runTest {
        scenario { f ->
            val newest = f.placeAll(10, HomeKind.REVIEW).reversed()
            val repo = f.repositoryFor(ownerA)
            val second = repo.summary().successValue().groups.last().previews[1]
            val window = repo.groupAround(HomeActionGroup.CLASSIFICATION_REVIEW, second.anchorCursor).successValue()
            assertEquals(newest, window.items.map { it.id })
            assertEquals(ItemAnchor(newest[1], newest[1], true), window.anchor)
            assertEquals(10, window.totalCount)
            val fromThere = repo.groupAround(HomeActionGroup.CLASSIFICATION_REVIEW, second.anchorCursor, before = 0).successValue()
            assertEquals(newest.drop(1), fromThere.items.map { it.id })
            assertNotNull(fromThere.previousCursor)
            assertNull(fromThere.nextCursor)
        }
    }

    @Test fun informationGroupIncludesCategoryAssignmentAndReassignment() = runTest {
        scenario { f ->
            val name = f.placeAll(1, HomeKind.NAME_MISSING).single()
            val assignment = f.placeAll(1, HomeKind.CATEGORY_ASSIGNMENT).single()
            val reassignment = f.placeAll(1, HomeKind.CATEGORY_REASSIGNMENT).single()
            f.placeAll(1, HomeKind.REVIEW)
            val processing = f.placeAll(1, HomeKind.PROCESSING).single()
            val repo = f.repositoryFor(ownerA)
            val summary = repo.summary().successValue()
            val info = summary.groups.single { it.group == HomeActionGroup.INFORMATION_COMPLETION }
            assertEquals(3, info.count)
            assertEquals(listOf(reassignment, assignment, name), info.previews.map { it.item.id })
            // Each card keeps its own action.
            assertEquals(listOf(RequiredAction.CATEGORY_REASSIGNMENT, RequiredAction.CATEGORY_ASSIGNMENT, RequiredAction.INFORMATION_COMPLETION),
                info.previews.map { it.item.requiredAction })
            assertEquals(listOf(processing), summary.groups.first().previews.map { it.item.id })
            val page = repo.group(HomeActionGroup.INFORMATION_COMPLETION).successValue()
            assertEquals(listOf(reassignment, assignment, name), page.items.map { it.id })
            assertEquals(3, page.totalCount)
        }
    }

    @Test fun groupPagesNewestFirst() = runTest {
        scenario { f ->
            val newest = f.placeAll(25, HomeKind.REVIEW).reversed()
            f.placeAll(2, HomeKind.NAME_MISSING)
            val repo = f.repositoryFor(ownerA)
            val first = repo.group(HomeActionGroup.CLASSIFICATION_REVIEW).successValue()
            assertEquals(newest.take(20), first.items.map { it.id })
            assertEquals(25, first.totalCount)
            assertNull(first.previousCursor)
            assertNull(first.anchor)
            assertEquals(newest.take(20).toSet(), first.anchorCursors.keys)
            val second = repo.group(HomeActionGroup.CLASSIFICATION_REVIEW, assertNotNull(first.nextCursor)).successValue()
            assertEquals(newest.drop(20), second.items.map { it.id })
            assertNull(second.nextCursor)
        }
    }

    @Test fun groupAroundRemovedAnchorFallsBack() = runTest {
        scenario { f ->
            val newest = f.placeAll(5, HomeKind.REVIEW).reversed()
            val repo = f.repositoryFor(ownerA)
            val anchors = repo.group(HomeActionGroup.CLASSIFICATION_REVIEW).successValue().anchorCursors
            f.remove(ownerA, newest[2])
            val fallback = repo.groupAround(HomeActionGroup.CLASSIFICATION_REVIEW, anchors.getValue(newest[2]), before = 1, after = 1).successValue()
            assertEquals(ItemAnchor(newest[2], newest[3], false), fallback.anchor)
            assertEquals(listOf(newest[1], newest[3], newest[4]), fallback.items.map { it.id })
            assertEquals(4, fallback.totalCount)
            newest.filter { it != newest[2] }.forEach { f.remove(ownerA, it) }
            val empty = repo.groupAround(HomeActionGroup.CLASSIFICATION_REVIEW, anchors.getValue(newest[2])).successValue()
            assertEquals(ItemAnchor(newest[2], null, false), empty.anchor)
            assertEquals(ItemPage(emptyList(), 0, null, anchor = ItemAnchor(newest[2], null, false)), empty)
        }
    }

    @Test fun invalidGroupCursorRejected() = runTest {
        scenario { f ->
            f.placeAll(2, HomeKind.NAME_MISSING)
            f.placeAll(2, HomeKind.REVIEW)
            val repo = f.repositoryFor(ownerA)
            val infoCursor = assertNotNull(repo.group(HomeActionGroup.INFORMATION_COMPLETION, limit = 1).successValue().nextCursor)
            // Another group's cursor and garbage are cursor errors; out-of-range bounds are query errors.
            for (cursor in listOf(infoCursor, "garbage")) {
                val error = repo.group(HomeActionGroup.CLASSIFICATION_REVIEW, cursor).failed()
                assertEquals(ErrorKind.VALIDATION to "INVALID_HOME_CURSOR", error.kind to error.code, cursor)
            }
            assertEquals("INVALID_HOME_CURSOR", repo.groupAround(HomeActionGroup.CLASSIFICATION_REVIEW, infoCursor).failed().code)
            assertEquals("INVALID_HOME_QUERY", repo.groupAround(HomeActionGroup.CLASSIFICATION_REVIEW, infoCursor, before = 21).failed().code)
            assertEquals("INVALID_HOME_QUERY", repo.group(HomeActionGroup.CLASSIFICATION_REVIEW, limit = 0).failed().code)
        }
    }

    @Test fun staleSessionNotPublished() = runTest {
        scenario { f ->
            f.placeAll(3, HomeKind.REVIEW)
            val repo = f.repositoryFor(ownerA)
            val anchor = repo.group(HomeActionGroup.CLASSIFICATION_REVIEW).successValue().anchorCursors.values.first()
            assertEquals(ErrorKind.SESSION_CHANGED, f.switchingDuring(ownerB) { repo.summary() }.failed().kind)
            f.repositoryFor(ownerA)
            assertEquals(ErrorKind.SESSION_CHANGED, f.switchingDuring(ownerB) { repo.group(HomeActionGroup.CLASSIFICATION_REVIEW) }.failed().kind)
            f.repositoryFor(ownerA)
            assertEquals(ErrorKind.SESSION_CHANGED, f.switchingDuring(ownerB) { repo.groupAround(HomeActionGroup.CLASSIFICATION_REVIEW, anchor) }.failed().kind)
        }
    }
}

/** The Fake store behind [FakeHomeRepository], with a movable clock and owner switching. */
private class FakeHomeFixture : HomeContractFixture {
    var now: Instant = fakeTime
    val session = MutableAuthSession()
    val store = FakeStore(session, Clock { now }, IdGenerator { Uuid.random().toString() })
    val repo = FakeHomeRepository(store)

    override suspend fun repositoryFor(ownerId: String): HomeRepository {
        if (session.state.value.accountId != ownerId) session.changeAccount(ownerId)
        return repo
    }

    private suspend fun <T> asOwner(ownerId: String, block: suspend () -> T): T {
        val previous = session.state.value.accountId
        if (previous != ownerId) session.changeAccount(ownerId)
        return block().also { if (previous != null && previous != ownerId) session.changeAccount(previous) }
    }

    override suspend fun place(ownerId: String, item: WishlistItem) = asOwner(ownerId) { store.putItem(item).successValue().id }
    override suspend fun remove(ownerId: String, id: String) = asOwner(ownerId) { store.delete(id).successValue() }
    override suspend fun createPurpose(ownerId: String, name: String) = asOwner(ownerId) {
        store.createPurpose(PurposeCommand(name, null, "coral", "heart"), Uuid.random().toString()).successValue().purpose.id
    }
    override fun advanceSeconds(seconds: Long) { now += seconds.seconds }

    // The store waits before its session gate; the switch lands during that wait.
    override suspend fun <T> switchingDuring(to: String, call: suspend () -> ClientResult<T>): ClientResult<T> = coroutineScope {
        store.delayNext(ApiId.HOME_01, 100)
        store.delayNext(ApiId.HOME_02, 100)
        val pending = async { call() }
        delay(50)
        session.changeAccount(to)
        pending.await().also { store.delayNext(ApiId.HOME_01, 0); store.delayNext(ApiId.HOME_02, 0) }
    }
}

/** The client session plus a Remote stack whose MockEngine serves [ScriptedHomeServer]. */
private class RemoteHomeFixture : HomeContractFixture {
    var now: Instant = fakeTime
    val server = ScriptedHomeServer(Clock { now })
    val session = MutableAuthSession()
    private val tokens = object : AuthTokenProvider {
        override suspend fun getToken(snapshot: SessionSnapshot, forceRefresh: Boolean): ClientResult<String> =
            ClientResult.Success("token-${snapshot.accountId}")
    }
    val repo = RemoteHomeRepository(session, transportOf(session, tokens, server.engine))

    override suspend fun repositoryFor(ownerId: String): HomeRepository {
        if (session.state.value.accountId != ownerId) session.changeAccount(ownerId)
        return repo
    }

    override suspend fun place(ownerId: String, item: WishlistItem) = server.asOwner(ownerId) { server.store.putItem(item).successValue().id }
    override suspend fun remove(ownerId: String, id: String) = server.asOwner(ownerId) { server.store.delete(id).successValue() }
    override suspend fun createPurpose(ownerId: String, name: String) = server.asOwner(ownerId) {
        server.store.createPurpose(PurposeCommand(name, null, "coral", "heart"), Uuid.random().toString()).successValue().purpose.id
    }
    override fun advanceSeconds(seconds: Long) { now += seconds.seconds }

    override suspend fun <T> switchingDuring(to: String, call: suspend () -> ClientResult<T>): ClientResult<T> {
        server.beforeRespond = { session.changeAccount(to) }
        return call()
    }

    override suspend fun close() = server.engine.close()
}

class FakeHomeRepositoryContractTest : HomeRepositoryContractTest() {
    override fun createFixture(): HomeContractFixture = FakeHomeFixture()
}

class RemoteHomeRepositoryContractTest : HomeRepositoryContractTest() {
    override fun createFixture(): HomeContractFixture = RemoteHomeFixture()
}

class RemoteHomeRepositoryTest {
    @Test fun requestsUseTheHomePathsAndNeverMixAnchorWithCursor() = runTest {
        val f = RemoteHomeFixture()
        val repo = f.repositoryFor("00000000-0000-4000-8000-00000000000a")
        repo.summary().successValue()
        repo.group(HomeActionGroup.INFORMATION_COMPLETION).successValue()
        repo.group(HomeActionGroup.INFORMATION_COMPLETION, cursor = "c/1?#", limit = 5)
        repo.groupAround(HomeActionGroup.CLASSIFICATION_REVIEW, "tok", before = 0, after = 5)
        val (summary, first, next, around) = f.server.engine.requestHistory
        assertEquals("/v1/home", summary.url.encodedPath)
        assertEquals("", summary.url.encodedQuery)
        assertEquals("/v1/home/action-items", first.url.encodedPath)
        assertEquals("group=INFORMATION_COMPLETION&limit=20", first.url.encodedQuery)
        assertEquals("c/1?#", next.url.parameters["cursor"])
        assertEquals("5", next.url.parameters["limit"])
        assertEquals("group=CLASSIFICATION_REVIEW&anchor=tok&before=0&after=5", around.url.encodedQuery)
        f.close()
    }
}
