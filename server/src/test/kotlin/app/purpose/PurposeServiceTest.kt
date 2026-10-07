package app.purpose

import app.category.CategoryInput
import app.category.CategoryService
import app.testutil.*
import app.wishlist.CreateWishlistItemService
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PurposeServiceTest {
    private val input = PurposeInput("출퇴근 헤드폰", "지하철", PurposeColor.CORAL, PurposeIcon.MUSIC)

    @Test fun `new purpose is an empty active purpose and creates no analysis work`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID(); val service = PurposeService(source)
        CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://example.com/item")
        val jobs = analysisScalar(source, "select count(*) from analysis_jobs")
        val created = service.create(owner, UUID.randomUUID(), input)
        val purpose = created.purpose
        assertFalse(created.replayed); assertEquals(1, created.activeCount)
        assertEquals(input, purpose.input); assertEquals(0L, purpose.candidateCount)
        assertEquals(1, purpose.version); assertEquals(1, purpose.membershipVersion)
        assertEquals(PurposeActivityKind.CREATED, purpose.activityKind); assertEquals(purpose.createdAt, purpose.activityAt)
        assertEquals(listOf(PurposeAction.EDIT, PurposeAction.DELETE, PurposeAction.ADD_CANDIDATES), purpose.allowedActions)
        assertEquals(jobs, analysisScalar(source, "select count(*) from analysis_jobs"))
        assertEquals(purpose, service.get(owner, purpose.id))
        assertNull(service.get(UUID.randomUUID(), purpose.id))
    }

    @Test fun `replay key reuse namespaces and unavailable targets`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID(); val other = UUID.randomUUID(); val service = PurposeService(source); val key = UUID.randomUUID()
        val first = service.create(owner, key, input)
        assertTrue(service.create(owner, key, input).replayed)
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertFailsWith<PurposeException> { service.create(owner, key, input.copy(name = "다른 이름")) }.code)
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertFailsWith<PurposeException> { service.create(owner, key, input.copy(description = null)) }.code)
        assertFalse(service.create(other, key, input).replayed)
        assertFalse(CategoryService(source).create(owner, key, "G003", CategoryInput("desk", null, emptyList())).replayed)
        analysisSql(source, "update purposes set lifecycle_status='ARCHIVED' where id='${first.purpose.id}'")
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertFailsWith<PurposeException> { service.create(owner, key, input.copy(name = "x")) }.code)
        assertEquals("PURPOSE_NOT_AVAILABLE", assertFailsWith<PurposeException> { service.create(owner, key, input) }.code)
    }

    @Test fun `twenty nine active purposes allow only one of two concurrent creates`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID(); val service = PurposeService(source)
        repeat(29) { insertPurpose(source, owner, "seed-$it", null) }
        insertPurpose(source, owner, "archived", null).also { analysisSql(source, "update purposes set lifecycle_status='ARCHIVED' where id='$it'") }
        val results = concurrent(2) { n -> runCatching { service.create(owner, UUID.randomUUID(), input.copy(name = "new-$n")) } }
        assertEquals(1, results.count { it.isSuccess })
        assertEquals("PURPOSE_LIMIT_REACHED", (results.single { it.isFailure }.exceptionOrNull() as PurposeException).code)
        assertEquals("30", analysisScalar(source, "select count(*) from purposes where owner_id='$owner' and lifecycle_status='ACTIVE'"))
    }

    @Test fun `ten successful creates per sixty seconds with replay and failed key retry`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID(); val service = PurposeService(source); val replayKey = UUID.randomUUID()
        service.create(owner, replayKey, input)
        repeat(9) { service.create(owner, UUID.randomUUID(), input.copy(name = "more-$it")) }
        val retryKey = UUID.randomUUID()
        val limited = assertFailsWith<PurposeException> { service.create(owner, retryKey, input.copy(name = "eleventh")) }
        assertEquals("PURPOSE_CREATE_RATE_LIMITED", limited.code); assertTrue(limited.retryAfterSeconds!! in 1..60)
        assertTrue(service.create(owner, replayKey, input).replayed)
        analysisSql(source, "update mutation_receipts set created_at=clock_timestamp()-interval '61 seconds' where owner_id='$owner'")
        assertFalse(service.create(owner, retryKey, input.copy(name = "eleventh")).replayed)
    }

    @Test fun `same key race creates one purpose`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID(); val service = PurposeService(source); val key = UUID.randomUUID()
        val results = concurrent(2) { service.create(owner, key, input) }
        assertEquals(1, results.count { !it.replayed }); assertEquals(1, results.map { it.purpose.id }.toSet().size)
    }

    @Test fun `candidate count includes every active linked item and enables archive`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID(); val service = PurposeService(source)
        val purpose = service.create(owner, UUID.randomUUID(), input).purpose
        val items = List(3) { CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://example.com/$it").createdItemId }
        items.forEach { analysisSql(source, "update wishlist_items set purpose_id='${purpose.id}',purpose_source='USER' where id='$it'") }
        analysisSql(source, "update wishlist_items set lifecycle_status='DELETED' where id='${items[2]}'")
        val detail = service.get(owner, purpose.id)!!
        assertEquals(2L, detail.candidateCount)
        assertEquals(PurposeAction.ARCHIVE, detail.allowedActions.last())
    }

    private fun <T> concurrent(count: Int, action: (Int) -> T): List<T> {
        val ready = CountDownLatch(count); val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(count)
        return try {
            val futures = (0 until count).map { n -> pool.submit<T> { ready.countDown(); check(start.await(10, TimeUnit.SECONDS)); action(n) } }
            check(ready.await(10, TimeUnit.SECONDS)); start.countDown()
            futures.map { it.get(15, TimeUnit.SECONDS) }
        } finally { start.countDown(); pool.shutdownNow() }
    }
}
