package app.candidates

import app.category.CategoryRef
import app.testutil.*
import app.wishlist.ReadPosition
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

class CandidateReadTest {
    private fun DataSource.list(owner: UUID, purpose: UUID, filter: CandidateFilter = CandidateFilter.None, limit: Int = 100,
                                after: ReadPosition? = null, facets: Boolean = false) =
        CandidateReadService(this).list(owner, purpose, filter, limit, after, facets)

    private fun expectError(code: String, block: () -> Unit) =
        assertFailsWith<CandidateException> { block() }.also { assertEquals(code, it.code); assertNull(it.itemId); assertNull(it.currentVersion) }

    private val unassignedCategory = ItemSeed(analysis = "PARTIAL", review = "NOT_REQUIRED", category = null, categorySource = null,
        missingReason = "AI_ABSTAINED")

    @Test fun `pool keeps every non processing active state outside the purpose and hides other owners`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val target = insertPurpose(source, owner, "목적", null)
        val other = insertPurpose(source, owner, "다른 목적", null)
        val inTarget = seedItem(source, owner, ItemSeed(purpose = target, purposeSource = "USER"))
        val included = listOf(
            seedItem(source, owner, ItemSeed(purpose = other, purposeSource = "AI")),
            seedItem(source, owner, unassignedCategory.copy(name = null, nameSource = null)),
            seedItem(source, owner, ItemSeed(analysis = "FAILED_RETRYABLE", review = "NOT_REQUIRED", name = null, nameSource = null,
                category = null, categorySource = null, missingReason = "EXTRACTION_UNRESOLVED")),
            seedItem(source, owner, ItemSeed(analysis = "FAILED_TERMINAL", review = "NOT_REQUIRED", category = null, categorySource = null,
                missingReason = "EXTRACTION_UNRESOLVED")),
            seedItem(source, owner, ItemSeed(analysis = "PARTIAL", review = "CONFIRMED", categorySource = "USER", manualCompleted = true)),
            seedItem(source, owner, ItemSeed(review = "CONFIRMED", purposeSource = "USER")),
        )
        val excluded = listOf(
            inTarget,
            seedItem(source, owner, ItemSeed(analysis = "PROCESSING", review = "NOT_REQUIRED", name = null, nameSource = null, category = null,
                categorySource = null, missingReason = "EXTRACTION_UNRESOLVED")),
            seedItem(source, owner, ItemSeed(lifecycle = "ARCHIVED")),
            seedItem(source, owner, ItemSeed(lifecycle = "DELETED")),
            seedItem(source, UUID.randomUUID()),
        )
        val page = source.list(owner, target)
        assertEquals(included.toSet(), page.items.map { it.id }.toSet())
        assertTrue(page.items.none { it.id in excluded })
        assertEquals(included.size.toLong(), page.totalCount)
        assertNull(page.next)
        assertNull(page.facets)

        val foreignPurpose = insertPurpose(source, UUID.randomUUID(), "남의 목적", null)
        expectError("PURPOSE_NOT_FOUND") { source.list(owner, foreignPurpose) }
        expectError("PURPOSE_NOT_FOUND") { source.list(owner, UUID.randomUUID()) }
        archivePurpose(source, other)
        expectError("PURPOSE_NOT_FOUND") { source.list(owner, other) }
        archivePurpose(source, other, "DELETED")
        expectError("PURPOSE_NOT_FOUND") { source.list(owner, other) }
    }

    @Test fun `filters narrow the page while facets describe the whole pool`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val target = insertPurpose(source, owner, "목적", null)
        val custom = insertCustomCategory(source, owner)
        val headphones = List(2) { seedItem(source, owner) }
        val phone = seedItem(source, owner, ItemSeed(category = "C019"))
        val mine = seedItem(source, owner, ItemSeed(customCategory = custom))
        val outer = seedItem(source, owner, ItemSeed(category = "C001"))
        val none = seedItem(source, owner, unassignedCategory)
        seedItem(source, owner, ItemSeed(purpose = target, purposeSource = "USER", category = "C023"))
        seedItem(source, owner, ItemSeed(analysis = "PROCESSING", review = "NOT_REQUIRED", category = "C024"))

        val publicPage = source.list(owner, target, CandidateFilter.Category(CategoryRef.Public("C026")), facets = true)
        assertEquals(headphones.toSet(), publicPage.items.map { it.id }.toSet())
        assertEquals(2, publicPage.totalCount)
        val facets = assertNotNull(publicPage.facets)
        val expected = listOf("C001" to "G001", "C019" to "G003", "C026" to "G003", custom.toString() to "G003").sortedBy { it.first }
        assertEquals(expected.map { (id, parent) -> CategoryFacet(id, parent, if (id == "C026") 2 else 1) }, facets.categories)
        assertEquals(1, facets.unassignedCount)

        assertEquals(setOf(mine), source.list(owner, target, CandidateFilter.Category(CategoryRef.Custom(custom))).items.map { it.id }.toSet())
        val parent = source.list(owner, target, CandidateFilter.Parent("G003"), facets = true)
        assertEquals((headphones + phone + mine).toSet(), parent.items.map { it.id }.toSet())
        assertEquals(facets, parent.facets, "facets ignore the current filter")
        assertEquals(setOf(outer), source.list(owner, target, CandidateFilter.Parent("G001")).items.map { it.id }.toSet())
        assertEquals(6, source.list(owner, target).totalCount)
        assertTrue(none in source.list(owner, target).items.map { it.id })
        assertEquals(0, source.list(owner, target, CandidateFilter.Parent("G009")).totalCount)

        val stranger = UUID.randomUUID()
        val empty = insertPurpose(source, stranger, "빈", null)
        val emptyFacets = assertNotNull(source.list(stranger, empty, facets = true).facets)
        assertEquals(emptyList(), emptyFacets.categories)
        assertEquals(0, emptyFacets.unassignedCount)

        expectError("CATEGORY_NOT_FOUND") { source.list(owner, target, CandidateFilter.Category(CategoryRef.Custom(insertCustomCategory(source, owner, deleted = true)))) }
        expectError("CATEGORY_NOT_FOUND") { source.list(owner, target, CandidateFilter.Category(CategoryRef.Custom(insertCustomCategory(source, UUID.randomUUID())))) }
        expectError("PURPOSE_NOT_FOUND") { source.list(owner, UUID.randomUUID(), CandidateFilter.Category(CategoryRef.Custom(UUID.randomUUID()))) }
    }

    @Test fun `keyset pages with tied createdAt have exclusive boundaries`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val target = insertPurpose(source, owner, "목적", null)
        val tied = List(5) { seedItem(source, owner).also { setCreatedAt(source, it, "2026-10-24T01:00:00Z") } }
        val newer = seedItem(source, owner).also { setCreatedAt(source, it, "2026-10-24T02:00:00Z") }
        val older = seedItem(source, owner).also { setCreatedAt(source, it, "2026-10-24T00:00:00Z") }
        val expected = listOf(newer) + tied.sortedWith(sqlUuidOrder.reversed()) + older

        for (limits in listOf(listOf(2), listOf(1, 3, 100), listOf(100))) {
            val seen = mutableListOf<UUID>()
            var after: ReadPosition? = null
            var step = 0
            do {
                val page = source.list(owner, target, limit = limits[minOf(step++, limits.lastIndex)], after = after)
                assertEquals(7, page.totalCount)
                seen += page.items.map { it.id }
                after = page.next
                if (after != null) assertEquals(page.items.last().let { ReadPosition(it.createdAt, it.id) }, after)
            } while (after != null)
            assertEquals(expected, seen, "limits $limits")
        }
    }
}
