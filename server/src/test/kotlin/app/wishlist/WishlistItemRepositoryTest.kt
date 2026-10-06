package app.wishlist

import app.DatabaseFactory
import app.testutil.PostgresTestContainer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WishlistItemRepositoryTest {
    @Test
    fun `owned lookup maps state and cannot read another owner or item`() {
        PostgresTestContainer().use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
            val owner = UUID.randomUUID()
            val item = CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://example.com/item").itemId
            source.connection.use { connection ->
                connection.createStatement().use { it.executeUpdate("""
                    update wishlist_items set analysis_status='PARTIAL', review_status='CONFIRMED',
                    lifecycle_status='ARCHIVED', product_name='제품', category_id='C026', category_source='USER',
                    category_missing_reason=null, purpose_source='USER', purpose_id=null,
                    name_source='USER', image_source='AI', user_override_fields=array['NAME','CATEGORY','PURPOSE'],
                    current_generation=3, version=7, manual_completion_at='2026-10-05T01:00:00Z'
                    where id='$item'
                """.trimIndent()) }
            }
            val repository = WishlistItemRepository(source)
            val stored = assertNotNull(repository.findOwned(owner, item)).storedState
            assertEquals(item, stored.id)
            assertEquals(owner, stored.ownerId)
            assertEquals(7, stored.version)
            assertEquals(3, stored.currentGeneration)
            assertEquals(AnalysisStatus.PARTIAL, stored.state.analysisStatus)
            assertEquals(ReviewStatus.CONFIRMED, stored.state.reviewStatus)
            assertEquals(LifecycleStatus.ARCHIVED, stored.state.lifecycleStatus)
            assertEquals("제품", stored.state.productName)
            assertEquals("C026", stored.state.categoryId)
            assertNull(stored.state.categoryMissingReason)
            assertEquals("2026-10-05T01:00:00Z", stored.state.manualCompletionAt.toString())
            assertEquals(ValueSource.USER, stored.categorySource)
            assertNull(stored.purposeId)
            assertEquals(ValueSource.USER, stored.purposeSource)
            assertEquals(ValueSource.USER, stored.nameSource)
            assertEquals(ValueSource.AI, stored.imageSource)
            assertEquals(setOf("NAME", "CATEGORY", "PURPOSE"), stored.userOverrideFields)
            assertNull(repository.findOwned(UUID.randomUUID(), item))
            assertNull(repository.findOwned(owner, UUID.randomUUID()))
            source.connection.use { connection ->
                connection.createStatement().use { it.executeUpdate("update wishlist_items set lifecycle_status='DELETED' where id='$item'") }
            }
            assertEquals(LifecycleStatus.DELETED, repository.findOwned(owner, item)!!.storedState.state.lifecycleStatus)
        }
    }

    @Test
    fun `new item and job share initial generation with nullable unassigned state`() {
        PostgresTestContainer().use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
            val owner = UUID.randomUUID()
            val item = CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://example.com/item").itemId
            val stored = assertNotNull(WishlistItemRepository(source).findOwned(owner, item)).storedState
            assertEquals(1, stored.currentGeneration)
            assertEquals(1, stored.version)
            assertEquals(ReviewStatus.NOT_REQUIRED, stored.state.reviewStatus)
            assertEquals(CategoryMissingReason.EXTRACTION_UNRESOLVED, stored.state.categoryMissingReason)
            assertNull(stored.state.categoryId)
            assertNull(stored.categorySource)
            assertNull(stored.nameSource)
            assertNull(stored.imageSource)
            assertNull(stored.state.manualCompletionAt)
            assertNull(stored.purposeId)
            assertEquals(ValueSource.UNASSIGNED, stored.purposeSource)
            assertEquals(emptySet(), stored.userOverrideFields)
            source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("select generation, execution_token, lease_until, claimed_item_version from analysis_jobs where wishlist_item_id='$item'").use { rows ->
                        check(rows.next())
                        assertEquals(1, rows.getInt("generation"))
                        assertNull(rows.getObject("execution_token"))
                        assertNull(rows.getObject("lease_until"))
                        assertNull(rows.getObject("claimed_item_version"))
                    }
                }
            }
        }
    }
}
