package app.wishlist.shared.di

import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.model.Category
import app.wishlist.shared.model.LocalSubmission
import app.wishlist.shared.model.SubmissionStatus
import app.wishlist.shared.model.Purpose
import app.wishlist.shared.model.WishlistItem
import app.wishlist.shared.model.*
import app.wishlist.shared.repository.CatalogRepository
import app.wishlist.shared.repository.PurposeRepository
import app.wishlist.shared.repository.CategoryRepository
import app.wishlist.shared.repository.ItemListRepository
import app.wishlist.shared.repository.CreateItemCommand
import app.wishlist.shared.repository.CreateItemRepository
import app.wishlist.shared.repository.GetItemRepository
import app.wishlist.shared.repository.LocalStore
import app.wishlist.shared.repository.SnapshotCreateItemRepository
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/** The API is explicitly bound to UNAVAILABLE (or the runtime has no implementation connected). */
internal const val API_UNAVAILABLE = "API_UNAVAILABLE"

/** The runtime is not ready yet (debug bootstrap pending) or already closed. */
internal const val RUNTIME_NOT_READY = "RUNTIME_NOT_READY"

internal fun unavailable(code: String) = ClientResult.Failure(ClientError(ErrorKind.UNAVAILABLE, code))

/** The UNAVAILABLE backend for ITEM-01/ITEM-03: a typed failure, never an exception. */
internal object UnavailableItemRepository : SnapshotCreateItemRepository, GetItemRepository {
    override suspend fun create(command: CreateItemCommand): ClientResult<WishlistItem> = unavailable(API_UNAVAILABLE)
    override suspend fun create(command: CreateItemCommand, expected: SessionSnapshot): ClientResult<WishlistItem> =
        unavailable(API_UNAVAILABLE)
    override suspend fun get(id: String): ClientResult<WishlistItem> = unavailable(API_UNAVAILABLE)
}

/** RELEASE catalog: the seed catalog is a debug-only facade, not a CAT/PUR/ITEM-02 backend. */
internal object UnavailableCatalogRepository : CatalogRepository {
    override suspend fun categories(): ClientResult<List<Category>> = unavailable(API_UNAVAILABLE)
    override suspend fun purposes(): ClientResult<List<Purpose>> = unavailable(API_UNAVAILABLE)
    override suspend fun items(categoryId: String?, purposeId: String?): ClientResult<List<WishlistItem>> =
        unavailable(API_UNAVAILABLE)
}

/** The UNAVAILABLE backend for CAT-01~04. */
internal object UnavailableCategoryRepository : CategoryRepository {
    override suspend fun catalog(scope: CategoryScope, parentId: String?): ClientResult<CategoryCatalog> = unavailable(API_UNAVAILABLE)
    override suspend fun custom(id: String): ClientResult<CustomCategoryDetail> = unavailable(API_UNAVAILABLE)
    override suspend fun create(command: CategoryCommand, idempotencyKey: String): ClientResult<CategoryCreated> = unavailable(API_UNAVAILABLE)
    override suspend fun update(id: String, expectedVersion: Int, patch: CategoryPatch): ClientResult<CustomCategoryDetail> =
        unavailable(API_UNAVAILABLE)
}

/** The UNAVAILABLE backend for ITEM-02. */
internal object UnavailableItemListRepository : ItemListRepository {
    override suspend fun byPurpose(purposeId: String, cursor: String?, limit: Int): ClientResult<ItemPage> = unavailable(API_UNAVAILABLE)
    override suspend fun byCategory(categoryId: String, cursor: String?, limit: Int): ClientResult<ItemPage> = unavailable(API_UNAVAILABLE)
    override suspend fun byCategoryAround(categoryId: String, anchor: String, before: Int, after: Int): ClientResult<ItemPage> =
        unavailable(API_UNAVAILABLE)
    override suspend fun byPurposeAround(purposeId: String, anchor: String, before: Int, after: Int): ClientResult<ItemPage> =
        unavailable(API_UNAVAILABLE)
}

/** CAT-01~04 may be bound to different backends; each method uses its own API's delegate. */
internal class RoutedCategoryRepository(
    internal val catalog: CategoryRepository,
    internal val custom: CategoryRepository,
    internal val create: CategoryRepository,
    internal val update: CategoryRepository,
) : CategoryRepository {
    override suspend fun catalog(scope: CategoryScope, parentId: String?) = catalog.catalog(scope, parentId)
    override suspend fun custom(id: String) = custom.custom(id)
    override suspend fun create(command: CategoryCommand, idempotencyKey: String) = create.create(command, idempotencyKey)
    override suspend fun update(id: String, expectedVersion: Int, patch: CategoryPatch) = update.update(id, expectedVersion, patch)
}

/*
 * Ready gates wrap every public facade: before the runtime is ready (DEBUG bootstrap pending) and
 * after close, requests return UNAVAILABLE/RUNTIME_NOT_READY without touching the graph.
 */
internal class GatedCreateItemRepository(
    private val ready: StateFlow<Boolean>,
    internal val delegate: SnapshotCreateItemRepository,
) : SnapshotCreateItemRepository {
    override suspend fun create(command: CreateItemCommand): ClientResult<WishlistItem> =
        if (ready.value) delegate.create(command) else unavailable(RUNTIME_NOT_READY)
    override suspend fun create(command: CreateItemCommand, expected: SessionSnapshot): ClientResult<WishlistItem> =
        if (ready.value) delegate.create(command, expected) else unavailable(RUNTIME_NOT_READY)
}

internal class GatedGetItemRepository(
    private val ready: StateFlow<Boolean>,
    internal val delegate: GetItemRepository,
) : GetItemRepository {
    override suspend fun get(id: String): ClientResult<WishlistItem> =
        if (ready.value) delegate.get(id) else unavailable(RUNTIME_NOT_READY)
}

internal class GatedCatalogRepository(
    private val ready: StateFlow<Boolean>,
    internal val delegate: CatalogRepository,
) : CatalogRepository {
    override suspend fun categories(): ClientResult<List<Category>> =
        if (ready.value) delegate.categories() else unavailable(RUNTIME_NOT_READY)
    override suspend fun purposes(): ClientResult<List<Purpose>> =
        if (ready.value) delegate.purposes() else unavailable(RUNTIME_NOT_READY)
    override suspend fun items(categoryId: String?, purposeId: String?): ClientResult<List<WishlistItem>> =
        if (ready.value) delegate.items(categoryId, purposeId) else unavailable(RUNTIME_NOT_READY)
}

internal class GatedCategoryRepository(
    private val ready: StateFlow<Boolean>,
    internal val delegate: CategoryRepository,
) : CategoryRepository {
    override suspend fun catalog(scope: CategoryScope, parentId: String?): ClientResult<CategoryCatalog> =
        if (ready.value) delegate.catalog(scope, parentId) else unavailable(RUNTIME_NOT_READY)
    override suspend fun custom(id: String): ClientResult<CustomCategoryDetail> =
        if (ready.value) delegate.custom(id) else unavailable(RUNTIME_NOT_READY)
    override suspend fun create(command: CategoryCommand, idempotencyKey: String): ClientResult<CategoryCreated> =
        if (ready.value) delegate.create(command, idempotencyKey) else unavailable(RUNTIME_NOT_READY)
    override suspend fun update(id: String, expectedVersion: Int, patch: CategoryPatch): ClientResult<CustomCategoryDetail> =
        if (ready.value) delegate.update(id, expectedVersion, patch) else unavailable(RUNTIME_NOT_READY)
}

internal class GatedItemListRepository(
    private val ready: StateFlow<Boolean>,
    internal val delegate: ItemListRepository,
) : ItemListRepository {
    override suspend fun byPurpose(purposeId: String, cursor: String?, limit: Int): ClientResult<ItemPage> =
        if (ready.value) delegate.byPurpose(purposeId, cursor, limit) else unavailable(RUNTIME_NOT_READY)
    override suspend fun byCategory(categoryId: String, cursor: String?, limit: Int): ClientResult<ItemPage> =
        if (ready.value) delegate.byCategory(categoryId, cursor, limit) else unavailable(RUNTIME_NOT_READY)
    override suspend fun byCategoryAround(categoryId: String, anchor: String, before: Int, after: Int): ClientResult<ItemPage> =
        if (ready.value) delegate.byCategoryAround(categoryId, anchor, before, after) else unavailable(RUNTIME_NOT_READY)
    override suspend fun byPurposeAround(purposeId: String, anchor: String, before: Int, after: Int): ClientResult<ItemPage> =
        if (ready.value) delegate.byPurposeAround(purposeId, anchor, before, after) else unavailable(RUNTIME_NOT_READY)
}

internal class GatedLocalStore(
    private val ready: StateFlow<Boolean>,
    internal val delegate: LocalStore,
) : LocalStore {
    private inline fun <T> gated(block: () -> ClientResult<T>): ClientResult<T> =
        if (ready.value) block() else unavailable(RUNTIME_NOT_READY)

    override suspend fun saveSubmission(submission: LocalSubmission) = gated { delegate.saveSubmission(submission) }
    override suspend fun importSubmission(submission: LocalSubmission) = gated { delegate.importSubmission(submission) }
    override suspend fun pending() = gated { delegate.pending() }
    override suspend fun prepareFlush(snapshot: SessionSnapshot) = gated { delegate.prepareFlush(snapshot) }
    override suspend fun markSubmission(
        snapshot: SessionSnapshot, id: String, status: SubmissionStatus, error: ClientError?, retryAfter: Instant?,
    ) = gated { delegate.markSubmission(snapshot, id, status, error, retryAfter) }
    override suspend fun deleteSubmission(snapshot: SessionSnapshot, submissionId: String) =
        gated { delegate.deleteSubmission(snapshot, submissionId) }
    override suspend fun processingItems(snapshot: SessionSnapshot) = gated { delegate.processingItems(snapshot) }
    override suspend fun upsertItem(snapshot: SessionSnapshot, item: WishlistItem) =
        gated { delegate.upsertItem(snapshot, item) }
    override suspend fun upsertItems(snapshot: SessionSnapshot, items: List<WishlistItem>) =
        gated { delegate.upsertItems(snapshot, items) }
    override suspend fun cachedItem(snapshot: SessionSnapshot, id: String) =
        gated { delegate.cachedItem(snapshot, id) }
    override suspend fun cachedItemBySubmission(snapshot: SessionSnapshot, submissionId: String) =
        gated { delegate.cachedItemBySubmission(snapshot, submissionId) }
    override suspend fun accept(snapshot: SessionSnapshot, submissionId: String, item: WishlistItem) =
        gated { delegate.accept(snapshot, submissionId, item) }
    override suspend fun removeCachedItem(snapshot: SessionSnapshot, id: String, throughVersion: Int) =
        gated { delegate.removeCachedItem(snapshot, id, throughVersion) }
    override suspend fun clearCurrentCache() = gated { delegate.clearCurrentCache() }
    override suspend fun readAppState(key: String) = gated { delegate.readAppState(key) }
    override suspend fun writeAppState(key: String, value: String?) = gated { delegate.writeAppState(key, value) }
}

/** Placeholder behind the closed gate: a closed runtime never resolves its graph again. */
internal object ClosedLocalStore : LocalStore {
    override suspend fun saveSubmission(submission: LocalSubmission): ClientResult<Unit> = unavailable(RUNTIME_NOT_READY)
    override suspend fun importSubmission(submission: LocalSubmission): ClientResult<Unit> = unavailable(RUNTIME_NOT_READY)
    override suspend fun pending(): ClientResult<List<LocalSubmission>> = unavailable(RUNTIME_NOT_READY)
    override suspend fun prepareFlush(snapshot: SessionSnapshot): ClientResult<List<LocalSubmission>> =
        unavailable(RUNTIME_NOT_READY)
    override suspend fun markSubmission(
        snapshot: SessionSnapshot, id: String, status: SubmissionStatus, error: ClientError?, retryAfter: Instant?,
    ): ClientResult<Unit> = unavailable(RUNTIME_NOT_READY)
    override suspend fun deleteSubmission(snapshot: SessionSnapshot, submissionId: String): ClientResult<Unit> =
        unavailable(RUNTIME_NOT_READY)
    override suspend fun processingItems(snapshot: SessionSnapshot): ClientResult<List<WishlistItem>> =
        unavailable(RUNTIME_NOT_READY)
    override suspend fun upsertItem(snapshot: SessionSnapshot, item: WishlistItem): ClientResult<Unit> =
        unavailable(RUNTIME_NOT_READY)
    override suspend fun cachedItem(snapshot: SessionSnapshot, id: String): ClientResult<WishlistItem?> =
        unavailable(RUNTIME_NOT_READY)
    override suspend fun cachedItemBySubmission(snapshot: SessionSnapshot, submissionId: String): ClientResult<WishlistItem?> =
        unavailable(RUNTIME_NOT_READY)
    override suspend fun accept(snapshot: SessionSnapshot, submissionId: String, item: WishlistItem): ClientResult<Unit> =
        unavailable(RUNTIME_NOT_READY)
    override suspend fun removeCachedItem(snapshot: SessionSnapshot, id: String, throughVersion: Int): ClientResult<Unit> =
        unavailable(RUNTIME_NOT_READY)
    override suspend fun clearCurrentCache(): ClientResult<Unit> = unavailable(RUNTIME_NOT_READY)
    override suspend fun readAppState(key: String): ClientResult<String?> = unavailable(RUNTIME_NOT_READY)
    override suspend fun writeAppState(key: String, value: String?): ClientResult<Unit> = unavailable(RUNTIME_NOT_READY)
}

/** Each PUR method honors its own backend binding. */
internal class RoutedPurposeRepository(
    internal val summary: PurposeRepository, internal val detail: PurposeRepository,
    internal val create: PurposeRepository, internal val update: PurposeRepository,
) : PurposeRepository {
    override suspend fun summary() = summary.summary()
    override suspend fun detail(id: String) = detail.detail(id)
    override suspend fun create(command: PurposeCommand, idempotencyKey: String) = create.create(command, idempotencyKey)
    override suspend fun update(id: String, expectedVersion: Int, patch: PurposePatch) = update.update(id, expectedVersion, patch)
}
internal object UnavailablePurposeRepository : PurposeRepository {
    override suspend fun summary(): ClientResult<PurposeCatalog> = unavailable(API_UNAVAILABLE)
    override suspend fun detail(id: String): ClientResult<PurposeDetail> = unavailable(API_UNAVAILABLE)
    override suspend fun create(command: PurposeCommand, idempotencyKey: String): ClientResult<PurposeCreated> = unavailable(API_UNAVAILABLE)
    override suspend fun update(id: String, expectedVersion: Int, patch: PurposePatch): ClientResult<PurposeDetail> = unavailable(API_UNAVAILABLE)
}
internal class GatedPurposeRepository(private val ready: StateFlow<Boolean>, internal val delegate: PurposeRepository) : PurposeRepository {
    override suspend fun summary() = if (ready.value) delegate.summary() else unavailable(RUNTIME_NOT_READY)
    override suspend fun detail(id: String) = if (ready.value) delegate.detail(id) else unavailable(RUNTIME_NOT_READY)
    override suspend fun create(command: PurposeCommand, idempotencyKey: String) = if (ready.value) delegate.create(command, idempotencyKey) else unavailable(RUNTIME_NOT_READY)
    override suspend fun update(id: String, expectedVersion: Int, patch: PurposePatch) = if (ready.value) delegate.update(id, expectedVersion, patch) else unavailable(RUNTIME_NOT_READY)
}
