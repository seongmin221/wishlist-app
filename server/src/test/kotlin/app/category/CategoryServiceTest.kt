package app.category

import app.common.FieldChange
import app.testutil.*
import app.wishlist.CreateWishlistItemService
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class CategoryServiceTest {
    @Test fun `unchanged patch keeps version and deleting a sibling does not renumber display order`() = withAnalysisDatabase { source ->
        val service=CategoryService(source); val owner=UUID.randomUUID()
        val input=CategoryInput("desk", "description",listOf("keyboard")); val key=UUID.randomUUID()
        val first=service.create(owner,key,"G003",input).category
        assertEquals(1,service.patch(owner,first.id,1,CategoryChanges(name=input.name,description=FieldChange.Set(input.description),examples=FieldChange.Set(input.examples))).version)
        val second=service.create(owner,UUID.randomUUID(),"G003",input.copy(name="second")).category
        val before=service.list(owner,CategoryScope.SELECT,"G003").groups.single().categories.single { it.id==second.id.toString() }.displayOrder
        analysisSql(source,"update custom_categories set deleted_at=clock_timestamp() where id='${first.id}'")
        val after=service.list(owner,CategoryScope.SELECT,"G003").groups.single().categories.single { it.id==second.id.toString() }.displayOrder
        assertEquals(before,after)
        assertEquals("CATEGORY_NOT_AVAILABLE",assertFailsWith<CategoryException> { service.create(owner,key,"G003",input) }.code)
        assertEquals(1,service.list(owner,CategoryScope.SELECT,null).customUsedCount)
    }

    @Test fun `failed keys can be retried and deleted categories still consume successful create rate window`() = withAnalysisDatabase { source ->
        val service=CategoryService(source);val owner=UUID.randomUUID();val failedKey=UUID.randomUUID()
        val first=service.create(owner,UUID.randomUUID(),"G003",CategoryInput("desk",null,emptyList())).category
        assertFailsWith<CategoryException> { service.create(owner,failedKey,"G003",CategoryInput("desk",null,emptyList())) }
        assertFalse(service.create(owner,failedKey,"G003",CategoryInput("renamed",null,emptyList())).replayed)
        repeat(3) { service.create(owner,UUID.randomUUID(),"G003",CategoryInput("more-$it",null,emptyList())) }
        analysisSql(source,"update custom_categories set deleted_at=clock_timestamp() where id='${first.id}'")
        val retryKey=UUID.randomUUID()
        assertEquals("CATEGORY_CREATE_RATE_LIMITED",assertFailsWith<CategoryException> { service.create(owner,retryKey,"G003",CategoryInput("sixth",null,emptyList())) }.code)
        analysisSql(source,"update mutation_receipts set created_at=clock_timestamp()-interval '61 seconds' where owner_id='$owner'")
        assertFalse(service.create(owner,retryKey,"G003",CategoryInput("sixth",null,emptyList())).replayed)
    }

    @Test fun `select browse parent filters and active counts include empty custom`() = withAnalysisDatabase { source ->
        val service = CategoryService(source)
        val owner = UUID.randomUUID()
        val select = service.list(owner, CategoryScope.SELECT, null)
        assertEquals(11, select.groups.size)
        assertEquals(87, select.groups.sumOf { it.categories.size })
        assertTrue(service.list(owner, CategoryScope.BROWSE, null).groups.isEmpty())
        val custom = service.create(owner, UUID.randomUUID(), "G003", CategoryInput("Desk gear", null, emptyList())).category
        assertEquals(0L, service.get(owner, custom.id)!!.itemCount)
        assertEquals(listOf(custom.id.toString()), service.list(owner, CategoryScope.BROWSE, null).groups.single().categories.map { it.id })
        val item = CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://example.com/item").createdItemId
        analysisSql(source, "update wishlist_items set category_id='C026',category_source='USER',category_missing_reason=null where id='$item'")
        val browse = service.list(owner, CategoryScope.BROWSE, "G003")
        assertEquals(1L, browse.groups.single().itemCount)
        assertEquals(1L, browse.groups.single().categories.single { it.id == "C026" }.itemCount)
        assertEquals(1, service.list(owner, CategoryScope.SELECT, "G001").customUsedCount)
        analysisSql(source, "update wishlist_items set lifecycle_status='DELETED' where id='$item'")
        assertEquals(0L, service.list(owner, CategoryScope.BROWSE, null).groups.single().itemCount)
        assertTrue(service.list(UUID.randomUUID(), CategoryScope.BROWSE, null).groups.isEmpty())
    }

    @Test fun `same parent normalized names conflict while different parents permit them`() = withAnalysisDatabase { source ->
        val service = CategoryService(source)
        val owner = UUID.randomUUID()
        val input = CategoryInput(" My\u00a0  Desk ", null, emptyList())
        service.create(owner, UUID.randomUUID(), "G003", input)
        assertEquals("CATEGORY_NAME_DUPLICATE", assertFailsWith<CategoryException> {
            service.create(owner, UUID.randomUUID(), "G003", input.copy(name = "my desk"))
        }.code)
        assertEquals(input.name, service.create(owner, UUID.randomUUID(), "G004", input).category.input.name)
    }

    @Test fun `nineteen categories concurrent creation permits only one`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val service = CategoryService(source)
        val first = service.create(owner, UUID.randomUUID(), "G003", CategoryInput("seed", null, emptyList())).category
        source.connection.use { c -> c.prepareStatement("""insert into custom_categories(id,owner_id,parent_id,name,normalized_name,display_order)
            select gen_random_uuid(),owner_id,parent_id,'seed-'||n,'seed-'||n,display_order+n from custom_categories cross join generate_series(1,18) n where id=?""").use { s ->
            s.setObject(1, first.id); assertEquals(18, s.executeUpdate())
        } }
        val results = concurrent(2) { n -> runCatching { service.create(owner, UUID.randomUUID(), "G003", CategoryInput("new-$n", null, emptyList())) } }
        assertEquals(1, results.count { it.isSuccess })
        assertEquals("CATEGORY_LIMIT_REACHED", (results.single { it.isFailure }.exceptionOrNull() as CategoryException).code)
        assertEquals(20, service.list(owner, CategoryScope.SELECT, null).customUsedCount)
    }

    @Test fun `same key races replay one creation and payload conflict survives edits and rate limit`() = withAnalysisDatabase { source ->
        val service = CategoryService(source)
        val owner = UUID.randomUUID()
        val key = UUID.randomUUID()
        val input = CategoryInput("desk", "desc", listOf("example"))
        val results = concurrent(2) { service.create(owner, key, "G003", input) }
        assertEquals(1, results.count { !it.replayed })
        assertEquals(1, results.map { it.category.id }.toSet().size)
        val category = results.first().category
        val edited = service.patch(owner, category.id, 1, CategoryChanges(name = "changed", description = FieldChange.Set(null), examples = FieldChange.Set(emptyList())))
        assertEquals(2, edited.version)
        assertEquals("changed", service.create(owner, key, "G003", input).category.input.name)
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertFailsWith<CategoryException> {
            service.create(owner, key, "G003", input.copy(name = "other"))
        }.code)
        repeat(4) { service.create(owner, UUID.randomUUID(), "G003", input.copy(name = "more-$it")) }
        val limited = assertFailsWith<CategoryException> { service.create(owner, UUID.randomUUID(), "G003", input.copy(name = "sixth")) }
        assertEquals("CATEGORY_CREATE_RATE_LIMITED", limited.code)
        assertTrue(limited.retryAfterSeconds!! in 1..60)
        assertTrue(service.create(owner, key, "G003", input).replayed)
        assertEquals(5, service.list(owner, CategoryScope.SELECT, null).customUsedCount)
    }

    @Test fun `owner isolation version conflict and database owner reference protection`() = withAnalysisDatabase { source ->
        val service = CategoryService(source)
        val owner = UUID.randomUUID()
        val other = UUID.randomUUID()
        val category = service.create(owner, UUID.randomUUID(), "G003", CategoryInput("desk", null, emptyList())).category
        assertNull(service.get(other, category.id))
        assertEquals("CATEGORY_NOT_FOUND", assertFailsWith<CategoryException> { service.patch(other, category.id, 1, CategoryChanges(name="forged")) }.code)
        val updates = concurrent(2) { n -> runCatching { service.patch(owner, category.id, 1, CategoryChanges(name="name-$n")) } }
        assertEquals(1, updates.count { it.isSuccess })
        val failure = updates.single { it.isFailure }.exceptionOrNull() as CategoryException
        assertEquals("CATEGORY_VERSION_CONFLICT", failure.code)
        assertEquals(2, failure.currentVersion)
        val item = CreateWishlistItemService(source).create(other, UUID.randomUUID(), "https://example.com/item").createdItemId
        assertFailsWith<java.sql.SQLException> { analysisSql(source, "update wishlist_items set custom_category_id='${category.id}',category_source='USER',category_missing_reason=null where id='$item'") }
        val ownItem = CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://example.com/item").createdItemId
        analysisSql(source, "update wishlist_items set custom_category_id='${category.id}',category_source='USER',category_missing_reason=null where id='$ownItem'")
        assertEquals(1L, service.get(owner, category.id)!!.itemCount)
    }

    @Test fun `concurrent normalized duplicates create one row`() = withAnalysisDatabase { source ->
        val owner=UUID.randomUUID();val service=CategoryService(source)
        val results=concurrent(2) { n -> runCatching { service.create(owner,UUID.randomUUID(),"G003",CategoryInput(if(n==0) "  Desk  Gear " else "desk gear",null,emptyList())) } }
        assertEquals(1,results.count { it.isSuccess })
        assertEquals("CATEGORY_NAME_DUPLICATE",(results.single { it.isFailure }.exceptionOrNull() as CategoryException).code)
        assertEquals(1,service.list(owner,CategoryScope.SELECT,null).customUsedCount)
    }

    @Test fun `four successful creates allow only one of two concurrent new creates`() = withAnalysisDatabase { source ->
        val owner=UUID.randomUUID();val service=CategoryService(source)
        repeat(4) { service.create(owner,UUID.randomUUID(),"G003",CategoryInput("seed-$it",null,emptyList())) }
        val results=concurrent(2) { n -> runCatching { service.create(owner,UUID.randomUUID(),"G003",CategoryInput("new-$n",null,emptyList())) } }
        assertEquals(1,results.count { it.isSuccess })
        assertEquals("CATEGORY_CREATE_RATE_LIMITED",(results.single { it.isFailure }.exceptionOrNull() as CategoryException).code)
        assertEquals("5",analysisScalar(source,"select count(*) from mutation_receipts where owner_id='$owner'"))
    }

    @Test fun `same key remains distinct across owners and item creation operation`() = withAnalysisDatabase { source ->
        val key=UUID.randomUUID();val owner=UUID.randomUUID();val other=UUID.randomUUID();val service=CategoryService(source)
        val input=CategoryInput("Desk",null,emptyList())
        CreateWishlistItemService(source).create(owner,key,"https://example.com/item")
        val first=service.create(owner,key,"G003",input)
        val second=service.create(other,key,"G003",input)
        assertFalse(first.replayed);assertFalse(second.replayed)
        assertNotEquals(first.category.id,second.category.id)
        assertTrue(service.create(owner,key,"G003",input).replayed)
    }

    @Test fun `saved display order determines array order despite clock changes`() = withAnalysisDatabase { source ->
        val owner=UUID.randomUUID();val service=CategoryService(source)
        val first=service.create(owner,UUID.randomUUID(),"G003",CategoryInput("first",null,emptyList())).category
        val second=service.create(owner,UUID.randomUUID(),"G003",CategoryInput("second",null,emptyList())).category
        analysisSql(source,"update custom_categories set created_at=created_at-interval '1 day' where id='${second.id}'")
        assertEquals(listOf(first.id.toString(),second.id.toString()),service.list(owner,CategoryScope.SELECT,"G003").groups.single().categories.filter { it.kind=="CUSTOM" }.map { it.id })
    }

    @Test fun `rate window uses time after owner lock waiting`() = withAnalysisDatabase { source ->
        val owner=UUID.randomUUID();val service=CategoryService(source)
        repeat(5) { service.create(owner,UUID.randomUUID(),"G003",CategoryInput("seed-$it",null,emptyList())) }
        val executor=Executors.newSingleThreadExecutor()
        try {
            source.connection.use { blocker ->
                blocker.autoCommit=false
                app.persistence.OwnerStructureLock.lock(blocker,owner)
                val result=executor.submit<CategoryCreation> { service.create(owner,UUID.randomUUID(),"G003",CategoryInput("after-wait",null,emptyList())) }
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
                var waiting=false
                while(!waiting && System.nanoTime()<deadline) {
                    waiting=blocker.createStatement().use { statement ->
                        statement.execute("select pg_stat_clear_snapshot()")
                        statement.executeQuery("select exists(select 1 from pg_stat_activity where wait_event_type='Lock' and query like '%app_users%')").use { rows -> rows.next();rows.getBoolean(1) }
                    }
                    if(!waiting) Thread.sleep(10)
                }
                assertTrue(waiting,"create must be blocked after starting its transaction")
                // The blocked transaction's now() precedes this timestamp; clock_timestamp() after the lock is later.
                blocker.prepareStatement("update mutation_receipts set created_at=clock_timestamp()-interval '60 seconds' where owner_id=?").use { statement -> statement.setObject(1,owner);statement.executeUpdate() }
                blocker.commit()
                assertFalse(result.get(10,TimeUnit.SECONDS).replayed)
            }
        } finally { executor.shutdownNow() }
    }

    private fun <T> concurrent(count: Int, action: (Int) -> T): List<T> {
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(count)
        return try {
            val futures = (0 until count).map { n -> pool.submit<T> { ready.countDown(); check(start.await(10, TimeUnit.SECONDS)); action(n) } }
            check(ready.await(10, TimeUnit.SECONDS)); start.countDown()
            futures.map { it.get(15, TimeUnit.SECONDS) }
        } finally { start.countDown(); pool.shutdownNow() }
    }
}
