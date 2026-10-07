package app.wishlist.shared.data.local

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.wishlist.shared.core.*
import app.wishlist.shared.data.fake.error
import app.wishlist.shared.data.fake.successValue
import app.wishlist.shared.model.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LocalStoreContractTest {
    private suspend fun MutableAuthSession.login(account: String): SessionSnapshot {
        changeAccount(account)
        return state.value
    }

    @Test fun pending_survives_close_and_reopen_with_exact_fields() = runTest {
        withHarness { h ->
            val a = h.session.login("A")
            val saved = submission(binding = "A").copy(
                submissionStatus = SubmissionStatus.SUBMITTING, serverItemId = itemId,
                lastSubmissionError = ClientError(ErrorKind.CONFLICT, "C", "req-1", 9, 30),
            )
            h.store.saveSubmission(saved).successValue()
            h.reopen()
            assertEquals(listOf(saved), h.store.pending().successValue())
            assertEquals(a, h.session.state.value)
        }
    }

    @Test fun cached_item_round_trips_exactly_across_reopen() = runTest {
        withHarness { h ->
            val a = h.session.login("A")
            h.store.upsertItem(a, item(3)).successValue()
            h.reopen()
            assertEquals(item(3), h.store.cachedItem(a, itemId).successValue())
        }
    }

    @Test fun pending_visibility_follows_account_binding() = runTest {
        withHarness { h ->
            val unbound = "00000000-0000-0000-0000-0000000000c1"
            val forB = "00000000-0000-0000-0000-0000000000c2"
            h.store.saveSubmission(submission(unbound)).successValue()
            h.session.login("A")
            h.store.saveSubmission(submission(submissionId, "A")).successValue()
            assertEquals(setOf(unbound, submissionId), h.store.pending().successValue().map { it.clientSubmissionId }.toSet())
            h.session.login("B")
            h.store.saveSubmission(submission(forB, "B")).successValue()
            assertEquals(setOf(unbound, forB), h.store.pending().successValue().map { it.clientSubmissionId }.toSet())
            h.session.changeAccount(null)
            assertEquals(listOf(unbound), h.store.pending().successValue().map { it.clientSubmissionId })
            h.session.login("A")
            assertEquals(setOf(unbound, submissionId), h.store.pending().successValue().map { it.clientSubmissionId }.toSet())
        }
    }

    @Test fun save_submission_rejects_foreign_binding() = runTest {
        withHarness { h ->
            h.session.login("A")
            val failure = h.store.saveSubmission(submission(binding = "B")).error()
            assertEquals(ErrorKind.VALIDATION, failure.kind)
            assertEquals("ACCOUNT_BINDING_MISMATCH", failure.code)
            h.session.changeAccount(null)
            assertEquals("ACCOUNT_BINDING_MISMATCH", h.store.saveSubmission(submission(binding = "A")).error().code)
            h.store.saveSubmission(submission(binding = null)).successValue()
        }
    }

    @Test fun item_cache_is_isolated_per_account_and_survives_relogin() = runTest {
        withHarness { h ->
            val a1 = h.session.login("A")
            h.store.upsertItem(a1, item(2)).successValue()
            val b = h.session.login("B")
            assertNull(h.store.cachedItem(b, itemId).successValue())
            h.store.upsertItem(b, item(5, name = "B")).successValue()
            val a2 = h.session.login("A")
            assertEquals(2, h.store.cachedItem(a2, itemId).successValue()!!.version)
            assertEquals(ErrorKind.SESSION_CHANGED, h.store.cachedItem(a1, itemId).error().kind)
            assertEquals(ErrorKind.SESSION_CHANGED, h.store.upsertItem(a1, item(9)).error().kind)
            assertEquals(2, h.store.cachedItem(a2, itemId).successValue()!!.version)
        }
    }

    @Test fun logged_out_item_operations_are_unauthenticated() = runTest {
        withHarness { h ->
            val out = h.session.state.value
            assertEquals(ErrorKind.UNAUTHENTICATED, h.store.upsertItem(out, item()).error().kind)
            assertEquals(ErrorKind.UNAUTHENTICATED, h.store.cachedItem(out, itemId).error().kind)
        }
    }

    @Test fun lower_and_same_versions_are_skipped_and_only_higher_replaces() = runTest {
        withHarness { h ->
            val a = h.session.login("A")
            h.store.upsertItem(a, item(5, name = "five")).successValue()
            h.store.upsertItem(a, item(4, name = "four")).successValue()
            assertEquals("five", h.store.cachedItem(a, itemId).successValue()!!.product.name)
            h.store.upsertItem(a, item(5, name = "same")).successValue()
            assertEquals("five", h.store.cachedItem(a, itemId).successValue()!!.product.name)
            h.store.upsertItem(a, item(6, name = "six")).successValue()
            assertEquals(6, h.store.cachedItem(a, itemId).successValue()!!.version)
            assertEquals("six", h.store.cachedItem(a, itemId).successValue()!!.product.name)
        }
    }

    @Test fun late_not_found_does_not_remove_newer_cache() = runTest {
        withHarness { h ->
            val sessionA = h.session.login("A")
            h.store.upsertItem(sessionA, item(version = 7))
            h.store.upsertItem(sessionA, item(version = 8))
            h.store.removeCachedItem(sessionA, itemId, throughVersion = 7)
            assertEquals(8, h.store.cachedItem(sessionA, itemId).successValue()!!.version)
        }
    }

    @Test fun remove_through_version_removes_equal_or_older_and_rejects_stale() = runTest {
        withHarness { h ->
            val a = h.session.login("A")
            h.store.upsertItem(a, item(7)).successValue()
            h.store.removeCachedItem(a, itemId, 7).successValue()
            assertNull(h.store.cachedItem(a, itemId).successValue())
            h.store.upsertItem(a, item(8)).successValue()
            h.session.login("A")
            assertEquals(ErrorKind.SESSION_CHANGED, h.store.removeCachedItem(a, itemId, 9).error().kind)
            assertEquals(8, h.store.cachedItem(h.session.state.value, itemId).successValue()!!.version)
        }
    }

    @Test fun accept_upserts_cache_and_deletes_pending_atomically() = runTest {
        withHarness { h ->
            val a = h.session.login("A")
            h.store.saveSubmission(submission(binding = "A")).successValue()
            h.store.accept(a, submissionId, item(1)).successValue()
            assertEquals(item(1), h.store.cachedItem(a, itemId).successValue())
            assertEquals(emptyList(), h.store.pending().successValue())
            h.reopen()
            assertEquals(emptyList(), h.store.pending().successValue())
            assertEquals(1, h.store.cachedItem(a, itemId).successValue()!!.version)
        }
    }

    @Test fun accept_deleted_replay_removes_cache_and_pending() = runTest {
        withHarness { h ->
            val a = h.session.login("A")
            h.store.upsertItem(a, item(3)).successValue()
            h.store.saveSubmission(submission(binding = "A")).successValue()
            h.store.accept(a, submissionId, item(4).copy(lifecycleStatus = LifecycleStatus.DELETED)).successValue()
            assertNull(h.store.cachedItem(a, itemId).successValue())
            assertEquals(emptyList(), h.store.pending().successValue())
        }
    }

    @Test fun accept_failure_after_cache_write_rolls_back_cache_and_pending() = runTest {
        var armed = false
        withHarness(hook = { if (armed) throw IllegalStateException("injected") }) { h ->
            val a = h.session.login("A")
            h.store.upsertItem(a, item(1, name = "old")).successValue()
            h.store.saveSubmission(submission(binding = "A")).successValue()
            armed = true
            val failure = h.store.accept(a, submissionId, item(2, name = "new")).error()
            assertEquals(ErrorKind.UNAVAILABLE, failure.kind)
            assertEquals("LOCAL_STORE_FAILURE", failure.code)
            armed = false
            assertEquals("old", h.store.cachedItem(a, itemId).successValue()!!.product.name)
            assertEquals(listOf(submissionId), h.store.pending().successValue().map { it.clientSubmissionId })
        }
    }

    @Test fun accept_rejects_stale_snapshot_and_other_generation_of_same_account() = runTest {
        withHarness { h ->
            val a1 = h.session.login("A")
            h.store.saveSubmission(submission(binding = "A")).successValue()
            val b = h.session.login("B")
            assertEquals(ErrorKind.SESSION_CHANGED, h.store.accept(a1, submissionId, item()).error().kind)
            val a2 = h.session.login("A")
            assertEquals(ErrorKind.SESSION_CHANGED, h.store.accept(a1, submissionId, item()).error().kind)
            assertEquals(ErrorKind.SESSION_CHANGED, h.store.accept(b, submissionId, item()).error().kind)
            assertNull(h.store.cachedItem(a2, itemId).successValue())
            assertEquals(1, h.store.pending().successValue().size)
        }
    }

    @Test fun accept_requires_pending_bound_to_current_account() = runTest {
        withHarness { h ->
            h.store.saveSubmission(submission(binding = null)).successValue()
            val a = h.session.login("A")
            val unbound = h.store.accept(a, submissionId, item()).error()
            assertEquals(ErrorKind.VALIDATION, unbound.kind)
            assertEquals("ACCOUNT_BINDING_MISMATCH", unbound.code)
            h.session.login("B")
            h.store.saveSubmission(submission("00000000-0000-0000-0000-0000000000d1", "B")).successValue()
            val b = h.session.state.value
            val other = h.store.accept(b, "00000000-0000-0000-0000-0000000000d1", item()).successValue()
            assertEquals(Unit, other)
            h.session.login("A")
            h.store.saveSubmission(submission("00000000-0000-0000-0000-0000000000d2", "A")).successValue()
            h.session.login("B")
            assertEquals("ACCOUNT_BINDING_MISMATCH",
                h.store.accept(h.session.state.value, "00000000-0000-0000-0000-0000000000d2", item(id = "00000000-0000-0000-0000-0000000000a2")).error().code)
            assertNull(h.store.cachedItem(h.session.state.value, "00000000-0000-0000-0000-0000000000a2").successValue())
        }
    }

    @Test fun clear_current_cache_preserves_pending_and_other_accounts() = runTest {
        withHarness { h ->
            val a = h.session.login("A")
            h.store.upsertItem(a, item(1)).successValue()
            h.store.saveSubmission(submission(binding = "A")).successValue()
            val b = h.session.login("B")
            h.store.upsertItem(b, item(2)).successValue()
            h.store.clearCurrentCache().successValue()
            assertNull(h.store.cachedItem(b, itemId).successValue())
            val a2 = h.session.login("A")
            assertEquals(1, h.store.cachedItem(a2, itemId).successValue()!!.version)
            h.store.clearCurrentCache().successValue()
            assertNull(h.store.cachedItem(a2, itemId).successValue())
            assertEquals(1, h.store.pending().successValue().size)
            h.session.changeAccount(null)
            h.store.clearCurrentCache().successValue()
        }
    }

    @Test fun driver_failure_surfaces_local_store_failure() = runTest {
        val session = MutableAuthSession()
        val path = newTestDbPath()
        val real = openTestDriver(path)
        try {
            session.changeAccount("A")
            val a = session.state.value
            val store = SqlLocalStore(session, ThrowingDriver(real))
            val failure = store.cachedItem(a, itemId).error()
            assertEquals(ErrorKind.UNAVAILABLE, failure.kind)
            assertEquals("LOCAL_STORE_FAILURE", failure.code)
            assertEquals("LOCAL_STORE_FAILURE", store.saveSubmission(submission()).error().code)
        } finally { real.close(); deleteTestDb(path) }
    }
}

private class ThrowingDriver(private val inner: SqlDriver) : SqlDriver by inner {
    override fun <R> executeQuery(
        identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>, parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = throw IllegalStateException("driver down")

    override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?):
        QueryResult<Long> = throw IllegalStateException("driver down")
}
