package app.purpose

import app.common.FieldChange
import app.testutil.*
import app.wishlist.CreateWishlistItemService
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PurposeListAndEditTest {
    private val input = PurposeInput("목적", null, PurposeColor.CORAL, PurposeIcon.HEART)

    @Test fun `activity order breaks ties by id and includes empty purposes`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID(); val service = PurposeService(source)
        val ids = List(3) { service.create(owner, UUID.randomUUID(), input.copy(name = "p$it")).purpose.id }
        analysisSql(source, "update purposes set activity_at='2026-10-07T00:00:00Z' where owner_id='$owner'")
        val tied = service.list(owner, PurposeProjection.SELECT, 30, null).entries.map { it.purpose.id }
        // PostgreSQL orders uuid bytewise, which equals the hex string order (java.util.UUID.compareTo is signed).
        assertEquals(ids.sortedByDescending { it.toString() }, tied)
        source.connection.use { c -> c.autoCommit = false; app.persistence.OwnerStructureLock.lock(c, owner)
            PurposeMembership.recordTransition(c, owner, null, tied.last()); c.commit() }
        assertEquals(tied.last(), service.list(owner, PurposeProjection.SELECT, 30, null).entries.first().purpose.id)
        assertEquals(PurposeActivityKind.CANDIDATE_ADDED, service.get(owner, tied.last())!!.activityKind)
    }

    @Test fun `cursor pages are disjoint and complete`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID(); val service = PurposeService(source)
        repeat(5) { insertPurpose(source, owner, "p$it", null) }
        analysisSql(source, "update purposes set activity_at='2026-10-07T00:00:00Z' where owner_id='$owner'")
        val first = service.list(owner, PurposeProjection.SUMMARY, 2, null)
        val second = service.list(owner, PurposeProjection.SUMMARY, 2, first.next)
        val third = service.list(owner, PurposeProjection.SUMMARY, 2, second.next)
        val all = (first.entries + second.entries + third.entries).map { it.purpose.id }
        assertEquals(5, all.toSet().size); assertNull(third.next); assertNotNull(second.next)
        assertEquals(5, first.activeCount)
    }

    @Test fun `summary previews use newest saved active candidates and select omits them`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID(); val service = PurposeService(source)
        val purpose = service.create(owner, UUID.randomUUID(), input).purpose
        val items = List(6) { n -> CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://example.com/$n").createdItemId.also { id ->
            analysisSql(source, "update wishlist_items set purpose_id='${purpose.id}',purpose_source='USER',product_image_url='https://img/$n',created_at='2026-10-0${n + 1}T00:00:00Z' where id='$id'")
        } }
        analysisSql(source, "update wishlist_items set lifecycle_status='DELETED' where id='${items[5]}'")
        val entry = service.list(owner, PurposeProjection.SUMMARY, 30, null).entries.single()
        assertEquals(5L, entry.purpose.candidateCount)
        assertEquals(listOf(items[4], items[3], items[2], items[1]), entry.previews.map { it.itemId })
        assertEquals("https://img/4", entry.previews.first().imageUrl)
        assertTrue(service.list(owner, PurposeProjection.SELECT, 30, null).entries.single().previews.isEmpty())
    }

    @Test fun `archived purposes leave the list and are counted for the archive entry`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID(); val service = PurposeService(source)
        val kept = service.create(owner, UUID.randomUUID(), input).purpose
        val archived = service.create(owner, UUID.randomUUID(), input.copy(name = "archived")).purpose
        analysisSql(source, "update purposes set lifecycle_status='ARCHIVED' where id='${archived.id}'")
        val page = service.list(owner, PurposeProjection.SUMMARY, 30, null)
        assertEquals(listOf(kept.id), page.entries.map { it.purpose.id })
        assertEquals(1, page.activeCount); assertEquals(1, page.archivedCount)
        assertNull(service.get(owner, archived.id))
        assertTrue(service.list(UUID.randomUUID(), PurposeProjection.SUMMARY, 30, null).entries.isEmpty())
    }

    @Test fun `patch handles version conflicts no-ops optional null and keeps activity`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID(); val service = PurposeService(source)
        val purpose = service.create(owner, UUID.randomUUID(), input.copy(description = "설명")).purpose
        assertEquals(1, service.patch(owner, purpose.id, 1, PurposeChanges(name = input.name, color = input.color)).version)
        val pool = Executors.newFixedThreadPool(2)
        val results = try { List(2) { n -> pool.submit<Result<Purpose>> { runCatching { service.patch(owner, purpose.id, 1, PurposeChanges(name = "name-$n")) } } }
            .map { it.get(15, TimeUnit.SECONDS) } } finally { pool.shutdownNow() }
        assertEquals(1, results.count { it.isSuccess })
        val conflict = results.single { it.isFailure }.exceptionOrNull() as PurposeException
        assertEquals("PURPOSE_VERSION_CONFLICT", conflict.code); assertEquals(2, conflict.currentVersion)
        val cleared = service.patch(owner, purpose.id, 2, PurposeChanges(description = FieldChange.Set(null), icon = PurposeIcon.BOOK))
        assertNull(cleared.input.description); assertEquals(PurposeIcon.BOOK, cleared.input.icon); assertEquals(3, cleared.version)
        assertEquals(purpose.activityAt, cleared.activityAt); assertEquals(1, cleared.membershipVersion)
        assertEquals("INVALID_PURPOSE_INPUT", assertFailsWith<PurposeException> { service.patch(owner, purpose.id, 3, PurposeChanges()) }.code)
        assertEquals(setOf("name"), assertFailsWith<PurposeException> { service.patch(owner, purpose.id, 3, PurposeChanges(name = " ")) }.fields)
        assertEquals("PURPOSE_NOT_FOUND", assertFailsWith<PurposeException> { service.patch(UUID.randomUUID(), purpose.id, 3, PurposeChanges(name = "x")) }.code)
    }
}
