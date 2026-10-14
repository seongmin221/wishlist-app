package app.wishlist.shared.presentation

import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.model.*
import app.wishlist.shared.repository.CategoryRepository
import app.wishlist.shared.repository.ItemListRepository
import kotlinx.coroutines.CompletableDeferred

/** Each call takes the next scripted answer (or the default); a deferred answer waits until the test completes it. */
internal class Script<T>(private val default: () -> ClientResult<T>) {
    private val queue = ArrayDeque<CompletableDeferred<ClientResult<T>>>()
    val calls = mutableListOf<Any?>()
    fun answer(result: ClientResult<T>) { queue.addLast(CompletableDeferred(result)) }
    fun hold(): CompletableDeferred<ClientResult<T>> = CompletableDeferred<ClientResult<T>>().also { queue.addLast(it) }
    suspend fun next(arg: Any?): ClientResult<T> {
        calls += arg
        return queue.removeFirstOrNull()?.await() ?: default()
    }
}

internal fun <T> ok(value: T) = ClientResult.Success(value)
internal fun fail(kind: ErrorKind, code: String? = null, currentVersion: Int? = null, fields: Set<String> = emptySet()) =
    ClientResult.Failure(ClientError(kind, code, currentVersion = currentVersion, fields = fields))

internal fun entry(id: String, name: String, parent: String, count: Int = 1, kind: CategoryKind = CategoryKind.PUBLIC, order: Int = 0) =
    CategoryEntry(id, name, parent, kind, kind.name, order, count)

internal fun group(id: String, name: String, vararg entries: CategoryEntry, order: Int = 0) =
    CategoryGroup(id, name, order, entries.sumOf { it.itemCount }, entries.toList())

internal fun catalog(vararg groups: CategoryGroup, scope: CategoryScope = CategoryScope.BROWSE, used: Int = 0, limit: Int = 20) =
    CategoryCatalog(scope, "v1", groups.toList(), used, limit)

internal val digital = group("G003", "디지털·IT", entry("C026", "헤드폰", "G003", 8), entry("C024", "키보드", "G003", 3))
internal val fashion = group("G001", "패션·잡화", entry("C006", "신발", "G001", 5))

internal class ScriptedCategories : CategoryRepository {
    val catalog = Script<CategoryCatalog> { ok(catalog(fashion, digital)) }
    val custom = Script<CustomCategoryDetail> { fail(ErrorKind.NOT_FOUND, "CATEGORY_NOT_FOUND") }
    val create = Script<CategoryCreated> { fail(ErrorKind.SERVER) }
    val update = Script<CustomCategoryDetail> { fail(ErrorKind.SERVER) }
    val createKeys = mutableListOf<String>()
    val createCommands = mutableListOf<CategoryCommand>()
    val patches = mutableListOf<Triple<String, Int, CategoryPatch>>()

    override suspend fun catalog(scope: CategoryScope, parentId: String?) = catalog.next(scope to parentId)
    override suspend fun custom(id: String) = custom.next(id)
    override suspend fun create(command: CategoryCommand, idempotencyKey: String): ClientResult<CategoryCreated> {
        createKeys += idempotencyKey
        createCommands += command
        return create.next(command)
    }
    override suspend fun update(id: String, expectedVersion: Int, patch: CategoryPatch): ClientResult<CustomCategoryDetail> {
        patches += Triple(id, expectedVersion, patch)
        return update.next(patch)
    }
}

internal class ScriptedItemList : ItemListRepository {
    val pages = Script<ItemPage> { ok(ItemPage(emptyList(), 0, null)) }
    override suspend fun byCategory(categoryId: String, cursor: String?, limit: Int) = pages.next(categoryId to cursor)
}
