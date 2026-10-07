@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package app.wishlist.shared.data.fake

import app.wishlist.shared.core.*
import app.wishlist.shared.domain.evaluateItem
import app.wishlist.shared.domain.isListEligible
import app.wishlist.shared.model.*
import app.wishlist.shared.repository.CreateItemCommand
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/**
 * Sole owner of Fake business rules. Every owner namespace is selected from the same AuthSession.
 * Artificial latency occurs before session gate -> store lock; neither gate encloses external IO.
 * Analysis advances only through explicit controls, never on a timer.
 */
class FakeStore(private val session: AuthSession, private val clock: Clock, private val ids: IdGenerator) {
    private data class Entry(var item: WishlistItem, var analysisGeneration: Int = 1,
        val retryAttempts: MutableSet<String> = mutableSetOf())
    private class OwnerStore {
        val entries = mutableMapOf<String, Entry>()
        val submissionIds = mutableMapOf<String, String>()
        /** Item keys are canonical UUID strings, so platform casing never splits one item in two. */
        fun entry(id: String): Entry? = uuidOrNull(id)?.let(entries::get)
        var categories: List<Category> = emptyList()
        var purposes: List<Purpose> = emptyList()
        var seeded = false
    }
    private data class Injection(val error: ClientError? = null, val millis: Long = 0)
    private val mutex = Mutex()
    private val owners = mutableMapOf<String, OwnerStore>()
    private val injections = MutableStateFlow<Map<ApiId, Injection>>(emptyMap())

    fun failNext(apiId: ApiId, error: ClientError) {
        injections.update { it + (apiId to (it[apiId] ?: Injection()).copy(error = error)) }
    }
    fun delayNext(apiId: ApiId, millis: Long) {
        require(millis >= 0) { "Delay must be nonnegative" }
        injections.update { it + (apiId to (it[apiId] ?: Injection()).copy(millis = millis)) }
    }

    private suspend fun <T> request(apiId: ApiId?, operation: (OwnerStore) -> ClientResult<T>): ClientResult<T> {
        val snapshot = session.state.value
        val injection = if (apiId == null) null else injections.getAndUpdate { it - apiId }[apiId]
        if (injection != null && injection.millis > 0) delay(injection.millis)
        val result = session.withCurrent(snapshot) {
            mutex.withLock {
                val owner = snapshot.accountId
                when {
                    owner == null -> failure(ErrorKind.UNAUTHENTICATED)
                    injection?.error != null -> ClientResult.Failure(injection.error)
                    else -> operation(owners.getOrPut(owner) { OwnerStore() })
                }
            }
        }
        // Account changes between commit and publication also invalidate this caller's result.
        return if (session.state.value == snapshot) result else failure(ErrorKind.SESSION_CHANGED)
    }

    suspend fun create(command: CreateItemCommand): ClientResult<WishlistItem> = request(ApiId.ITEM_01) { owner ->
        val key = uuidOrNull(command.submissionId)
            ?: return@request failure(ErrorKind.VALIDATION, "INVALID_IDEMPOTENCY_KEY")
        val previous = owner.submissionIds[key]?.let { owner.entries.getValue(it).item }
        if (previous != null) {
            if (previous.sourceUrl != command.sourceUrl) failure(ErrorKind.CONFLICT, "IDEMPOTENCY_KEY_REUSED")
            else ClientResult.Success(previous)
        } else {
            val now = clock.now()
            val item = withPolicy(WishlistItem(
                id = Uuid.parse(ids.newId()).toString(), clientSubmissionId = key, version = 1,
                sourceUrl = command.sourceUrl, product = ProductSnapshot(), category = ItemCategory(),
                purpose = ItemPurpose(), analysis = ItemAnalysis(AnalysisStatus.PROCESSING),
                reviewStatus = ReviewStatus.NOT_REQUIRED, lifecycleStatus = LifecycleStatus.ACTIVE,
                requiredAction = RequiredAction.NONE, createdAt = now, updatedAt = now,
                clientCreatedAt = command.clientCreatedAt,
            ))
            check(item.id !in owner.entries) { "IdGenerator returned an existing item ID" }
            owner.entries[item.id] = Entry(item)
            owner.submissionIds[key] = item.id
            ClientResult.Success(item)
        }
    }

    suspend fun get(id: String): ClientResult<WishlistItem> = request(ApiId.ITEM_03) { owner ->
        val key = uuidOrNull(id) ?: return@request invalidItemId()
        val item = owner.entries[key]?.item
        if (item == null || item.lifecycleStatus == LifecycleStatus.DELETED)
            failure(ErrorKind.NOT_FOUND, "WISHLIST_ITEM_NOT_FOUND")
        else ClientResult.Success(item)
    }

    /** Initializes the current owner's demo namespace once, preserving subsequent edits/replays. */
    suspend fun seed(data: BoardSeedData): ClientResult<Unit> = request(null) { owner ->
        if (owner.seeded) return@request ClientResult.Success(Unit)
        // Store keys use the same canonical form as every lookup; reject before any partial write.
        val items = data.items.map { item ->
            val id = uuidOrNull(item.id) ?: return@request invalidItemId()
            val key = uuidOrNull(item.clientSubmissionId)
                ?: return@request failure(ErrorKind.VALIDATION, "INVALID_IDEMPOTENCY_KEY")
            item.copy(id = id, clientSubmissionId = key)
        }
        owner.categories = data.categories.toList()
        owner.purposes = data.purposes.toList()
        items.forEach { item ->
            if (item.id !in owner.entries && item.clientSubmissionId !in owner.submissionIds) {
                owner.entries[item.id] = Entry(withPolicy(item))
                owner.submissionIds[item.clientSubmissionId] = item.id
            }
        }
        owner.seeded = true
        ClientResult.Success(Unit)
    }
    suspend fun categories(): ClientResult<List<Category>> = request(ApiId.CAT_01) { ClientResult.Success(it.categories) }
    suspend fun purposes(): ClientResult<List<Purpose>> = request(ApiId.PUR_01) { ClientResult.Success(it.purposes) }
    suspend fun items(categoryId: String?, purposeId: String?): ClientResult<List<WishlistItem>> = request(ApiId.ITEM_02) { owner ->
        ClientResult.Success(owner.entries.values.map { it.item }.filter {
            isListEligible(it) && (categoryId == null || it.category.id == categoryId) &&
                (purposeId == null || it.purpose.id == purposeId)
        }.sortedWith(compareByDescending<WishlistItem> { it.createdAt }.thenByDescending { it.id }))
    }

    /**
     * Applies a final analysis like the server's AnalysisResultRepository: USER values and a
     * CONFIRMED/DEFERRED review survive, READY overwrites AI metadata, other outcomes only fill gaps.
     */
    suspend fun completeAnalysis(id: String, analysisGeneration: Int, result: AnalysisOutcome): ClientResult<Unit> = request(null) { owner ->
        val entry = owner.entry(id) ?: return@request missing(id)
        val item = entry.item
        if (entry.analysisGeneration != analysisGeneration || item.lifecycleStatus != LifecycleStatus.ACTIVE ||
            item.analysis.status != AnalysisStatus.PROCESSING) return@request ClientResult.Success(Unit)
        if (result.status == AnalysisStatus.PROCESSING || result.status == AnalysisStatus.UNKNOWN ||
            (result.categoryId != null && result.missingReason != null)) return@request failure(ErrorKind.VALIDATION)

        val reviewSettled = item.reviewStatus == ReviewStatus.CONFIRMED || item.reviewStatus == ReviewStatus.DEFERRED
        val userCategory = item.category.source == ValueSource.USER
        val applyCategory = result.categoryId != null && !reviewSettled && !userCategory
        val categoryId = if (applyCategory) result.categoryId else item.category.id
        if (result.status == AnalysisStatus.READY && categoryId.isNullOrBlank()) return@request failure(ErrorKind.VALIDATION)
        val complete = result.status == AnalysisStatus.READY

        val userName = item.product.nameSource == ValueSource.USER
        val existingName = item.product.name
        val name = when {
            userName -> existingName
            complete -> result.name ?: existingName
            else -> existingName ?: result.name
        }
        val nameSource = if (!userName && result.name != null && (complete || existingName == null)) ValueSource.AI
            else item.product.nameSource
        val categorySource = if (applyCategory) ValueSource.AI else item.category.source
        val missingReason = when {
            categoryId != null -> null
            userCategory && item.category.missingReason != null -> item.category.missingReason
            else -> result.missingReason ?: CategoryMissingReason.EXTRACTION_UNRESOLVED
        }
        val unconfirmedAiCategory = categorySource == ValueSource.AI && (applyCategory || item.reviewStatus == ReviewStatus.PENDING)
        val review = when {
            reviewSettled -> item.reviewStatus
            !categoryId.isNullOrBlank() && !name.isNullOrBlank() && unconfirmedAiCategory -> ReviewStatus.PENDING
            else -> ReviewStatus.NOT_REQUIRED
        }
        val category = if (applyCategory) category(owner, categoryId, ValueSource.AI)
            else item.category.copy(missingReason = missingReason)
        entry.item = next(item.copy(
            product = item.product.copy(name = name, nameSource = nameSource),
            category = category,
            analysis = ItemAnalysis(result.status, if (complete) null else result.failureCode),
            reviewStatus = review,
        ))
        ClientResult.Success(Unit)
    }

    suspend fun edit(id: String, expectedVersion: Int, patch: ItemPatch): ClientResult<WishlistItem> =
        mutate(id, expectedVersion, ItemAction.EDIT) { owner, item -> ClientResult.Success(applyPatch(owner, item, patch)) }

    suspend fun manualComplete(id: String, expectedVersion: Int, name: String, categoryId: String): ClientResult<WishlistItem> =
        mutate(id, expectedVersion, ItemAction.MANUAL_COMPLETE) { owner, item ->
            if (name.isBlank() || categoryId.isBlank()) failure(ErrorKind.VALIDATION)
            else ClientResult.Success(applyPatch(owner, item, ItemPatch(name = Patch.Set(name), categoryId = Patch.Set(categoryId)))
                .copy(manualCompletionAt = clock.now(), reviewStatus = ReviewStatus.CONFIRMED))
        }

    suspend fun review(id: String, expectedVersion: Int, decision: ReviewDecision, patch: ItemPatch): ClientResult<WishlistItem> =
        mutate(id, expectedVersion, ItemAction.REVIEW) { owner, item ->
            ClientResult.Success(applyPatch(owner, item, patch).copy(reviewStatus = when (decision) {
                ReviewDecision.CONFIRM -> ReviewStatus.CONFIRMED
                ReviewDecision.DEFER -> ReviewStatus.DEFERRED
            }))
        }

    private suspend fun mutate(id: String, expectedVersion: Int, action: ItemAction,
        transform: (OwnerStore, WishlistItem) -> ClientResult<WishlistItem>): ClientResult<WishlistItem> = request(null) { owner ->
        val entry = owner.entry(id) ?: return@request missing(id)
        val item = entry.item
        when {
            item.lifecycleStatus != LifecycleStatus.ACTIVE -> notFound()
            item.version != expectedVersion || action !in item.allowedActions -> conflict(item)
            else -> when (val result = transform(owner, item)) {
                is ClientResult.Failure -> result
                is ClientResult.Success -> {
                    entry.item = next(result.value)
                    ClientResult.Success(entry.item)
                }
            }
        }
    }

    suspend fun delete(id: String): ClientResult<Unit> = request(null) { owner ->
        val entry = owner.entry(id) ?: return@request missing(id)
        if (entry.item.lifecycleStatus != LifecycleStatus.DELETED) {
            entry.item = next(entry.item.copy(lifecycleStatus = LifecycleStatus.DELETED))
        }
        ClientResult.Success(Unit)
    }

    suspend fun reanalyze(id: String, attemptRequestId: String): ClientResult<WishlistItem> = request(null) { owner ->
        val entry = owner.entry(id) ?: return@request missing(id)
        val item = entry.item
        when {
            item.lifecycleStatus != LifecycleStatus.ACTIVE -> notFound()
            attemptRequestId in entry.retryAttempts -> ClientResult.Success(item)
            ItemAction.REANALYZE !in item.allowedActions -> conflict(item)
            else -> {
                entry.retryAttempts.add(attemptRequestId)
                entry.analysisGeneration += 1
                entry.item = next(item.copy(analysis = ItemAnalysis(AnalysisStatus.PROCESSING)))
                ClientResult.Success(entry.item)
            }
        }
    }

    private fun applyPatch(owner: OwnerStore, item: WishlistItem, patch: ItemPatch): WishlistItem = item.copy(
        product = item.product.copy(
            name = patch.name.valueOr(item.product.name), imageUrl = patch.imageUrl.valueOr(item.product.imageUrl),
            nameSource = if (patch.name is Patch.Set) ValueSource.USER else item.product.nameSource,
            imageSource = if (patch.imageUrl is Patch.Set) ValueSource.USER else item.product.imageSource,
        ),
        category = when (val value = patch.categoryId) {
            Patch.Unchanged -> item.category
            is Patch.Set -> category(owner, value.value, if(value.value == null) ValueSource.UNASSIGNED else ValueSource.USER)
        },
        purpose = when (val value = patch.purposeId) {
            Patch.Unchanged -> item.purpose
            is Patch.Set -> ItemPurpose(value.value, if(value.value == null) ValueSource.UNASSIGNED else ValueSource.USER)
        },
    )
    private fun category(owner: OwnerStore, id: String?, source: ValueSource): ItemCategory {
        val definition = owner.categories.firstOrNull { it.id == id }
        return ItemCategory(id, source, null, definition?.name, definition?.parentId, definition?.kind)
    }
    private fun next(item: WishlistItem): WishlistItem = withPolicy(item.copy(version = item.version + 1, updatedAt = clock.now()))
    private fun withPolicy(item: WishlistItem): WishlistItem {
        val policy = evaluateItem(item)
        return item.copy(requiredAction = policy.requiredAction, allowedActions = policy.allowedActions)
    }
    private fun <T> Patch<T>.valueOr(original: T): T = when(this) { Patch.Unchanged -> original; is Patch.Set -> value }
    private fun notFound() = failure(ErrorKind.NOT_FOUND)
    private fun invalidItemId() = failure(ErrorKind.VALIDATION, "INVALID_WISHLIST_ITEM_ID")
    private fun missing(id: String) = if (uuidOrNull(id) == null) invalidItemId() else notFound()
    private fun conflict(item: WishlistItem) = failure(ErrorKind.CONFLICT, currentVersion = item.version)
    private fun failure(kind: ErrorKind, code: String? = null, currentVersion: Int? = null) =
        ClientResult.Failure(ClientError(kind, code, currentVersion = currentVersion))
}

private fun uuidOrNull(value: String): String? = try { Uuid.parse(value).toString() } catch (_: IllegalArgumentException) { null }
