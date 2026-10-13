package app.wishlist.shared.data.local

import app.wishlist.shared.core.*
import app.wishlist.shared.data.fake.error
import app.wishlist.shared.data.fake.successValue
import app.wishlist.shared.model.WishlistItem
import app.wishlist.shared.repository.GetItemRepository
import app.wishlist.shared.repository.LocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

private class ScriptedDelegate : GetItemRepository {
    private val queue = ArrayDeque<suspend () -> ClientResult<WishlistItem>>()
    var calls = 0
    fun enqueueSuccess(item: WishlistItem, during: suspend () -> Unit = {}) =
        queue.addLast { during(); ClientResult.Success(item) }
    fun enqueueNotFound(during: suspend () -> Unit = {}) =
        queue.addLast { during(); ClientResult.Failure(ClientError(ErrorKind.NOT_FOUND)) }
    fun enqueueError(kind: ErrorKind) = queue.addLast { ClientResult.Failure(ClientError(kind)) }
    override suspend fun get(id: String): ClientResult<WishlistItem> { calls++; return queue.removeFirst()() }
}

private class FailingStore(private val inner: LocalStore, var failRead: Boolean = false, var failWrite: Boolean = false,
    var cancelRead: Boolean = false) : LocalStore by inner {
    private val failure = ClientResult.Failure(ClientError(ErrorKind.UNAVAILABLE, "LOCAL_STORE_FAILURE"))
    override suspend fun cachedItem(snapshot: SessionSnapshot, id: String): ClientResult<WishlistItem?> {
        if (cancelRead) throw CancellationException("cancelled")
        return if (failRead) failure else inner.cachedItem(snapshot, id)
    }
    override suspend fun upsertItem(snapshot: SessionSnapshot, item: WishlistItem) =
        if (failWrite) failure else inner.upsertItem(snapshot, item)
    override suspend fun removeCachedItem(snapshot: SessionSnapshot, id: String, throughVersion: Int) =
        if (failWrite) failure else inner.removeCachedItem(snapshot, id, throughVersion)
}

class CachedGetItemRepositoryTest {
    @Test fun cached_repository_owns_success_and_not_found_sync() = runTest {
        withHarness { h ->
            h.session.changeAccount("A")
            val sessionA = h.session.state.value
            val store = h.store
            val delegate = ScriptedDelegate()
            val cachedRepository = CachedGetItemRepository(delegate, store, h.session)
            delegate.enqueueSuccess(item(version = 7))
            cachedRepository.get(itemId)
            assertEquals(7, store.cachedItem(sessionA, itemId).successValue()!!.version)
            delegate.enqueueNotFound()
            cachedRepository.get(itemId)
            assertNull(store.cachedItem(sessionA, itemId).successValue())
        }
    }

    @Test fun same_version_response_is_returned_and_replaces_the_cache() = runTest {
        withHarness { h ->
            h.session.changeAccount("A")
            val a = h.session.state.value
            val repo = CachedGetItemRepository(ScriptedDelegate().also {
                it.enqueueSuccess(item(3, name = "old")); it.enqueueSuccess(item(3, name = "renamed category"))
            }, h.store, h.session)
            repo.get(itemId).successValue()
            val second = repo.get(itemId).successValue()
            assertEquals("renamed category", second.product.name)
            assertEquals("renamed category", h.store.cachedItem(a, itemId).successValue()!!.product.name)
        }
    }

    @Test fun not_found_without_cache_is_a_noop_and_returns_not_found() = runTest {
        withHarness { h ->
            h.session.changeAccount("A")
            val delegate = ScriptedDelegate().also { it.enqueueNotFound() }
            val result = CachedGetItemRepository(delegate, h.store, h.session).get(itemId)
            assertEquals(ErrorKind.NOT_FOUND, result.error().kind)
            assertNull(h.store.cachedItem(h.session.state.value, itemId).successValue())
        }
    }

    @Test fun general_error_keeps_existing_cache() = runTest {
        withHarness { h ->
            h.session.changeAccount("A")
            val a = h.session.state.value
            h.store.upsertItem(a, item(4)).successValue()
            val delegate = ScriptedDelegate().also { it.enqueueError(ErrorKind.NETWORK) }
            val result = CachedGetItemRepository(delegate, h.store, h.session).get(itemId)
            assertEquals(ErrorKind.NETWORK, result.error().kind)
            assertEquals(4, h.store.cachedItem(a, itemId).successValue()!!.version)
        }
    }

    @Test fun late_not_found_keeps_newer_version_that_arrived_meanwhile() = runTest {
        withHarness { h ->
            h.session.changeAccount("A")
            val a = h.session.state.value
            h.store.upsertItem(a, item(7)).successValue()
            val delegate = ScriptedDelegate().also {
                it.enqueueNotFound { h.store.upsertItem(a, item(8)).successValue() }
            }
            val result = CachedGetItemRepository(delegate, h.store, h.session).get(itemId)
            assertEquals(ErrorKind.NOT_FOUND, result.error().kind)
            assertEquals(8, h.store.cachedItem(a, itemId).successValue()!!.version)
        }
    }

    @Test fun account_change_during_delegate_rejects_result_and_cache_commit() = runTest {
        withHarness { h ->
            h.session.changeAccount("A")
            val a = h.session.state.value
            val delegate = ScriptedDelegate().also {
                it.enqueueSuccess(item(5)) { h.session.changeAccount("B") }
            }
            val result = CachedGetItemRepository(delegate, h.store, h.session).get(itemId)
            assertEquals(ErrorKind.SESSION_CHANGED, result.error().kind)
            h.session.changeAccount("A")
            assertNull(h.store.cachedItem(h.session.state.value, itemId).successValue())
            assertEquals(ErrorKind.SESSION_CHANGED, h.store.cachedItem(a, itemId).error().kind)
        }
    }

    @Test fun relogin_same_account_during_delegate_also_rejects() = runTest {
        withHarness { h ->
            h.session.changeAccount("A")
            val delegate = ScriptedDelegate().also {
                it.enqueueSuccess(item(5)) { h.session.changeAccount("A") }
            }
            val result = CachedGetItemRepository(delegate, h.store, h.session).get(itemId)
            assertEquals(ErrorKind.SESSION_CHANGED, result.error().kind)
            assertNull(h.store.cachedItem(h.session.state.value, itemId).successValue())
        }
    }

    @Test fun store_failure_is_returned_without_calling_delegate() = runTest {
        withHarness { h ->
            h.session.changeAccount("A")
            val delegate = ScriptedDelegate()
            val repo = CachedGetItemRepository(delegate, FailingStore(h.store, failRead = true), h.session)
            assertEquals("LOCAL_STORE_FAILURE", repo.get(itemId).error().code)
            assertEquals(0, delegate.calls)
        }
    }

    @Test fun undecodableRowIsDroppedAndTheNetworkAnswerIsCached() = runTest {
        withHarness { h ->
            h.session.changeAccount("A")
            val a = h.session.state.value
            h.store.upsertItem(a, item(99)).successValue()
            h.driver.execute(null, "UPDATE item_cache SET analysis_status = 'NOT_A_STATUS'", 0)
            val delegate = ScriptedDelegate().also { it.enqueueSuccess(item(2)) }
            val result = CachedGetItemRepository(delegate, h.store, h.session).get(itemId)
            assertEquals(2, result.successValue().version)
            assertEquals(1, delegate.calls)
            assertEquals(2, h.store.cachedItem(a, itemId).successValue()!!.version)
        }
    }

    @Test fun uppercaseIdUsesTheCanonicalCacheRow() = runTest {
        withHarness { h ->
            h.session.changeAccount("A")
            val a = h.session.state.value
            val ids = mutableListOf<String>()
            val delegate = object : GetItemRepository {
                override suspend fun get(id: String): ClientResult<WishlistItem> { ids += id; return ClientResult.Success(item(2, id = UUID_A)) }
            }
            val repo = CachedGetItemRepository(delegate, h.store, h.session)
            repo.get(UUID_A.uppercase()).successValue()
            repo.get(UUID_A.uppercase()).successValue()
            assertEquals(listOf(UUID_A, UUID_A), ids)
            assertEquals(UUID_A, h.store.cachedItem(a, UUID_A).successValue()!!.id)
        }
    }

    @Test fun cache_write_failure_is_returned_for_success_and_not_found() = runTest {
        withHarness { h ->
            h.session.changeAccount("A")
            h.store.upsertItem(h.session.state.value, item(1)).successValue()
            val delegate = ScriptedDelegate().also { it.enqueueSuccess(item(2)); it.enqueueNotFound() }
            val repo = CachedGetItemRepository(delegate, FailingStore(h.store, failWrite = true), h.session)
            assertEquals("LOCAL_STORE_FAILURE", repo.get(itemId).error().code)
            assertEquals("LOCAL_STORE_FAILURE", repo.get(itemId).error().code)
        }
    }

    @Test fun cancellation_propagates() = runTest {
        withHarness { h ->
            h.session.changeAccount("A")
            val repo = CachedGetItemRepository(ScriptedDelegate(), FailingStore(h.store, cancelRead = true), h.session)
            assertFailsWith<CancellationException> { repo.get(itemId) }
        }
    }
}
