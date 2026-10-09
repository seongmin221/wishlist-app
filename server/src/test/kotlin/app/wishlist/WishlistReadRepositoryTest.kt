package app.wishlist

import app.category.*
import app.http.WishlistItemViewMapper
import app.purpose.PurposeService
import app.testutil.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class WishlistReadRepositoryTest {
    @Test fun filters_match_purpose_candidate_count_and_owner() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID(); val other = UUID.randomUUID()
        val purpose = insertPurpose(source, owner, "purpose", null)
        val custom = CategoryService(source).create(owner, UUID.randomUUID(), "G003", CategoryInput("custom", null, emptyList())).category.id
        val time = Instant.parse("2026-10-07T10:00:00Z")
        fun fixture(name: String?, category: String?, status: AnalysisStatus, lifecycle: LifecycleStatus = LifecycleStatus.ACTIVE,
            p: UUID? = purpose, purposeSource: ValueSource = ValueSource.AI, customId: UUID? = null) =
            ReadFixture(ReadPosition(time, UUID.randomUUID()), WishlistItemState(status, ReviewStatus.DEFERRED, lifecycle, name, category, null, null),
                customId, p, purposeSource)
        val rows = listOf(fixture(null,null,AnalysisStatus.PROCESSING), fixture("\u00a0","C026",AnalysisStatus.PARTIAL),
            fixture("retained","C026",AnalysisStatus.PROCESSING), fixture("custom",custom.toString(),AnalysisStatus.READY,customId=custom),
            fixture("archived","C026",AnalysisStatus.READY,LifecycleStatus.ARCHIVED), fixture("deleted","C026",AnalysisStatus.READY,LifecycleStatus.DELETED),
            fixture(null,null,AnalysisStatus.FAILED_TERMINAL,p=null,purposeSource=ValueSource.UNASSIGNED),
            fixture(null,null,AnalysisStatus.PROCESSING,p=null,purposeSource=ValueSource.USER))
        source.connection.use { c -> insertReadFixtures(c,owner,rows); insertReadFixtures(c,other,listOf(fixture("other","C026",AnalysisStatus.READY,p=null,purposeSource=ValueSource.UNASSIGNED))) }
        val service = WishlistReadService(source)
        fun page(scope: ReadScope) = assertIs<ReadResult.Success>(service.read(owner,ReadQuery(scope,ReadWindow.Page(100)))).page
        val purposePage = page(ReadScope.Purpose(purpose))
        assertEquals(4L,purposePage.totalCount)
        assertEquals(PurposeService(source).get(owner,purpose)!!.candidateCount,purposePage.totalCount)
        assertEquals(rows.take(4).map { it.position.id }.toSet(),purposePage.items.map { it.id }.toSet())
        assertEquals(2L,page(ReadScope.PurposeUnassigned).totalCount)
        assertEquals(setOf(ValueSource.UNASSIGNED,ValueSource.USER),page(ReadScope.PurposeUnassigned).items.map { it.storedState.purposeSource }.toSet())
        assertEquals(listOf(rows[2].position.id),page(ReadScope.Category(CategoryRef.Public("C026"))).items.map { it.id })
        assertEquals(listOf(rows[3].position.id),page(ReadScope.Category(CategoryRef.Custom(custom))).items.map { it.id })
        assertEquals(ReadResult.PurposeNotFound,service.read(other,ReadQuery(ReadScope.Purpose(purpose),ReadWindow.Page(40))))
        assertEquals(ReadResult.CategoryNotFound,service.read(other,ReadQuery(ReadScope.Category(CategoryRef.Custom(custom)),ReadWindow.Page(40))))
        assertEquals(ReadResult.PurposeNotFound,service.read(owner,ReadQuery(ReadScope.Purpose(UUID.randomUUID()),ReadWindow.Page(40))))
        analysisSql(source,"update purposes set lifecycle_status='ARCHIVED' where id='$purpose'")
        assertEquals(ReadResult.PurposeNotFound,service.read(owner,ReadQuery(ReadScope.Purpose(purpose),ReadWindow.Page(40))))
    }
    @Test fun projection_keeps_detail_and_replay_contract() = withAnalysisDatabase { source ->
        val owner=UUID.randomUUID();val purpose=insertPurpose(source,owner,"comparison",null)
        val custom=CategoryService(source).create(owner,UUID.randomUUID(),"G003",CategoryInput("custom",null,emptyList())).category.id
        val time=Instant.parse("2026-10-07T10:00:00Z")
        val fixtures=buildList {
            for (a in AnalysisStatus.entries) for (r in ReviewStatus.entries) for (l in LifecycleStatus.entries)
            for (reason in listOf(null)+CategoryMissingReason.entries) for (manual in listOf(null,time))
                add(ReadFixture(ReadPosition(time,UUID.randomUUID()),WishlistItemState(a,r,l,"item",null,reason,manual),purposeId=purpose,purposeSource=ValueSource.USER))
            add(ReadFixture(ReadPosition(time,UUID.randomUUID()),WishlistItemState(AnalysisStatus.READY,ReviewStatus.PENDING,LifecycleStatus.ACTIVE,"custom",custom.toString(),null,null),custom,purpose,ValueSource.AI,"https://example.com/image",time.minusSeconds(1)))
        }
        source.connection.use { c -> insertReadFixtures(c,owner,fixtures) }
        val repository=WishlistReadRepository()
        source.connection.use { c ->
            val loaded=repository.load(c,owner,fixtures.map { it.position })
            assertEquals(fixtures.map { it.position.id },loaded.map { it.id })
            for (item in loaded) {
                val detail=assertNotNull(WishlistItemRepository(source).findOwned(owner,item.id))
                assertEquals(detail,item)
                val dto=WishlistItemViewMapper.map(item)
                assertEquals(WishlistItemPolicy.evaluate(item.storedState.state).allowedActions,dto.allowedActions)
                assertNull(dto.product.brand);assertNull(dto.product.price);assertNull(dto.product.currency);assertNull(dto.product.merchant);assertNull(dto.product.metadataCheckedAt)
            }
            assertTrue(repository.load(c,UUID.randomUUID(),fixtures.map { it.position }).isEmpty())
        }
    }
}
