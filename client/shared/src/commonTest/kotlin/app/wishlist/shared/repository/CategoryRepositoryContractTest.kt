package app.wishlist.shared.repository

import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.model.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** One backend shared by every owner of a scenario; items are placed directly (no item API is under test). */
interface CategoryContractFixture {
    suspend fun repositoriesFor(ownerId: String): CategoryContractRepositories
    /** Puts an item of [ownerId] in [categoryId]; returns its id. Later calls are newer (createdAt order). */
    suspend fun placeItem(ownerId: String, categoryId: String, name: String? = "상품", lifecycle: LifecycleStatus = LifecycleStatus.ACTIVE): String
    /** Moves the backend clock forward. */
    fun advanceSeconds(seconds: Long)
    suspend fun close() {}
}

data class CategoryContractRepositories(val categories: CategoryRepository, val items: ItemListRepository)

internal fun <T> ClientResult<T>.ok(): T = when (this) {
    is ClientResult.Success -> value
    is ClientResult.Failure -> fail("Expected success: $error")
}

internal fun ClientResult<*>.failed(): ClientError = when (this) {
    is ClientResult.Success -> fail("Expected failure: $value")
    is ClientResult.Failure -> error
}

/** B2 CAT-01~04 behaviour, run on Fake and on Remote (MockEngine). This is wire handling, not a server check. */
abstract class CategoryRepositoryContractTest {
    protected abstract suspend fun createFixture(): CategoryContractFixture

    protected val ownerA = "00000000-0000-4000-8000-00000000000a"
    protected val ownerB = "00000000-0000-4000-8000-00000000000b"
    private var keySeq = 0
    protected fun key(): String = "00000000-0000-4000-9000-" + (++keySeq).toString().padStart(12, '0')
    protected fun command(name: String = "책상 장비", parentId: String = "G003", description: String? = null, examples: List<String> = emptyList()) =
        CategoryCommand(parentId, name, description, examples)

    protected suspend fun scenario(block: suspend (CategoryContractFixture) -> Unit) {
        val fixture = createFixture()
        try { block(fixture) } finally { fixture.close() }
    }

    @Test fun selectHasTheWholeTaxonomyAndEveryCustom() = runTest {
        scenario { f ->
            val repo = f.repositoriesFor(ownerA).categories
            val empty = repo.create(command("빈 카테고리"), key()).ok()
            val catalog = repo.catalog(CategoryScope.SELECT).ok()
            assertEquals(CategoryScope.SELECT, catalog.scope)
            assertEquals("v1", catalog.taxonomyVersion)
            assertEquals(11, catalog.groups.size)
            assertEquals((1..11).map { "G" + it.toString().padStart(3, '0') }, catalog.groups.map { it.id })
            val leaves = catalog.groups.flatMap { it.categories }
            assertEquals(87, leaves.count { it.kind == CategoryKind.PUBLIC })
            assertEquals(listOf(empty.category.id), leaves.filter { it.kind == CategoryKind.CUSTOM }.map { it.id })
            assertEquals(1, catalog.customUsedCount)
            assertEquals(20, catalog.customLimit)
        }
    }

    @Test fun browseShowsOnlyVisibleLeavesAndEveryCustom() = runTest {
        scenario { f ->
            val repo = f.repositoriesFor(ownerA).categories
            assertEquals(emptyList(), repo.catalog(CategoryScope.BROWSE).ok().groups)
            f.placeItem(ownerA, "C026")
            f.placeItem(ownerA, "C026")
            f.placeItem(ownerA, "C001", name = "  \t")
            f.placeItem(ownerA, "C002", lifecycle = LifecycleStatus.ARCHIVED)
            f.placeItem(ownerA, "C003", lifecycle = LifecycleStatus.DELETED)
            f.placeItem(ownerB, "C004")
            val lego = repo.create(command("레고", "G007"), key()).ok().category
            val browse = repo.catalog(CategoryScope.BROWSE).ok()
            assertEquals(listOf("G003", "G007"), browse.groups.map { it.id })
            assertEquals(listOf("C026" to 2), browse.groups[0].categories.map { it.id to it.itemCount })
            assertEquals(2, browse.groups[0].itemCount)
            assertEquals(listOf(lego.id to 0), browse.groups[1].categories.map { it.id to it.itemCount })
        }
    }

    @Test fun parentFilterAndUnknownParent() = runTest {
        scenario { f ->
            val repo = f.repositoriesFor(ownerA).categories
            f.placeItem(ownerA, "C026")
            f.placeItem(ownerA, "C001")
            assertEquals(listOf("G003"), repo.catalog(CategoryScope.BROWSE, "G003").ok().groups.map { it.id })
            assertEquals(listOf("G001"), repo.catalog(CategoryScope.SELECT, "G001").ok().groups.map { it.id })
            val error = repo.catalog(CategoryScope.SELECT, "G099").failed()
            assertEquals(ErrorKind.VALIDATION, error.kind)
            assertEquals("INVALID_CATEGORY_PARENT", error.code)
        }
    }

    @Test fun customCountsUseTheSameVisibility() = runTest {
        scenario { f ->
            val repo = f.repositoriesFor(ownerA).categories
            val dac = repo.create(command("오디오 케이블·DAC"), key()).ok().category
            f.placeItem(ownerA, dac.id)
            f.placeItem(ownerA, dac.id, name = null)
            assertEquals(1, repo.custom(dac.id).ok().itemCount)
            assertEquals(1, repo.catalog(CategoryScope.SELECT, "G003").ok().groups.single().categories.single { it.id == dac.id }.itemCount)
        }
    }

    @Test fun customOrderStartsAfterThePublicLeavesOfItsParent() = runTest {
        scenario { f ->
            val repo = f.repositoriesFor(ownerA).categories
            val first = repo.create(command("첫째"), key()).ok().category
            val second = repo.create(command("둘째"), key()).ok().category
            val group = repo.catalog(CategoryScope.SELECT, "G003").ok().groups.single()
            val publicCount = group.categories.count { it.kind == CategoryKind.PUBLIC }
            val customs = group.categories.filter { it.kind == CategoryKind.CUSTOM }
            assertEquals(listOf(first.id, second.id), customs.map { it.id })
            assertEquals(listOf(publicCount, publicCount + 1), customs.map { it.displayOrder })
            assertEquals(group.categories.map { it.id }.takeLast(2), customs.map { it.id })
        }
    }

    @Test fun createAndReplay() = runTest {
        scenario { f ->
            val repo = f.repositoriesFor(ownerA).categories
            val k = key()
            val cmd = command(description = "업무 공간\n주변 장비", examples = listOf("키보드", "헤드폰"))
            val first = repo.create(cmd, k).ok()
            assertEquals(CustomCategoryDetail(first.category.id, "책상 장비", "G003", "업무 공간\n주변 장비", listOf("키보드", "헤드폰"), 0, 1), first.category)
            assertEquals(1, first.customUsedCount)
            val replay = repo.create(cmd, k).ok()
            assertEquals(first.category.id, replay.category.id)
            assertEquals(1, replay.customUsedCount)
            val reused = repo.create(cmd.copy(name = "책상 장비 "), k).failed()
            assertEquals(ErrorKind.CONFLICT, reused.kind)
            assertEquals("IDEMPOTENCY_KEY_REUSED", reused.code)
        }
    }

    @Test fun replayComesBeforeLimitAndRateLimit() = runTest {
        scenario { f ->
            val repo = f.repositoriesFor(ownerA).categories
            val k = key()
            val first = repo.create(command("0"), k).ok()
            repeat(4) { repo.create(command("r$it"), key()).ok() }
            // Rate limited for new keys, but the replay still answers.
            assertEquals("CATEGORY_CREATE_RATE_LIMITED", repo.create(command("x"), key()).failed().code)
            assertEquals(first.category.id, repo.create(command("0"), k).ok().category.id)
        }
    }

    @Test fun duplicateNamesPerParent() = runTest {
        scenario { f ->
            val repo = f.repositoriesFor(ownerA).categories
            repo.create(command("Desk  Gear"), key()).ok()
            val duplicate = repo.create(command("  desk gear "), key()).failed()
            assertEquals(ErrorKind.CONFLICT, duplicate.kind)
            assertEquals("CATEGORY_NAME_DUPLICATE", duplicate.code)
            assertEquals(setOf("name"), duplicate.fields)
            repo.create(command("desk gear", parentId = "G004"), key()).ok()
            // Other owners are independent.
            f.repositoriesFor(ownerB).categories.create(command("Desk Gear"), key()).ok()
        }
    }

    @Test fun limitOfTwenty() = runTest {
        scenario { f ->
            val repo = f.repositoriesFor(ownerA).categories
            repeat(20) {
                repo.create(command("c$it"), key()).ok()
                if (it % 5 == 4) f.advanceSeconds(61)
            }
            val error = repo.create(command("c20"), key()).failed()
            assertEquals(ErrorKind.CONFLICT, error.kind)
            assertEquals("CATEGORY_LIMIT_REACHED", error.code)
            assertEquals(20, repo.catalog(CategoryScope.SELECT).ok().customUsedCount)
        }
    }

    @Test fun rateLimitOfFivePerSixtySeconds() = runTest {
        scenario { f ->
            val repo = f.repositoriesFor(ownerA).categories
            repeat(5) { repo.create(command("c$it"), key()).ok() }
            f.advanceSeconds(23)
            val error = repo.create(command("c5"), key()).failed()
            assertEquals(ErrorKind.RATE_LIMITED, error.kind)
            assertEquals("CATEGORY_CREATE_RATE_LIMITED", error.code)
            assertEquals(37L, error.retryAfterSeconds)
            f.advanceSeconds(37)
            repo.create(command("c5"), key()).ok()
        }
    }

    @Test fun invalidInputAndParent() = runTest {
        scenario { f ->
            val repo = f.repositoriesFor(ownerA).categories
            val input = repo.create(command(name = "", description = "a".repeat(201), examples = listOf(" ")), key()).failed()
            assertEquals(ErrorKind.VALIDATION, input.kind)
            assertEquals("INVALID_CATEGORY_INPUT", input.code)
            assertEquals(setOf("name", "description", "examples"), input.fields)
            val parent = repo.create(command(parentId = "C026"), key()).failed()
            assertEquals("INVALID_CATEGORY_PARENT", parent.code)
            assertEquals(setOf("parentId"), parent.fields)
        }
    }

    @Test fun detailNotFound() = runTest {
        scenario { f ->
            val repo = f.repositoriesFor(ownerA).categories
            val mine = repo.create(command(), key()).ok().category
            val missing = repo.custom("00000000-0000-4000-8000-0000000000ff").failed()
            assertEquals(ErrorKind.NOT_FOUND, missing.kind)
            assertEquals("CATEGORY_NOT_FOUND", missing.code)
            assertEquals("CATEGORY_NOT_FOUND", f.repositoriesFor(ownerB).categories.custom(mine.id).failed().code)
            assertEquals(mine.id, f.repositoriesFor(ownerA).categories.custom(mine.id.uppercase()).ok().id)
        }
    }

    @Test fun patchVersionAndChanges() = runTest {
        scenario { f ->
            val repo = f.repositoriesFor(ownerA).categories
            val created = repo.create(command(description = "설명", examples = listOf("키보드")), key()).ok().category
            val same = repo.update(created.id, 1, CategoryPatch(name = created.name)).ok()
            assertEquals(1, same.version)
            val cleared = repo.update(created.id, 1, CategoryPatch(description = DescriptionChange.Set(null), examples = emptyList())).ok()
            assertEquals(2, cleared.version)
            assertNull(cleared.description)
            assertEquals(emptyList(), cleared.examples)
            val conflict = repo.update(created.id, 1, CategoryPatch(name = "새 이름")).failed()
            assertEquals(ErrorKind.CONFLICT, conflict.kind)
            assertEquals("CATEGORY_VERSION_CONFLICT", conflict.code)
            assertEquals(2, conflict.currentVersion)
            assertEquals("CATEGORY_NOT_FOUND", repo.update("00000000-0000-4000-8000-0000000000ff", 1, CategoryPatch(name = "x")).failed().code)
            val invalid = repo.update(created.id, 2, CategoryPatch(name = "두\n줄")).failed()
            assertEquals(setOf("name"), invalid.fields)
        }
    }

    @Test fun renameReachesTheItems() = runTest {
        scenario { f ->
            val repos = f.repositoriesFor(ownerA)
            val created = repos.categories.create(command(), key()).ok().category
            f.placeItem(ownerA, created.id)
            repos.categories.update(created.id, 1, CategoryPatch(name = "작업 공간")).ok()
            val item = repos.items.byCategory(created.id).ok().items.single()
            assertEquals("작업 공간", item.category.name)
            assertEquals(CategoryKind.CUSTOM.name, item.category.kind)
        }
    }

    @Test fun patchDuplicateName() = runTest {
        scenario { f ->
            val repo = f.repositoriesFor(ownerA).categories
            repo.create(command("하나"), key()).ok()
            val two = repo.create(command("둘"), key()).ok().category
            assertEquals("CATEGORY_NAME_DUPLICATE", repo.update(two.id, 1, CategoryPatch(name = "하나")).failed().code)
        }
    }
}

/** B4 ITEM-02 category scope. */
abstract class ItemListRepositoryContractTest {
    protected abstract suspend fun createFixture(): CategoryContractFixture

    private val ownerA = "00000000-0000-4000-8000-00000000000a"

    private suspend fun scenario(block: suspend (CategoryContractFixture) -> Unit) {
        val fixture = createFixture()
        try { block(fixture) } finally { fixture.close() }
    }

    @Test fun newestFirstWithCursorPages() = runTest {
        scenario { f ->
            val repo = f.repositoriesFor(ownerA).items
            val ids = (1..45).map { f.placeItem(ownerA, "C026", name = "상품 $it") }
            f.placeItem(ownerA, "C026", name = " ")
            f.placeItem(ownerA, "C024")
            val first = repo.byCategory("C026").ok()
            assertEquals(45, first.totalCount)
            assertEquals(ids.reversed().take(40), first.items.map { it.id })
            val cursor = assertNotNull(first.nextCursor)
            val second = repo.byCategory("C026", cursor).ok()
            assertEquals(ids.reversed().drop(40), second.items.map { it.id })
            assertNull(second.nextCursor)
            assertEquals(45, second.totalCount)
        }
    }

    @Test fun limitBoundsAndMissingCustom() = runTest {
        scenario { f ->
            val repo = f.repositoriesFor(ownerA).items
            assertEquals(ErrorKind.VALIDATION, repo.byCategory("C026", limit = 0).failed().kind)
            assertEquals(ErrorKind.VALIDATION, repo.byCategory("C026", limit = 101).failed().kind)
            assertEquals(0, repo.byCategory("C026", limit = 100).ok().totalCount)
            val missing = repo.byCategory("00000000-0000-4000-8000-0000000000ff").failed()
            assertEquals(ErrorKind.NOT_FOUND, missing.kind)
            assertEquals("CATEGORY_NOT_FOUND", missing.code)
        }
    }

    @Test fun emptyCustomListsNothing() = runTest {
        scenario { f ->
            val repos = f.repositoriesFor(ownerA)
            val lego = repos.categories.create(CategoryCommand("G007", "레고", null, emptyList()), "00000000-0000-4000-9000-000000000001").ok().category
            val page = repos.items.byCategory(lego.id).ok()
            assertEquals(ItemPage(emptyList(), 0, null), page)
        }
    }
}
