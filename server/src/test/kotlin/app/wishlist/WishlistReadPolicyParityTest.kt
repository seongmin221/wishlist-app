package app.wishlist

import app.category.*
import app.testutil.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class WishlistReadPolicyParityTest {
    @Test fun sql_policy_matches_all_valid_database_combinations() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val custom = CategoryService(source).create(owner, UUID.randomUUID(), "G003", CategoryInput("custom", null, emptyList())).category.id
        val names = listOf(null, "", " ", "\t\n", "\u00a0", "\u2007", "\u202f", "\u2028\u2029", "\u0085", "\u200b", "상품", " \t상품\u00a0")
        val time = Instant.parse("2026-10-07T10:00:00Z")
        val fixtures = buildList {
            for (analysis in AnalysisStatus.entries) for (review in ReviewStatus.entries)
            for (reason in listOf(null) + CategoryMissingReason.entries) for (name in names)
            for (category in listOf(null, "C026", custom.toString())) for (manual in listOf(null, time)) {
                if (category != null && reason != null) continue
                for (lifecycle in LifecycleStatus.entries) add(ReadFixture(ReadPosition(time, UUID.randomUUID()),
                    WishlistItemState(analysis, review, lifecycle, name, category, reason, manual),
                    customCategoryId = custom.takeIf { category == custom.toString() }))
            }
            for (c in WishlistReadPredicates.POLICY_WHITESPACE) add(ReadFixture(ReadPosition(time, UUID.randomUUID()),
                WishlistItemState(AnalysisStatus.READY, ReviewStatus.PENDING, LifecycleStatus.ACTIVE, "$c", "C026", null, null)))
        }
        assertEquals(10080 + 28, fixtures.size)
        source.connection.use { c -> insertReadFixtures(c, owner, fixtures) }
        val expected = fixtures.associateBy { it.position.id }
        val action = WishlistReadPredicates.requiredAction()
        val visible = WishlistReadPredicates.categoryVisible()
        source.connection.use { c -> c.prepareStatement("""select id, required, visible, ${WishlistReadPredicates.homeGroup("required")} grp
            from (select i.id, ${action.sql} required, ${visible.sql} visible from wishlist_items i where owner_id=?) evaluated""").use { s ->
            var parameter = 1
            for (v in action.parameters + visible.parameters) s.setObject(parameter++, v)
            s.setObject(parameter, owner)
            s.executeQuery().use { r ->
                var checked = 0
                while (r.next()) {
                    val state = expected.getValue(r.getObject("id", UUID::class.java)).state
                    val policy = WishlistItemPolicy.evaluate(state)
                    assertEquals(policy.requiredAction.name, r.getString("required"), state.toString())
                    assertEquals(policy.homeActionGroup?.name, r.getString("grp"), state.toString())
                    assertEquals(state.lifecycleStatus == LifecycleStatus.ACTIVE && !state.productName.isNullOrBlank(), r.getBoolean("visible"), state.toString())
                    checked++
                }
                assertEquals(fixtures.size, checked)
            }
        } }
    }

    @Test fun raw_category_blank_expression_matches_policy_without_bypassing_foreign_key() = withAnalysisDatabase { source ->
        val action = WishlistReadPredicates.requiredAction()
        val state = WishlistItemState(AnalysisStatus.READY, ReviewStatus.PENDING, LifecycleStatus.ACTIVE, "\u0085", "\t\u00a0", null, null)
        source.connection.use { c -> c.prepareStatement("""select ${action.sql} required from
            (select 'ACTIVE'::text lifecycle_status, 'READY'::text analysis_status, 'PENDING'::text review_status,
              ?::text product_name, ?::text category_id, null::uuid custom_category_id, null::text category_missing_reason) i""").use { s ->
            var n = 1
            for (v in action.parameters) s.setObject(n++, v)
            s.setString(n++, state.productName); s.setString(n, state.categoryId)
            s.executeQuery().use { r -> assertTrue(r.next()); assertEquals(WishlistItemPolicy.evaluate(state).requiredAction.name, r.getString(1)) }
        } }
    }
}
