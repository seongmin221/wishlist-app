package app.candidates

import app.testutil.*
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

class CandidateMoveTest {
    private fun DataSource.move(owner: UUID, target: UUID, expectedVersion: Int, vararg items: MoveSelection, key: UUID = UUID.randomUUID()) =
        CandidateMoveService(this).move(owner, target, key, CandidateMoveRequest(expectedVersion, items.toList()))

    private fun receipts(source: DataSource) = analysisScalar(source, "select count(*) from candidate_mutation_receipts")!!.toInt()

    private fun expectError(code: String, block: () -> Unit) =
        assertFailsWith<CandidateException> { block() }.also { assertEquals(code, it.code) }

    @Test fun `moves every selected item in one transaction and records membership per item`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val target = insertPurpose(source, owner, "목적", null)
        val from = insertPurpose(source, owner, "이전", null)
        val aiPending = seedItem(source, owner, ItemSeed(purpose = from, purposeSource = "AI"))
        val userOnly = seedItem(source, owner, ItemSeed(purpose = from, purposeSource = "AI", categorySource = "USER", version = 5))
        val loose = seedItem(source, owner, ItemSeed(review = "NOT_REQUIRED", category = null, categorySource = null,
            analysis = "PARTIAL", missingReason = "AI_ABSTAINED", name = null, nameSource = null))
        val targetBefore = purposeState(source, target)
        val fromBefore = purposeState(source, from)

        val result = source.move(owner, target, 1, MoveSelection(loose, 3, null), MoveSelection(aiPending, 3, from),
            MoveSelection(userOnly, 5, from))

        assertFalse(result.replayed)
        val ids = listOf(aiPending, userOnly, loose).sortedWith(sqlUuidOrder)
        assertEquals(ids, result.items.map { it.id }, "items in SQL UUID order")
        assertEquals(mapOf(aiPending to 4, userOnly to 6, loose to 4), result.items.associate { it.id to it.version })
        assertTrue(result.items.all { it.purposeId == target })
        assertEquals(listOf(from, target).sortedWith(sqlUuidOrder), result.purposes.map { it.id })
        val targetAfter = purposeState(source, target); val fromAfter = purposeState(source, from)
        assertEquals(MovedPurpose(target, 3, targetBefore["membership"]!!.toInt() + 3), result.purposes.single { it.id == target })
        assertEquals(MovedPurpose(from, 0, fromBefore["membership"]!!.toInt() + 2), result.purposes.single { it.id == from })
        assertEquals("3", targetAfter["count"]); assertEquals("0", fromAfter["count"])
        assertEquals(targetBefore["version"], targetAfter["version"], "metadata version is not membership")
        assertNotEquals(targetBefore["activity"], targetAfter["activity"]); assertEquals("CANDIDATE_ADDED", targetAfter["kind"])
        assertEquals(fromBefore["activity"], fromAfter["activity"], "removal is not activity")
        for (id in ids) assertEquals(mapOf("purpose_id" to target.toString(), "purpose_source" to "USER"), itemColumns(source, id, "purpose_id", "purpose_source"))
        assertEquals("PENDING", itemColumns(source, aiPending, "review_status")["review_status"], "AI category review stays")
        assertEquals("CONFIRMED", itemColumns(source, userOnly, "review_status")["review_status"], "no AI connection remains")
        assertEquals("NOT_REQUIRED", itemColumns(source, loose, "review_status")["review_status"])
        assertEquals("C026", itemColumns(source, aiPending, "category_id")["category_id"], "category is not touched")
    }

    @Test fun `replay returns the stored result without moving again`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val target = insertPurpose(source, owner, "목적", null)
        val back = insertPurpose(source, owner, "돌아갈 곳", null)
        val a = seedItem(source, owner); val b = seedItem(source, owner)
        val key = UUID.randomUUID()
        val first = source.move(owner, target, 1, MoveSelection(a, 3, null), MoveSelection(b, 3, null), key = key)
        val replay = source.move(owner, target, 1, MoveSelection(b, 3, null), MoveSelection(a, 3, null), key = key)
        assertTrue(replay.replayed)
        assertEquals(first.copy(replayed = true), replay)
        assertEquals(1, receipts(source))

        source.move(owner, back, 1, MoveSelection(a, 4, target))
        val membership = purposeState(source, target)
        val later = source.move(owner, target, 1, MoveSelection(a, 3, null), MoveSelection(b, 3, null), key = key)
        assertEquals(first.copy(replayed = true), later, "the receipt is the result at move time, not the current state")
        assertEquals(back.toString(), itemColumns(source, a, "purpose_id")["purpose_id"], "replay never moves again")
        assertEquals(membership, purposeState(source, target))

        for (changed in listOf(
            { source.move(owner, target, 1, MoveSelection(a, 3, null), key = key) },
            { source.move(owner, target, 2, MoveSelection(a, 3, null), MoveSelection(b, 3, null), key = key) },
            { source.move(owner, target, 1, MoveSelection(a, 4, null), MoveSelection(b, 3, null), key = key) },
            { source.move(owner, target, 1, MoveSelection(a, 3, back), MoveSelection(b, 3, null), key = key) },
            { source.move(owner, back, 1, MoveSelection(a, 3, null), MoveSelection(b, 3, null), key = key) },
        )) expectError("IDEMPOTENCY_KEY_REUSED") { changed() }
        val stranger = UUID.randomUUID()
        expectError("PURPOSE_NOT_FOUND") { source.move(stranger, target, 1, MoveSelection(a, 3, null), key = key) }
    }

    @Test fun `one conflicting item rolls back every item purpose and receipt`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val target = insertPurpose(source, owner, "목적", null)
        val from = insertPurpose(source, owner, "이전", null)
        val good = seedItem(source, owner, ItemSeed(purpose = from, purposeSource = "USER"))
        val stale = seedItem(source, owner, ItemSeed(version = 7))
        val processing = seedItem(source, owner, ItemSeed(analysis = "PROCESSING", review = "NOT_REQUIRED"))
        val archived = seedItem(source, owner, ItemSeed(lifecycle = "ARCHIVED"))
        val member = seedItem(source, owner, ItemSeed(purpose = target, purposeSource = "USER"))
        val elsewhere = seedItem(source, owner, ItemSeed(purpose = from, purposeSource = "AI"))
        val all = listOf(good, stale, processing, archived, member, elsewhere)
        val snapshot = itemSnapshot(source, all)
        val purposes = listOf(target, from).map { purposeState(source, it) }

        fun assertConflict(code: String, item: UUID?, version: Int, vararg extra: MoveSelection, expected: Int = 1) {
            val error = expectError(code) { source.move(owner, target, expected, MoveSelection(good, 3, from), *extra) }
            assertEquals(item, error.itemId); assertEquals(version, error.currentVersion)
            assertEquals(snapshot, itemSnapshot(source, all)); assertEquals(purposes, listOf(target, from).map { purposeState(source, it) })
            assertEquals(0, receipts(source))
        }
        assertConflict("WISHLIST_ITEM_VERSION_CONFLICT", stale, 7, MoveSelection(stale, 3, null))
        assertConflict("WISHLIST_ITEM_STATE_CONFLICT", processing, 3, MoveSelection(processing, 3, null))
        assertConflict("WISHLIST_ITEM_STATE_CONFLICT", archived, 3, MoveSelection(archived, 3, null))
        assertConflict("WISHLIST_ITEM_STATE_CONFLICT", member, 3, MoveSelection(member, 3, target))
        assertConflict("WISHLIST_ITEM_PURPOSE_CONFLICT", elsewhere, 3, MoveSelection(elsewhere, 3, null))
        assertConflict("WISHLIST_ITEM_VERSION_CONFLICT", processing, 3, MoveSelection(processing, 2, null))
        assertConflict("PURPOSE_VERSION_CONFLICT", null, 1, MoveSelection(stale, 3, null), expected = 2)

        val first = listOf(processing, stale).sortedWith(sqlUuidOrder).first()
        val error = expectError(if (first == stale) "WISHLIST_ITEM_VERSION_CONFLICT" else "WISHLIST_ITEM_STATE_CONFLICT") {
            source.move(owner, target, 1, MoveSelection(stale, 3, null), MoveSelection(processing, 3, null))
        }
        assertEquals(first, error.itemId, "the first conflict in SQL UUID order is reported")
        assertEquals(0, receipts(source))
    }

    @Test fun `missing deleted or foreign items are not found before any conflict`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val target = insertPurpose(source, owner, "목적", null)
        val stale = seedItem(source, owner, ItemSeed(version = 7))
        val deleted = seedItem(source, owner, ItemSeed(lifecycle = "DELETED"))
        val foreign = seedItem(source, UUID.randomUUID())
        val snapshot = itemSnapshot(source, listOf(stale, deleted, foreign))
        for (missing in listOf(foreign, deleted, UUID.randomUUID())) {
            for (version in listOf(1, 2)) {
                val error = expectError("WISHLIST_ITEM_NOT_FOUND") {
                    source.move(owner, target, version, MoveSelection(stale, 3, null), MoveSelection(missing, 3, null))
                }
                assertNull(error.itemId); assertNull(error.currentVersion)
            }
        }
        assertEquals(snapshot, itemSnapshot(source, listOf(stale, deleted, foreign)))
        val foreignPurpose = insertPurpose(source, UUID.randomUUID(), "남의 목적", null)
        expectError("PURPOSE_NOT_FOUND") { source.move(owner, foreignPurpose, 1, MoveSelection(foreign, 3, null)) }
        archivePurpose(source, target)
        assertNull(expectError("PURPOSE_NOT_FOUND") { source.move(owner, target, 1, MoveSelection(stale, 7, null)) }.currentVersion)
        assertEquals(0, receipts(source))
    }
}
