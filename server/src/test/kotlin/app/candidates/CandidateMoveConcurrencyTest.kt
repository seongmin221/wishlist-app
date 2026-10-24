package app.candidates

import app.common.FieldChange
import app.testutil.*
import app.wishlist.ItemChanges
import app.wishlist.ItemMutationException
import app.wishlist.ItemMutationService
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.*

class CandidateMoveConcurrencyTest {
    private fun <A, B> race(left: () -> A, right: () -> B): Pair<Result<A>, Result<B>> {
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit(Callable { barrier.await(10, TimeUnit.SECONDS); runCatching(left) })
            val b = pool.submit(Callable { barrier.await(10, TimeUnit.SECONDS); runCatching(right) })
            return a.get(30, TimeUnit.SECONDS) to b.get(30, TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
    }

    private fun Result<*>.code(): String = when (val e = exceptionOrNull()) {
        null -> "OK"
        is CandidateException -> e.code
        is ItemMutationException -> e.code
        else -> throw e
    }

    /** Waits until another session blocks on a row lock; polls the server rather than sleeping a fixed time. */
    private fun awaitLockWaiter(source: DataSource) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (analysisScalar(source, "select count(*) from pg_stat_activity where wait_event_type='Lock'") == "0") {
            check(System.nanoTime() < deadline) { "no session waited for a lock" }
            Thread.sleep(5)
        }
    }

    /** Membership versions grow by one per item transition; counts match the ACTIVE members. */
    private fun assertMembership(source: DataSource, purpose: UUID, initial: Int, transitions: Int) {
        val state = purposeState(source, purpose)
        assertEquals(initial + transitions, state["membership"]!!.toInt(), "membership of $purpose")
    }

    @Test fun `same key twice moves once and replays the stored result`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        repeat(3) { round ->
            val target = insertPurpose(source, owner, "목적$round", null)
            val items = List(3) { seedItem(source, owner) }
            val key = UUID.randomUUID()
            val request = CandidateMoveRequest(1, items.map { MoveSelection(it, 3, null) })
            val (a, b) = race({ CandidateMoveService(source).move(owner, target, key, request) },
                { CandidateMoveService(source).move(owner, target, key, request.copy(items = request.items.reversed())) })
            assertEquals(setOf(false, true), setOf(a.getOrThrow().replayed, b.getOrThrow().replayed))
            assertEquals(a.getOrThrow().copy(replayed = false), b.getOrThrow().copy(replayed = false))
            assertMembership(source, target, 1, 3)
            assertTrue(items.all { itemColumns(source, it, "version")["version"] == "4" })
        }
    }

    @Test fun `B7 edit waiting behind a move sees the moved version`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val target = insertPurpose(source, owner, "목적", null)
        val item = seedItem(source, owner)
        val locked = CountDownLatch(1); val release = CountDownLatch(1)
        val mover = CandidateMoveService(source) { locked.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val move = pool.submit(Callable { mover.move(owner, target, UUID.randomUUID(), CandidateMoveRequest(1, listOf(MoveSelection(item, 3, null)))) })
            check(locked.await(10, TimeUnit.SECONDS))
            val edit = pool.submit(Callable { runCatching { ItemMutationService(source).edit(owner, item, 3, ItemChanges(purpose = FieldChange.Set(null))) } })
            awaitLockWaiter(source)
            release.countDown()
            assertEquals(4, move.get(10, TimeUnit.SECONDS).items.single().version)
            val error = edit.get(10, TimeUnit.SECONDS).exceptionOrNull() as ItemMutationException
            assertEquals("WISHLIST_ITEM_VERSION_CONFLICT", error.code); assertEquals(4, error.currentVersion)
        } finally { release.countDown(); pool.shutdownNow() }
        assertEquals(target.toString(), itemColumns(source, item, "purpose_id")["purpose_id"])
        assertMembership(source, target, 1, 1)

        val (a, b) = race(
            { CandidateMoveService(source).move(owner, insertPurpose(source, owner, "다음", null), UUID.randomUUID(),
                CandidateMoveRequest(1, listOf(MoveSelection(item, 4, target)))) },
            { ItemMutationService(source).edit(owner, item, 4, ItemChanges(purpose = FieldChange.Set(null))) },
        )
        assertEquals(1, listOf(a.code(), b.code()).count { it == "OK" })
        assertTrue(listOf(a.code(), b.code()).all { it == "OK" || it == "WISHLIST_ITEM_VERSION_CONFLICT" })
        assertEquals("5", itemColumns(source, item, "version")["version"])
        assertMembership(source, target, 1, 2)
    }

    @Test fun `opposite bulk moves never deadlock and keep membership exact`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val left = insertPurpose(source, owner, "왼쪽", null)
        val right = insertPurpose(source, owner, "오른쪽", null)
        // Seeds are inserted directly, so only moves change membership versions.
        var leftCount = 0; var rightCount = 0; var leftMembership = 1; var rightMembership = 1
        repeat(6) { round ->
            val inLeft = List(3) { seedItem(source, owner, ItemSeed(purpose = left, purposeSource = "USER")) }
            val inRight = List(3) { seedItem(source, owner, ItemSeed(purpose = right, purposeSource = "USER")) }
            val shared = seedItem(source, owner)
            leftCount += 3; rightCount += 3
            val toRight = CandidateMoveRequest(1, inLeft.map { MoveSelection(it, 3, left) } + MoveSelection(shared, 3, null))
            val toLeft = CandidateMoveRequest(1, (inRight.map { MoveSelection(it, 3, right) } + MoveSelection(shared, 3, null)).reversed())
            val (a, b) = race({ CandidateMoveService(source).move(owner, right, UUID.randomUUID(), toRight) },
                { CandidateMoveService(source).move(owner, left, UUID.randomUUID(), toLeft) })
            val codes = listOf(a.code(), b.code())
            assertEquals(1, codes.count { it == "OK" }, "round $round: $codes")
            assertEquals("WISHLIST_ITEM_VERSION_CONFLICT", codes.single { it != "OK" }, "the shared item already moved")
            if (a.isSuccess) { leftCount -= 3; rightCount += 4; leftMembership += 3; rightMembership += 4 }
            else { rightCount -= 3; leftCount += 4; rightMembership += 3; leftMembership += 4 }
            assertEquals(listOf(leftCount, rightCount), listOf(left, right).map { purposeState(source, it)["count"]!!.toInt() }, "round $round")
        }
        assertMembership(source, left, 0, leftMembership)
        assertMembership(source, right, 0, rightMembership)
    }
}
