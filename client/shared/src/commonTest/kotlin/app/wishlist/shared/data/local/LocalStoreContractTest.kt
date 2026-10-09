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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class LocalStoreContractTest {
    private suspend fun MutableAuthSession.login(account: String): SessionSnapshot {
        changeAccount(account)
        return state.value
    }

    @Test fun pending_survives_close_and_reopen_with_exact_fields() = runTest {
        withHarness { h ->
            val a = h.session.login("A")
            val saved = submission(binding = "A").copy(
                submissionStatus = SubmissionStatus.SUBMITTING,
                sharedAt = Instant.parse("2026-10-07T01:02:03.123456Z"),
                lastSubmissionError = ClientError(ErrorKind.CONFLICT, "C", "req-1", 9, 30),
                retryAfter = Instant.parse("2026-10-07T01:03:03.000001Z"),
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

    @Test fun purposeDisplayFieldsRoundTripThroughCache() = runTest {
        withHarness { h ->
            val a = h.session.login("A")
            h.store.upsertItem(a, item(1)).successValue()
            assertEquals(
                ItemPurpose("P1", ValueSource.USER, "출퇴근 헤드폰", "CORAL", "HEART"),
                h.store.cachedItem(a, itemId).successValue()?.purpose,
            )
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

    @Test fun resave_cannot_rebind_or_unbind_a_row_owned_by_another_binding() = runTest {
        withHarness { h ->
            h.session.login("A")
            h.store.saveSubmission(submission(binding = "A")).successValue()
            // bound -> null (stale unbound writer)
            assertEquals("ACCOUNT_BINDING_MISMATCH", h.store.saveSubmission(submission(binding = null)).error().code)
            h.session.login("B")
            // A's row re-saved as B's, or unbound, by another account
            assertEquals("ACCOUNT_BINDING_MISMATCH", h.store.saveSubmission(submission(binding = "B")).error().code)
            assertEquals("ACCOUNT_BINDING_MISMATCH", h.store.saveSubmission(submission(binding = null)).error().code)
            assertEquals(emptyList(), h.store.pending().successValue())
            h.session.login("A")
            assertEquals(listOf("A"), h.store.pending().successValue().map { it.accountBinding })
        }
    }

    // C3: a re-save of the same key and URL keeps the original row (binding happens in prepareFlush).
    @Test fun resave_of_same_key_and_url_keeps_the_original_row() = runTest {
        withHarness { h ->
            h.store.saveSubmission(submission(binding = null)).successValue()
            h.store.saveSubmission(submission(binding = null)).successValue()
            h.session.login("A")
            h.store.saveSubmission(submission(binding = "A")).successValue()
            h.store.saveSubmission(submission(binding = "A", status = SubmissionStatus.SUBMITTING)).successValue()
            val saved = h.store.pending().successValue().single()
            assertNull(saved.accountBinding)
            assertEquals(SubmissionStatus.PENDING, saved.submissionStatus)
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

    @Test fun lower_version_is_skipped_and_same_or_higher_replaces() = runTest {
        withHarness { h ->
            val a = h.session.login("A")
            h.store.upsertItem(a, item(5, name = "five")).successValue()
            h.store.upsertItem(a, item(4, name = "four")).successValue()
            assertEquals("five", h.store.cachedItem(a, itemId).successValue()!!.product.name)
            h.store.upsertItem(a, item(5, name = "same")).successValue()
            assertEquals("same", h.store.cachedItem(a, itemId).successValue()!!.product.name)
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
            val other = h.store.accept(b, "00000000-0000-0000-0000-0000000000d1",
                item().copy(clientSubmissionId = "00000000-0000-0000-0000-0000000000d1")).successValue()
            assertEquals(Unit, other)
            h.session.login("A")
            h.store.saveSubmission(submission("00000000-0000-0000-0000-0000000000d2", "A")).successValue()
            h.session.login("B")
            assertEquals("ACCOUNT_BINDING_MISMATCH",
                h.store.accept(h.session.state.value, "00000000-0000-0000-0000-0000000000d2",
                    item(id = "00000000-0000-0000-0000-0000000000a2").copy(clientSubmissionId = "00000000-0000-0000-0000-0000000000d2")).error().code)
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
            val store = SqlLocalStore(session, ThrowingDriver(real).asLazy())
            val failure = store.cachedItem(a, itemId).error()
            assertEquals(ErrorKind.UNAVAILABLE, failure.kind)
            assertEquals("LOCAL_STORE_FAILURE", failure.code)
            assertEquals("LOCAL_STORE_FAILURE", store.saveSubmission(submission()).error().code)
            assertEquals("LOCAL_STORE_FAILURE", store.importSubmission(submission()).error().code)
            assertEquals("LOCAL_STORE_FAILURE", store.prepareFlush(a).error().code)
            assertEquals("LOCAL_STORE_FAILURE", store.readAppState("k").error().code)
        } finally { real.close(); deleteTestDb(path) }
    }

    @Test fun driver_open_failure_surfaces_local_store_failure() = runTest {
        val session = MutableAuthSession()
        val store = SqlLocalStore(session, LazyDriver(open = { throw IllegalStateException("no disk") }, io = kotlinx.coroutines.Dispatchers.Unconfined))
        assertEquals("LOCAL_STORE_FAILURE", store.pending().error().code)
    }

    // --- C3: ordering, key guard, accept match, flush preparation, app state -----------------

    @Test fun pendingOrdersBySubMillisecondInstantThenKey() = runStoreTest { h ->
        val base = Instant.parse("2026-10-07T00:00:00Z")
        h.store.saveSubmission(submission(id = UUID_B, sharedAt = base + 500.milliseconds)).successValue()
        h.store.saveSubmission(submission(id = UUID_C, sharedAt = base)).successValue()
        h.store.saveSubmission(submission(id = UUID_A, sharedAt = base + 500.milliseconds)).successValue()
        assertEquals(listOf(UUID_C, UUID_A, UUID_B), h.store.pending().successValue().map { it.clientSubmissionId })
    }

    @Test fun sameKeyDifferentUrlIsRejectedAndOriginalKept() = runStoreTest { h ->
        h.store.saveSubmission(submission(id = UUID_A, url = "https://a.example/1")).successValue()
        assertEquals(ErrorKind.CONFLICT, h.store.saveSubmission(submission(id = UUID_A, url = "https://a.example/2")).failureKind())
        assertEquals("SUBMISSION_KEY_REUSED", h.store.importSubmission(submission(id = UUID_A, url = "https://a.example/2")).failureCode())
        assertEquals("https://a.example/1", h.store.pending().successValue().single().sourceUrl)
    }

    @Test fun sameKeySameUrlIsNoOpKeepingStatus() = runStoreTest { h ->
        h.login("A")
        h.store.saveSubmission(submission(id = UUID_A, binding = "A")).successValue()
        h.store.markSubmission(h.snapshot(), UUID_A, SubmissionStatus.FAILED, ClientError(ErrorKind.VALIDATION), null).successValue()
        h.store.saveSubmission(submission(id = UUID_A, binding = "A")).successValue()
        assertEquals(SubmissionStatus.FAILED, h.store.pending().successValue().single().submissionStatus)
    }

    @Test fun markSubmissionRecordsErrorAndRetryAfterOnlyForSnapshotAccount() = runStoreTest { h ->
        h.login("A")
        h.store.saveSubmission(submission(id = UUID_A, binding = "A")).successValue()
        h.store.saveSubmission(submission(id = UUID_B)).successValue()
        val retryAt = Instant.parse("2026-10-07T00:01:00.250Z")
        val error = ClientError(ErrorKind.RATE_LIMITED, "SLOW_DOWN", "req-9", null, 60)
        h.store.markSubmission(h.snapshot(), UUID_A, SubmissionStatus.PENDING, error, retryAt).successValue()
        val marked = h.store.pending().successValue().first { it.clientSubmissionId == UUID_A }
        assertEquals(error, marked.lastSubmissionError)
        assertEquals(retryAt, marked.retryAfter)
        // Unbound rows and other accounts' rows are never touched; unknown keys are NOT_FOUND.
        assertEquals("ACCOUNT_BINDING_MISMATCH",
            h.store.markSubmission(h.snapshot(), UUID_B, SubmissionStatus.SUBMITTING, null, null).failureCode())
        assertEquals(ErrorKind.NOT_FOUND,
            h.store.markSubmission(h.snapshot(), UUID_C, SubmissionStatus.SUBMITTING, null, null).failureKind())
        val stale = h.snapshot()
        h.login("B")
        assertEquals(ErrorKind.SESSION_CHANGED,
            h.store.markSubmission(stale, UUID_A, SubmissionStatus.FAILED, null, null).failureKind())
        assertEquals("ACCOUNT_BINDING_MISMATCH",
            h.store.markSubmission(h.snapshot(), UUID_A, SubmissionStatus.FAILED, null, null).failureCode())
        h.login("A")
        assertEquals(SubmissionStatus.PENDING,
            h.store.pending().successValue().first { it.clientSubmissionId == UUID_A }.submissionStatus)
    }

    @Test fun acceptRejectsItemOfAnotherSubmission() = runStoreTest { h ->
        h.login("A")
        h.store.saveSubmission(submission(id = UUID_A, binding = "A")).successValue()
        val wrong = itemFixture(clientSubmissionId = UUID_B)
        assertEquals("SUBMISSION_ITEM_MISMATCH", h.store.accept(h.snapshot(), UUID_A, wrong).failureCode())
        assertNull(h.store.cachedItem(h.snapshot(), wrong.id).successValue())
        assertEquals(1, h.store.pending().successValue().size)
    }

    @Test fun acceptMatchesKeyCaseInsensitively() = runStoreTest { h ->
        h.login("A")
        h.store.saveSubmission(submission(id = UUID_A, binding = "A")).successValue()
        h.store.accept(h.snapshot(), UUID_A, itemFixture(clientSubmissionId = UUID_A.uppercase())).successValue()
        assertTrue(h.store.pending().successValue().isEmpty())
    }

    @Test fun prepareFlushResetsSubmittingAndBindsUnboundOnlyToSnapshotAccount() = runStoreTest { h ->
        h.store.saveSubmission(submission(id = UUID_A)).successValue()            // unbound
        h.login("B"); h.store.saveSubmission(submission(id = UUID_B, binding = "B")).successValue()
        h.login("A")
        h.store.importSubmission(submission(id = UUID_C, binding = "A", status = SubmissionStatus.SUBMITTING)).successValue()
        val ready = h.store.prepareFlush(h.snapshot()).successValue()
        assertEquals(setOf(UUID_A, UUID_C), ready.map { it.clientSubmissionId }.toSet())
        assertTrue(ready.all { it.accountBinding == "A" && it.submissionStatus == SubmissionStatus.PENDING })
        h.login("B")
        assertEquals(listOf(UUID_B), h.store.pending().successValue().map { it.clientSubmissionId })
    }

    @Test fun prepareFlushRejectsStaleOrLoggedOutSnapshot() = runStoreTest { h ->
        h.store.saveSubmission(submission(id = UUID_A)).successValue()
        assertEquals(ErrorKind.UNAUTHENTICATED, h.store.prepareFlush(h.snapshot()).failureKind())
        val stale = h.snapshot()
        h.login("A")
        assertEquals(ErrorKind.SESSION_CHANGED, h.store.prepareFlush(stale).failureKind())
        assertNull(h.store.pending().successValue().single().accountBinding)
    }

    @Test fun importKeepsShareTimeBindingOfOtherAccount() = runStoreTest { h ->
        h.login("A")
        h.store.importSubmission(submission(id = UUID_A, binding = "B")).successValue()
        assertTrue(h.store.pending().successValue().isEmpty())
        h.login("B")
        assertEquals(1, h.store.pending().successValue().size)
    }

    @Test fun processingItemsReturnsOnlyActiveProcessingOfAccount() = runStoreTest { h ->
        val ids = (1..4).map { "00000000-0000-4000-8000-00000000010$it" }
        h.login("B")
        h.store.upsertItem(h.snapshot(), itemFixture(analysis = AnalysisStatus.PROCESSING, id = ids[3])).successValue()
        h.login("A")
        val a = h.snapshot()
        h.store.upsertItem(a, itemFixture(analysis = AnalysisStatus.READY, id = ids[0])).successValue()
        h.store.upsertItem(a, itemFixture(analysis = AnalysisStatus.PROCESSING, id = ids[1])).successValue()
        h.store.upsertItem(a, itemFixture(
            analysis = AnalysisStatus.PROCESSING, lifecycle = LifecycleStatus.DELETED, id = ids[2],
        )).successValue()
        assertEquals(listOf(ids[1]), h.store.processingItems(a).successValue().map { it.id })
        h.login("B")
        assertEquals(listOf(ids[3]), h.store.processingItems(h.snapshot()).successValue().map { it.id })
        assertEquals(ErrorKind.SESSION_CHANGED, h.store.processingItems(a).failureKind())
    }

    @Test fun getUpsertReplacesTheSameVersion() = runTest {
        withHarness { h ->
            val a = h.session.login("A")
            h.store.upsertItem(a, item(version = 3, name = "옛 이름")).successValue()
            h.store.upsertItem(a, item(version = 3, name = "새 이름")).successValue()
            assertEquals("새 이름", h.store.cachedItem(a, itemId).successValue()!!.product.name)
        }
    }

    @Test fun getUpsertStillIgnoresAnOlderVersion() = runTest {
        withHarness { h ->
            val a = h.session.login("A")
            h.store.upsertItem(a, item(version = 3, name = "v3")).successValue()
            h.store.upsertItem(a, item(version = 2, name = "v2")).successValue()
            val cached = h.store.cachedItem(a, itemId).successValue()!!
            assertEquals(3, cached.version)
            assertEquals("v3", cached.product.name)
        }
    }

    @Test fun acceptKeepsANewerSameVersionCacheRow() = runTest {
        withHarness { h ->
            val a = h.session.login("A")
            h.store.upsertItem(a, item(version = 3, name = "GET 결과")).successValue()
            h.store.saveSubmission(submission(binding = "A")).successValue()
            h.store.accept(a, submissionId, item(version = 3, name = "멱등 응답")).successValue()
            assertEquals("GET 결과", h.store.cachedItem(a, itemId).successValue()!!.product.name)
            assertEquals(emptyList(), h.store.pending().successValue())
        }
    }

    private fun itemRowCount(h: StoreHarness): Long =
        h.driver.executeQuery(null, "SELECT COUNT(*) FROM item_cache", { c ->
            app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getLong(0) else 0L)
        }, 0).value ?: 0L

    @Test fun undecodableRowIsDroppedOnRead() = runTest {
        withHarness { h ->
            val a = h.session.login("A")
            h.store.upsertItem(a, item(version = 3)).successValue()
            h.driver.execute(null, "UPDATE item_cache SET analysis_status = 'NOT_A_STATUS'", 0)
            assertNull(h.store.cachedItem(a, itemId).successValue())
            assertEquals(0L, itemRowCount(h))
        }
    }

    @Test fun undecodableProcessingRowDoesNotHideTheOthers() = runTest {
        withHarness { h ->
            val a = h.session.login("A")
            val good = "00000000-0000-4000-8000-000000000201"
            val bad = "00000000-0000-4000-8000-000000000202"
            h.store.upsertItem(a, itemFixture(analysis = AnalysisStatus.PROCESSING, id = good)).successValue()
            h.store.upsertItem(a, itemFixture(analysis = AnalysisStatus.PROCESSING, id = bad)).successValue()
            h.driver.execute(null, "UPDATE item_cache SET created_at = 'broken' WHERE item_id = '$bad'", 0)
            assertEquals(listOf(good), h.store.processingItems(a).successValue().map { it.id })
            assertEquals(1L, itemRowCount(h))
        }
    }

    @Test fun uppercaseIdReadsAndRemovesTheCanonicalRow() = runTest {
        withHarness { h ->
            val a = h.session.login("A")
            h.store.upsertItem(a, item(version = 3, id = UUID_A)).successValue()
            assertEquals(UUID_A, h.store.cachedItem(a, UUID_A.uppercase()).successValue()!!.id)
            h.store.removeCachedItem(a, UUID_A.uppercase(), 3).successValue()
            assertNull(h.store.cachedItem(a, UUID_A).successValue())
        }
    }

    @Test fun cachedItemBySubmissionFindsTheAcceptedItem() = runTest {
        withHarness { h ->
            val a = h.session.login("A")
            h.store.saveSubmission(submission(id = UUID_B, binding = "A")).successValue()
            h.store.accept(a, UUID_B, item(version = 1).copy(clientSubmissionId = UUID_B)).successValue()
            assertEquals(itemId, h.store.cachedItemBySubmission(a, UUID_B).successValue()!!.id)
            assertEquals(itemId, h.store.cachedItemBySubmission(a, UUID_B.uppercase()).successValue()!!.id)
        }
    }

    @Test fun cachedItemBySubmissionIsNullWhenAbsent() = runTest {
        withHarness { h ->
            val a = h.session.login("A")
            assertNull(h.store.cachedItemBySubmission(a, submissionId).successValue())
        }
    }

    @Test fun cachedItemBySubmissionNeedsAnAccount() = runTest {
        withHarness { h ->
            assertEquals(ErrorKind.UNAUTHENTICATED, h.store.cachedItemBySubmission(h.snapshot(), submissionId).failureKind())
        }
    }

    @Test fun appStateRoundTripsAndDeletes() = runStoreTest { h ->
        h.store.writeAppState("k", "v").successValue()
        assertEquals("v", h.store.readAppState("k").successValue())
        h.store.writeAppState("k", null).successValue()
        assertNull(h.store.readAppState("k").successValue())
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
