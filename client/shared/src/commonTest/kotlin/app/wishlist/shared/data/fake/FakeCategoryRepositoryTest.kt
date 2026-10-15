@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package app.wishlist.shared.data.fake

import app.wishlist.shared.core.*
import app.wishlist.shared.model.*
import app.wishlist.shared.repository.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A finished item for the category contracts; a larger [seq] is newer. */
internal fun contractItem(seq: Int, categoryId: String, name: String?, lifecycle: LifecycleStatus): WishlistItem {
    val created = fakeTime + seq.milliseconds
    return WishlistItem(
        id = Uuid.random().toString(), clientSubmissionId = Uuid.random().toString(), version = 1,
        sourceUrl = "https://shop.example/$seq", product = ProductSnapshot(name = name),
        category = ItemCategory(categoryId, ValueSource.USER), purpose = ItemPurpose(),
        analysis = ItemAnalysis(AnalysisStatus.READY), reviewStatus = ReviewStatus.CONFIRMED,
        lifecycleStatus = lifecycle, requiredAction = RequiredAction.NONE, createdAt = created, updatedAt = created,
    )
}

/** A Fake store with a movable clock and owner switching, for the category contracts. */
internal class FakeCategoryFixture : CategoryContractFixture {
    var now: Instant = fakeTime
    private var itemSeq = 0
    val session = MutableAuthSession()
    val store = FakeStore(session, Clock { now }, IdGenerator { Uuid.random().toString() })
    val categories = FakeCategoryRepository(store)
    val items = FakeItemListRepository(store)

    override suspend fun repositoriesFor(ownerId: String): CategoryContractRepositories {
        if (session.state.value.accountId != ownerId) session.changeAccount(ownerId)
        return CategoryContractRepositories(categories, items)
    }

    override suspend fun placeItem(ownerId: String, categoryId: String, name: String?, lifecycle: LifecycleStatus): String {
        val previous = session.state.value.accountId
        if (previous != ownerId) session.changeAccount(ownerId)
        val id = store.putItem(contractItem(++itemSeq, categoryId, name, lifecycle)).successValue().id
        if (previous != null && previous != ownerId) session.changeAccount(previous)
        return id
    }

    override fun advanceSeconds(seconds: Long) { now += seconds.seconds }
}

class FakeCategorySeedTest {
    @kotlin.test.Test fun seededBrowseMatchesTheBoardChips() = kotlinx.coroutines.test.runTest {
        val f = FakeCategoryFixture()
        f.session.changeAccount("A")
        f.store.seed(BoardSeeds.create(Clock { fakeTime }, IdGenerator { Uuid.random().toString() })).successValue()
        val browse = f.categories.catalog(CategoryScope.BROWSE).successValue()
        kotlin.test.assertEquals(listOf("G001", "G002", "G003", "G004", "G005", "G006", "G007", "G011"), browse.groups.map { it.id })
        kotlin.test.assertEquals(69, browse.groups.sumOf { it.itemCount })
        val digital = browse.groups.single { it.id == "G003" }.categories
        kotlin.test.assertEquals(listOf("헤드폰" to 8, "키보드" to 3, "카메라·액션캠" to 3, "모니터" to 2, "마우스·트랙패드" to 2,
            "웨어러블 기기" to 2, "이어폰" to 1, "태블릿" to 1, "오디오 케이블·DAC" to 2).sortedBy { it.first },
            digital.map { it.name to it.itemCount }.sortedBy { it.first })
        kotlin.test.assertEquals(CategoryKind.CUSTOM, digital.last().kind) // customs after the public leaves
        kotlin.test.assertEquals(0, browse.groups.single { it.id == "G007" }.categories.single { it.name == "레고" }.itemCount)
        kotlin.test.assertEquals(3, browse.customUsedCount)
        val dac = digital.single { it.name == "오디오 케이블·DAC" }
        val detail = f.categories.custom(dac.id).successValue()
        kotlin.test.assertEquals("헤드폰에 연결하는 DAC와 교체용 케이블", detail.description)
        kotlin.test.assertEquals(listOf("포터블 DAC", "이어폰 케이블"), detail.examples)
    }

    @kotlin.test.Test fun longCategoryFixturePagesBy40() = kotlinx.coroutines.test.runTest {
        val f = FakeCategoryFixture()
        f.session.changeAccount("A")
        FakeControls(f.store).seedLongCategory("C024", fakeTime).successValue()
        val first = f.items.byCategory("C024").successValue()
        kotlin.test.assertEquals(120, first.totalCount)
        kotlin.test.assertEquals(40, first.items.size)
        kotlin.test.assertEquals("https://seed.wishlist.invalid/slow/0.jpg", first.items.first().product.imageUrl)
    }
}

class FakeCategoryRepositoryContractTest : CategoryRepositoryContractTest() {
    override suspend fun createFixture(): CategoryContractFixture = FakeCategoryFixture()
}

class FakeItemListRepositoryContractTest : ItemListRepositoryContractTest() {
    override suspend fun createFixture(): CategoryContractFixture = FakeCategoryFixture()
}
