@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package app.wishlist.shared.data.fake

import app.wishlist.shared.core.*
import app.wishlist.shared.domain.CategoryInputRules
import app.wishlist.shared.domain.PurposeInputRules
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Sole owner of Fake business rules. Every owner namespace is selected from the same AuthSession.
 * Artificial latency occurs before session gate -> store lock; neither gate encloses external IO.
 * Analysis advances only through explicit controls, never on a timer.
 */
internal class FakeStore(private val session: AuthSession, private val clock: Clock, private val ids: IdGenerator) {
    private data class Entry(var item: WishlistItem, var analysisGeneration: Int = 1,
        val retryAttempts: MutableSet<String> = mutableSetOf())
    private class OwnerStore {
        val entries = mutableMapOf<String, Entry>()
        val submissionIds = mutableMapOf<String, String>()
        /** Item keys are canonical UUID strings, so platform casing never splits one item in two. */
        fun entry(id: String): Entry? = canonicalUuidOrNull(id)?.let(entries::get)
        val customs = mutableListOf<FakeCustom>()
        /** CAT-03 receipts by idempotency key: never expire (B2). */
        val receipts = mutableMapOf<String, FakeReceipt>()
        /** Successful (non-replay) creation times, for the 60-second limit. */
        val creations = mutableListOf<Instant>()
        val purposeRows = mutableMapOf<String, PurposeDetail>()
        val purposeReceipts = mutableMapOf<String, Pair<PurposeCommand, String>>()
        val purposeCreations = mutableListOf<Instant>()
        var seeded = false
        /** The board seed's first leaf (DEBUG analysis files items there); null before seeding. */
        var firstSeedLeaf: String? = null
        fun custom(id: String): FakeCustom? = canonicalUuidOrNull(id)?.let { key -> customs.firstOrNull { it.id == key && !it.deleted } }
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

    /** [expected]: the snapshot the call is for (default: the current one); a stale one touches no owner. */
    private suspend fun <T> request(
        apiId: ApiId?,
        expected: SessionSnapshot? = null,
        operation: (OwnerStore) -> ClientResult<T>,
    ): ClientResult<T> {
        val snapshot = expected ?: session.state.value
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

    suspend fun create(command: CreateItemCommand, expected: SessionSnapshot? = null): ClientResult<WishlistItem> =
        request(ApiId.ITEM_01, expected) { owner ->
        val key = canonicalUuidOrNull(command.submissionId)
            ?: return@request failure(ErrorKind.VALIDATION, "INVALID_IDEMPOTENCY_KEY")
        if (!isValidFakeClientCreatedAt(command.clientCreatedAt)) return@request failure(ErrorKind.VALIDATION, "INVALID_CLIENT_CREATED_AT")
        if (!isValidFakeSourceUrl(command.sourceUrl)) return@request failure(ErrorKind.VALIDATION, "INVALID_URL")
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
                clientCreatedAt = normalizeFakeClientCreatedAt(command.clientCreatedAt),
            ))
            check(item.id !in owner.entries) { "IdGenerator returned an existing item ID" }
            owner.entries[item.id] = Entry(item)
            owner.submissionIds[key] = item.id
            ClientResult.Success(item)
        }
    }

    suspend fun get(id: String): ClientResult<WishlistItem> = request(ApiId.ITEM_03) { owner ->
        val key = canonicalUuidOrNull(id) ?: return@request invalidItemId()
        val item = owner.entries[key]?.item
        if (item == null || item.lifecycleStatus == LifecycleStatus.DELETED)
            failure(ErrorKind.NOT_FOUND, "WISHLIST_ITEM_NOT_FOUND")
        else ClientResult.Success(present(owner, item))
    }

    /** Initializes the current owner's demo namespace once, preserving subsequent edits/replays. */
    suspend fun seed(data: BoardSeedData): ClientResult<Unit> = request(null) { owner ->
        if (owner.seeded) return@request ClientResult.Success(Unit)
        // Store keys use the same canonical form as every lookup; reject before any partial write.
        val items = data.items.map { item ->
            val id = canonicalUuidOrNull(item.id) ?: return@request invalidItemId()
            val key = canonicalUuidOrNull(item.clientSubmissionId)
                ?: return@request failure(ErrorKind.VALIDATION, "INVALID_IDEMPOTENCY_KEY")
            item.copy(id = id, clientSubmissionId = key)
        }
        data.categories.filter { it.kind == "CUSTOM" }.forEach { custom ->
            val id = canonicalUuidOrNull(custom.id) ?: return@request failure(ErrorKind.VALIDATION)
            val parent = custom.parentId ?: return@request failure(ErrorKind.VALIDATION)
            val (description, examples) = BoardSeeds.customText[custom.name] ?: (null to emptyList())
            owner.customs += FakeCustom(id, parent, custom.name, description, examples, custom.version ?: 1, nextCustomOrder(owner, parent))
        }
        data.purposes.forEach { p -> owner.purposeRows[p.id] = PurposeDetail(p.id, p.name, p.description, p.colorKey, p.iconKey,
            0, 1, p.version, data.purposeActivities.getValue(p.id), p.createdAt, p.updatedAt, emptySet()) }
        owner.firstSeedLeaf = data.categories.firstOrNull { it.parentId != null }?.id
        val displayed = items.map { item ->
            if (item.purpose.id == null) item else item.copy(purpose = purpose(owner, item.purpose.id, item.purpose.source))
        }
        displayed.forEach { item ->
            if (item.id !in owner.entries && item.clientSubmissionId !in owner.submissionIds) {
                owner.entries[item.id] = Entry(withPolicy(item))
                owner.submissionIds[item.clientSubmissionId] = item.id
            }
        }
        owner.seeded = true
        ClientResult.Success(Unit)
    }
    /** C2 seed-shaped flat list (groups, public leaves, live customs); the C5 screens use [catalog]. */
    suspend fun categories(): ClientResult<List<Category>> = request(ApiId.CAT_01) { owner ->
        ClientResult.Success(FakeTaxonomy.groups.flatMap { group ->
            listOf(Category(group.id, group.name)) +
                FakeTaxonomy.leavesOf(group.id).map { Category(it.id, it.name, group.id, "PUBLIC") } +
                owner.customs.filter { it.parentId == group.id && !it.deleted }.map { Category(it.id, it.name, group.id, "CUSTOM", it.version) }
        })
    }
    suspend fun firstSeedLeaf(): ClientResult<String?> = request(null) { ClientResult.Success(it.firstSeedLeaf) }
    suspend fun purposes(): ClientResult<List<Purpose>> = request(ApiId.PUR_01) { owner ->
        ClientResult.Success(owner.purposeRows.values.map { Purpose(it.id, it.name, it.description, it.colorKey, it.iconKey, it.version, it.createdAt, it.updatedAt) })
    }
    suspend fun items(categoryId: String?, purposeId: String?): ClientResult<List<WishlistItem>> = request(ApiId.ITEM_02) { owner ->
        ClientResult.Success(owner.entries.values.map { present(owner, it.item) }.filter {
            isListEligible(it) && (categoryId == null || it.category.id == categoryId) &&
                (purposeId == null || it.purpose.id == purposeId)
        }.sortedWith(compareByDescending<WishlistItem> { it.createdAt }.thenByDescending { it.id }))
    }

    /**
     * Applies a final analysis like the server's AnalysisResultRepository: USER values and a
     * CONFIRMED/DEFERRED review survive, READY overwrites AI metadata, other outcomes only fill gaps.
     */
    suspend fun completeAnalysis(id: String, analysisGeneration: Int, result: AnalysisOutcome): ClientResult<Unit> =
        request(null) { owner -> applyAnalysis(owner, id, analysisGeneration, result) }

    /**
     * Completes, through the same rules as [completeAnalysis], every ACTIVE PROCESSING item of the
     * current owner created at least [minAge] before [now]. There is no timer: the caller decides
     * when (DEBUG refresh). Returns how many items were completed; an invalid outcome skips its item.
     */
    suspend fun completeDueAnalyses(
        now: Instant,
        minAge: Duration,
        outcome: (WishlistItem) -> AnalysisOutcome,
    ): ClientResult<Int> = request(null) { owner ->
        val due = owner.entries.values.filter {
            it.item.lifecycleStatus == LifecycleStatus.ACTIVE && it.item.analysis.status == AnalysisStatus.PROCESSING &&
                now - it.item.createdAt >= minAge
        }
        ClientResult.Success(due.count { entry ->
            applyAnalysis(owner, entry.item.id, entry.analysisGeneration, outcome(entry.item)) is ClientResult.Success
        })
    }

    private fun applyAnalysis(owner: OwnerStore, id: String, analysisGeneration: Int, result: AnalysisOutcome): ClientResult<Unit> {
        val entry = owner.entry(id) ?: return missing(id)
        val item = entry.item
        if (entry.analysisGeneration != analysisGeneration || item.lifecycleStatus != LifecycleStatus.ACTIVE ||
            item.analysis.status != AnalysisStatus.PROCESSING) return ClientResult.Success(Unit)
        if (result.status == AnalysisStatus.PROCESSING || result.status == AnalysisStatus.UNKNOWN ||
            (result.categoryId != null && result.missingReason != null)) return failure(ErrorKind.VALIDATION)

        val reviewSettled = item.reviewStatus == ReviewStatus.CONFIRMED || item.reviewStatus == ReviewStatus.DEFERRED
        val userCategory = item.category.source == ValueSource.USER
        val applyCategory = result.categoryId != null && !reviewSettled && !userCategory
        val categoryId = if (applyCategory) result.categoryId else item.category.id
        if (result.status == AnalysisStatus.READY && categoryId.isNullOrBlank()) return failure(ErrorKind.VALIDATION)
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
        return ClientResult.Success(Unit)
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
                    purposeMembershipChanged(owner, item, entry.item)
                    ClientResult.Success(present(owner, entry.item))
                }
            }
        }
    }

    suspend fun delete(id: String): ClientResult<Unit> = request(null) { owner ->
        val entry = owner.entry(id) ?: return@request missing(id)
        if (entry.item.lifecycleStatus != LifecycleStatus.DELETED) {
            val previous = entry.item
            entry.item = next(entry.item.copy(lifecycleStatus = LifecycleStatus.DELETED))
            purposeMembershipChanged(owner, previous, entry.item)
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
            is Patch.Set -> value.value?.let { purpose(owner, it, ValueSource.USER) } ?: ItemPurpose()
        },
    )
    /** Like the server's B3 PurposeDto: display fields from the owner's purpose, keys in wire form (`CORAL`, `MUSIC`). */
    private fun purpose(owner: OwnerStore, id: String, source: ValueSource): ItemPurpose {
        val definition = owner.purposeRows[id]
        return ItemPurpose(id, source, definition?.name,
            definition?.colorKey?.let { PurposeStyleKeys.colorToWire(it) ?: it },
            definition?.iconKey?.let { PurposeStyleKeys.iconToWire(it) ?: it })
    }
    private fun category(owner: OwnerStore, id: String?, source: ValueSource): ItemCategory =
        displayed(owner, ItemCategory(id, source))

    /** Like the server's B2 mapper: name/parentId/kind come from the current row (a renamed custom shows its new name). */
    private fun displayed(owner: OwnerStore, category: ItemCategory): ItemCategory {
        val id = category.id ?: return category.copy(name = null, parentId = null, kind = null)
        FakeTaxonomy.leaf(id)?.let { return category.copy(name = it.name, parentId = it.groupId, kind = "PUBLIC") }
        val custom = owner.custom(id) ?: return category
        return category.copy(id = custom.id, name = custom.name, parentId = custom.parentId, kind = "CUSTOM")
    }
    private fun present(owner: OwnerStore, item: WishlistItem): WishlistItem = item.copy(category = displayed(owner, item.category),
        purpose = item.purpose.id?.let { purpose(owner, it, item.purpose.source) } ?: item.purpose)
    // ---- B2 CAT-01~04 and B4 ITEM-02 (category scope) ----

    /** Server `WishlistReadPredicates.categoryVisible`: ACTIVE with a non-blank name. */
    private fun visible(item: WishlistItem) = item.lifecycleStatus == LifecycleStatus.ACTIVE && !item.product.name.isNullOrBlank()

    private fun counts(owner: OwnerStore): Map<String, Int> =
        owner.entries.values.map { it.item }.filter(::visible).mapNotNull { it.category.id }.groupingBy { it }.eachCount()

    private fun nextCustomOrder(owner: OwnerStore, parent: String): Int =
        owner.customs.filter { it.parentId == parent }.maxOfOrNull { it.order + 1 } ?: FakeTaxonomy.leavesOf(parent).size

    private fun detail(owner: OwnerStore, custom: FakeCustom) = CustomCategoryDetail(
        custom.id, custom.name, custom.parentId, custom.description, custom.examples, counts(owner)[custom.id] ?: 0, custom.version)

    private fun liveCustomCount(owner: OwnerStore) = owner.customs.count { !it.deleted }

    suspend fun catalog(scope: CategoryScope, parentId: String?): ClientResult<CategoryCatalog> = request(ApiId.CAT_01) { owner ->
        if (parentId != null && FakeTaxonomy.group(parentId) == null)
            return@request failure(ErrorKind.VALIDATION, "INVALID_CATEGORY_PARENT", fields = setOf("parentId"))
        val counts = counts(owner)
        val groups = FakeTaxonomy.groups.filter { parentId == null || it.id == parentId }.mapNotNull { group ->
            val public = FakeTaxonomy.leavesOf(group.id).map {
                CategoryEntry(it.id, it.name, group.id, CategoryKind.PUBLIC, "PUBLIC", it.order, counts[it.id] ?: 0)
            }
            val personal = owner.customs.filter { it.parentId == group.id && !it.deleted }.sortedBy { it.order }.map {
                CategoryEntry(it.id, it.name, group.id, CategoryKind.CUSTOM, "CUSTOM", it.order, counts[it.id] ?: 0)
            }
            val entries = (public + personal).filter { scope == CategoryScope.SELECT || it.kind == CategoryKind.CUSTOM || it.itemCount > 0 }
            if (scope == CategoryScope.BROWSE && entries.isEmpty()) null
            else CategoryGroup(group.id, group.name, group.order, entries.sumOf { it.itemCount }, entries)
        }
        ClientResult.Success(CategoryCatalog(scope, FakeTaxonomy.VERSION, groups, liveCustomCount(owner), CUSTOM_LIMIT))
    }

    suspend fun customCategory(id: String): ClientResult<CustomCategoryDetail> = request(ApiId.CAT_02) { owner ->
        if (canonicalUuidOrNull(id) == null) return@request failure(ErrorKind.VALIDATION, "INVALID_CATEGORY_ID")
        val custom = owner.custom(id) ?: return@request categoryNotFound()
        ClientResult.Success(detail(owner, custom))
    }

    /** Server `CategoryService.create` order: input → receipt (replay) → rate → limit → duplicate. */
    suspend fun createCategory(command: CategoryCommand, idempotencyKey: String): ClientResult<CategoryCreated> = request(ApiId.CAT_03) { owner ->
        val key = canonicalUuidOrNull(idempotencyKey) ?: return@request failure(ErrorKind.VALIDATION, "INVALID_IDEMPOTENCY_KEY")
        if (FakeTaxonomy.group(command.parentId) == null)
            return@request failure(ErrorKind.VALIDATION, "INVALID_CATEGORY_PARENT", fields = setOf("parentId"))
        val invalid = CategoryInputRules.validate(command)
        if (invalid.isNotEmpty()) return@request failure(ErrorKind.VALIDATION, "INVALID_CATEGORY_INPUT", fields = invalid)
        owner.receipts[key]?.let { receipt ->
            if (receipt.command != command) return@request failure(ErrorKind.CONFLICT, "IDEMPOTENCY_KEY_REUSED")
            val row = owner.custom(receipt.customId) ?: return@request failure(ErrorKind.CONFLICT, "CATEGORY_NOT_AVAILABLE")
            return@request ClientResult.Success(CategoryCreated(detail(owner, row), liveCustomCount(owner), CUSTOM_LIMIT))
        }
        val now = clock.now()
        val recent = owner.creations.filter { now - it < CREATE_WINDOW }
        if (recent.size >= CREATE_PER_WINDOW) {
            val wait = (recent.min() + CREATE_WINDOW - now).inWholeMilliseconds
            return@request ClientResult.Failure(ClientError(ErrorKind.RATE_LIMITED, "CATEGORY_CREATE_RATE_LIMITED",
                retryAfterSeconds = maxOf(1L, (wait + 999) / 1000)))
        }
        if (liveCustomCount(owner) >= CUSTOM_LIMIT) return@request failure(ErrorKind.CONFLICT, "CATEGORY_LIMIT_REACHED")
        if (duplicate(owner, command.parentId, command.name, null))
            return@request failure(ErrorKind.CONFLICT, "CATEGORY_NAME_DUPLICATE", fields = setOf("name"))
        val custom = FakeCustom(Uuid.parse(ids.newId()).toString(), command.parentId, command.name, command.description,
            command.examples, 1, nextCustomOrder(owner, command.parentId))
        owner.customs += custom
        owner.receipts[key] = FakeReceipt(command, custom.id)
        owner.creations += now
        ClientResult.Success(CategoryCreated(detail(owner, custom), liveCustomCount(owner), CUSTOM_LIMIT))
    }

    /** Server `CategoryService.patch` order: found → version → any change → input → no-op → duplicate. */
    suspend fun updateCategory(id: String, expectedVersion: Int, patch: CategoryPatch): ClientResult<CustomCategoryDetail> = request(ApiId.CAT_04) { owner ->
        if (canonicalUuidOrNull(id) == null) return@request failure(ErrorKind.VALIDATION, "INVALID_CATEGORY_ID")
        val custom = owner.custom(id) ?: return@request categoryNotFound()
        if (custom.version != expectedVersion)
            return@request failure(ErrorKind.CONFLICT, "CATEGORY_VERSION_CONFLICT", currentVersion = custom.version)
        if (patch.isEmpty || expectedVersion <= 0) return@request failure(ErrorKind.VALIDATION, "INVALID_CATEGORY_INPUT")
        val name = patch.name ?: custom.name
        val description = when (val change = patch.description) {
            DescriptionChange.Keep -> custom.description
            is DescriptionChange.Set -> change.value
        }
        val examples = patch.examples ?: custom.examples
        val invalid = CategoryInputRules.validate(CategoryCommand(custom.parentId, name, description, examples))
        if (invalid.isNotEmpty()) return@request failure(ErrorKind.VALIDATION, "INVALID_CATEGORY_INPUT", fields = invalid)
        if (name == custom.name && description == custom.description && examples == custom.examples)
            return@request ClientResult.Success(detail(owner, custom))
        if (duplicate(owner, custom.parentId, name, custom.id))
            return@request failure(ErrorKind.CONFLICT, "CATEGORY_NAME_DUPLICATE", fields = setOf("name"))
        custom.name = name
        custom.description = description
        custom.examples = examples
        custom.version += 1
        ClientResult.Success(detail(owner, custom))
    }

    private fun duplicate(owner: OwnerStore, parent: String, name: String, exceptId: String?): Boolean {
        val key = CategoryInputRules.normalizedKey(name)
        return owner.customs.any { !it.deleted && it.parentId == parent && it.id != exceptId && CategoryInputRules.normalizedKey(it.name) == key }
    }

    /** ITEM-02 category scope: newest first (`createdAt DESC, id DESC`), keyset page or anchor window. */
    suspend fun itemsByCategory(categoryId: String, cursor: String?, limit: Int): ClientResult<ItemPage> =
        itemsByCategory(categoryId, cursor, limit, anchor = null, before = 0, after = 0)

    suspend fun itemsByCategoryAround(categoryId: String, anchor: String, before: Int, after: Int): ClientResult<ItemPage> =
        itemsByCategory(categoryId, cursor = null, limit = null, anchor, before, after)

    private suspend fun itemsByCategory(categoryId: String, cursor: String?, limit: Int?, anchor: String?, before: Int, after: Int) =
        request(ApiId.ITEM_02) { owner ->
            if (!validWindowQuery(limit, anchor, before, after)) return@request failure(ErrorKind.VALIDATION, "INVALID_WISHLIST_ITEM_QUERY")
            val known = FakeTaxonomy.leaf(categoryId) != null || owner.custom(categoryId) != null
            if (!known) return@request if (canonicalUuidOrNull(categoryId) != null) categoryNotFound()
                else failure(ErrorKind.VALIDATION, "INVALID_WISHLIST_ITEM_QUERY")
            val key = canonicalUuidOrNull(categoryId) ?: categoryId
            val all = owner.entries.values.map { it.item }.filter { visible(it) && it.category.id == key }
                .sortedWith(compareByDescending<WishlistItem> { it.createdAt }.thenByDescending { it.id })
            // Tokens are scoped to owner + category (another scope's token is a cursor error).
            itemPage(owner, all, "fake2:${session.state.value.accountId}:$key:", cursor, limit, anchor, before, after)
        }

    /** Query checks come before any lookup (a bad limit is 400 even for a missing scope), like the server. */
    private fun validWindowQuery(limit: Int?, anchor: String?, before: Int, after: Int) =
        if (anchor == null) limit != null && limit in 1..100 else before in 0..20 && after in 0..20

    /** One ITEM-02 slice of [all] (already in scope order) as an [ItemPage] with per-card anchors. */
    private fun itemPage(owner: OwnerStore, all: List<WishlistItem>, prefix: String, cursor: String?, limit: Int?,
                         anchor: String?, before: Int, after: Int): ClientResult<ItemPage> =
        when (val window = fakeWindow(all, { it.createdAt to it.id }, prefix, cursor, limit, anchor, before, after)) {
            is ClientResult.Failure -> window
            is ClientResult.Success -> window.value.let { w ->
                ClientResult.Success(ItemPage(w.items.map { present(owner, it) }, all.size, w.nextCursor, w.previousCursor,
                    w.items.associate { it.id to w.anchorOf(it) }, w.anchor))
            }
        }

    /** DEBUG/test control: puts a finished item as is (policy applied); replaces one with the same id. */
    suspend fun putItem(item: WishlistItem): ClientResult<WishlistItem> = request(null) { owner ->
        val id = canonicalUuidOrNull(item.id) ?: return@request invalidItemId()
        val stored = withPolicy(item.copy(id = id, category = displayed(owner, item.category)))
        val previous = owner.entries[id]?.item
        owner.entries[id] = Entry(stored)
        purposeMembershipChanged(owner, previous, stored)
        owner.submissionIds[stored.clientSubmissionId] = id
        ClientResult.Success(stored)
    }

    // ---- B3 PUR-01~04; membership and ITEM-02 use every ACTIVE item, including unnamed ones. ----
    private fun purposeItems(owner: OwnerStore, id: String) = owner.entries.values.map { it.item }
        .filter { it.lifecycleStatus == LifecycleStatus.ACTIVE && it.purpose.id == id }
        .sortedWith(compareByDescending<WishlistItem> { it.createdAt }.thenByDescending { it.id })

    private fun purposeView(owner: OwnerStore, row: PurposeDetail): PurposeDetail {
        val count = purposeItems(owner, row.id).size
        return row.copy(candidateCount = count, allowedActions = buildSet {
            add("EDIT"); add("DELETE"); add("ADD_CANDIDATES"); if (count > 0) add("ARCHIVE")
        })
    }

    private fun purposeMembershipChanged(owner: OwnerStore, before: WishlistItem?, after: WishlistItem) {
        val oldId = before?.takeIf { it.lifecycleStatus == LifecycleStatus.ACTIVE }?.purpose?.id
        val newId = after.takeIf { it.lifecycleStatus == LifecycleStatus.ACTIVE }?.purpose?.id
        if (oldId == newId) return
        oldId?.let { id -> owner.purposeRows[id]?.let { row -> owner.purposeRows[id] = row.copy(membershipVersion = row.membershipVersion + 1) } }
        newId?.let { id -> owner.purposeRows[id]?.let { row -> owner.purposeRows[id] = row.copy(
            membershipVersion = row.membershipVersion + 1, activity = PurposeActivity(clock.now(), "CANDIDATE_ADDED")) } }
    }

    suspend fun purposeSummary(): ClientResult<PurposeCatalog> = request(ApiId.PUR_01) { owner ->
        val rows = owner.purposeRows.values.sortedWith(compareByDescending<PurposeDetail> { it.activity.at }.thenByDescending { it.id })
        ClientResult.Success(PurposeCatalog(rows.map { row ->
            val candidates = purposeItems(owner, row.id)
            PurposeSummary(row.id, row.name, row.description, row.colorKey, row.iconKey, row.version, candidates.size,
                row.activity, candidates.take(4).map { PurposePreview(it.id, it.product.imageUrl) })
        }, rows.size, 30, ArchiveSummary(0, emptyList())))
    }

    suspend fun purposeDetail(id: String): ClientResult<PurposeDetail> = request(ApiId.PUR_03) { owner ->
        val key = canonicalUuidOrNull(id) ?: return@request failure(ErrorKind.VALIDATION, "INVALID_PURPOSE_ID")
        val row = owner.purposeRows[key] ?: return@request purposeNotFound()
        ClientResult.Success(purposeView(owner, row))
    }

    suspend fun createPurpose(command: PurposeCommand, idempotencyKey: String): ClientResult<PurposeCreated> = request(ApiId.PUR_02) { owner ->
        val key = canonicalUuidOrNull(idempotencyKey) ?: return@request failure(ErrorKind.VALIDATION, "INVALID_IDEMPOTENCY_KEY")
        val invalid = PurposeInputRules.validate(command)
        if (invalid.isNotEmpty()) return@request failure(ErrorKind.VALIDATION, "INVALID_PURPOSE_INPUT", fields = invalid)
        owner.purposeReceipts[key]?.let { (original, id) ->
            if (original != command) return@request failure(ErrorKind.CONFLICT, "IDEMPOTENCY_KEY_REUSED")
            val row = owner.purposeRows[id] ?: return@request failure(ErrorKind.CONFLICT, "PURPOSE_NOT_AVAILABLE")
            return@request ClientResult.Success(PurposeCreated(purposeView(owner, row), owner.purposeRows.size, 30))
        }
        val now = clock.now()
        val recent = owner.purposeCreations.filter { now - it < CREATE_WINDOW }
        if (recent.size >= 10) {
            val wait = (recent.min() + CREATE_WINDOW - now).inWholeNanoseconds
            return@request ClientResult.Failure(ClientError(ErrorKind.RATE_LIMITED, "PURPOSE_CREATE_RATE_LIMITED", retryAfterSeconds = maxOf(1L, (wait + 999_999_999) / 1_000_000_000)))
        }
        if (owner.purposeRows.size >= 30) return@request failure(ErrorKind.CONFLICT, "PURPOSE_LIMIT_REACHED")
        val id = Uuid.parse(ids.newId()).toString()
        check(id !in owner.purposeRows) { "IdGenerator returned an existing purpose ID" }
        val row = PurposeDetail(id, command.name, command.description, command.colorKey, command.iconKey,
            0, 1, 1, PurposeActivity(now, "CREATED"), now, now, emptySet())
        owner.purposeRows[id] = row
        owner.purposeReceipts[key] = command to id
        owner.purposeCreations += now
        ClientResult.Success(PurposeCreated(purposeView(owner, row), owner.purposeRows.size, 30))
    }

    suspend fun updatePurpose(id: String, expectedVersion: Int, patch: PurposePatch): ClientResult<PurposeDetail> = request(ApiId.PUR_04) { owner ->
        val key = canonicalUuidOrNull(id) ?: return@request failure(ErrorKind.VALIDATION, "INVALID_PURPOSE_ID")
        val row = owner.purposeRows[key] ?: return@request purposeNotFound()
        if (expectedVersion <= 0 || patch.isEmpty) return@request failure(ErrorKind.VALIDATION, "INVALID_PURPOSE_INPUT")
        if (row.version != expectedVersion) return@request failure(ErrorKind.CONFLICT, "PURPOSE_VERSION_CONFLICT", currentVersion = row.version)
        val description = when (val change = patch.description) { DescriptionChange.Keep -> row.description; is DescriptionChange.Set -> change.value }
        val command = PurposeCommand(patch.name ?: row.name, description, patch.colorKey ?: row.colorKey, patch.iconKey ?: row.iconKey)
        val invalid = PurposeInputRules.validate(command)
        if (invalid.isNotEmpty()) return@request failure(ErrorKind.VALIDATION, "INVALID_PURPOSE_INPUT", fields = invalid)
        val changed = command != PurposeCommand(row.name, row.description, row.colorKey, row.iconKey)
        val updated = if (!changed) row else row.copy(name = command.name, description = command.description, colorKey = command.colorKey,
            iconKey = command.iconKey, version = row.version + 1, updatedAt = clock.now())
        owner.purposeRows[key] = updated
        ClientResult.Success(purposeView(owner, updated))
    }

    suspend fun itemsByPurpose(purposeId: String, cursor: String?, limit: Int): ClientResult<ItemPage> =
        itemsByPurpose(purposeId, cursor, limit, anchor = null, before = 0, after = 0)

    suspend fun itemsByPurposeAround(purposeId: String, anchor: String, before: Int, after: Int): ClientResult<ItemPage> =
        itemsByPurpose(purposeId, cursor = null, limit = null, anchor, before, after)

    private suspend fun itemsByPurpose(purposeId: String, cursor: String?, limit: Int?, anchor: String?, before: Int, after: Int) =
        request(ApiId.ITEM_02) { owner ->
            if (!validWindowQuery(limit, anchor, before, after)) return@request failure(ErrorKind.VALIDATION, "INVALID_WISHLIST_ITEM_QUERY")
            val key = canonicalUuidOrNull(purposeId) ?: return@request failure(ErrorKind.VALIDATION, "INVALID_WISHLIST_ITEM_QUERY")
            if (key !in owner.purposeRows) return@request purposeNotFound()
            // Tokens are scoped to owner + purpose and preserve sub-millisecond order.
            itemPage(owner, purposeItems(owner, key), "fake-purpose:${session.state.value.accountId}:$key:", cursor, limit, anchor, before, after)
        }

    private fun purposeNotFound() = failure(ErrorKind.NOT_FOUND, "PURPOSE_NOT_FOUND")

    private fun categoryNotFound() = failure(ErrorKind.NOT_FOUND, "CATEGORY_NOT_FOUND")

    private fun next(item: WishlistItem): WishlistItem = withPolicy(item.copy(version = item.version + 1, updatedAt = clock.now()))
    private fun withPolicy(item: WishlistItem): WishlistItem {
        val policy = evaluateItem(item)
        return item.copy(requiredAction = policy.requiredAction, allowedActions = policy.allowedActions)
    }
    private fun <T> Patch<T>.valueOr(original: T): T = when(this) { Patch.Unchanged -> original; is Patch.Set -> value }
    private fun notFound() = failure(ErrorKind.NOT_FOUND)
    private fun invalidItemId() = failure(ErrorKind.VALIDATION, "INVALID_WISHLIST_ITEM_ID")
    private fun missing(id: String) = if (canonicalUuidOrNull(id) == null) invalidItemId() else notFound()
    private fun conflict(item: WishlistItem) = failure(ErrorKind.CONFLICT, currentVersion = item.version)
    private fun failure(kind: ErrorKind, code: String? = null, currentVersion: Int? = null, fields: Set<String> = emptySet()) =
        ClientResult.Failure(ClientError(kind, code, currentVersion = currentVersion, fields = fields))

    private companion object {
        const val CUSTOM_LIMIT = 20
        const val CREATE_PER_WINDOW = 5
        val CREATE_WINDOW = 60.seconds
    }
}

/** A custom category row. [order] is fixed at creation (parent's max + 1, first = its public leaf count). */
internal class FakeCustom(
    val id: String,
    val parentId: String,
    var name: String,
    var description: String?,
    var examples: List<String>,
    var version: Int,
    val order: Int,
    var deleted: Boolean = false,
)

internal data class FakeReceipt(val command: CategoryCommand, val customId: String)

