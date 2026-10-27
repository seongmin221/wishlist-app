@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.wishlist.shared.data.fake

import app.wishlist.shared.core.Clock
import app.wishlist.shared.core.IdGenerator
import app.wishlist.shared.model.ReviewStatus
import app.wishlist.shared.model.ValueSource
import kotlin.test.*
import kotlin.time.Instant
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.test.runTest
import app.wishlist.shared.core.MutableAuthSession
import app.wishlist.shared.model.LifecycleStatus
import kotlin.uuid.Uuid

class BoardSeedsTest {
    /** FCategoryHome chip numbers (design/canvas-fresh/gen_category.py `home_data`), by name. */
    private val boardChips = mapOf(
        "신발" to 5, "아우터" to 3, "가방" to 3, "상의" to 2, "패션 소품" to 2, "스킨케어" to 2, "향수" to 2,
        "헤드폰" to 8, "키보드" to 3, "카메라·액션캠" to 3, "모니터" to 2, "마우스·트랙패드" to 2, "웨어러블 기기" to 2,
        "이어폰" to 1, "태블릿" to 1, "오디오 케이블·DAC" to 2, "조명" to 3, "의자" to 2, "책상·테이블" to 2,
        "주방 가전" to 3, "공기·온습도 관리" to 2, "조리 도구" to 1, "캠핑 용품" to 4, "러닝 용품" to 2,
        "여행 가방·캐리어" to 2, "백패킹 소품" to 1, "피규어·컬렉터블" to 2, "보드게임·퍼즐" to 1, "레고" to 0, "수면·회복 용품" to 1,
    )

    private val now = Instant.parse("2026-10-07T00:00:00Z")
    private fun seed(): BoardSeedData {
        var id = 0
        return BoardSeeds.create(Clock { now }, IdGenerator {
            "00000000-0000-4000-8000-${(++id).toString().padStart(12, '0')}"
        })
    }

    @Test fun board_parents_are_eight_browse_groups_not_the_whole_public_taxonomy() {
        val data = seed()
        assertEquals(listOf("G001", "G002", "G003", "G004", "G005", "G006", "G007", "G011"),
            data.categories.filter { it.parentId == null }.map { it.id })
        assertEquals(38, data.categories.size)
        assertEquals(boardChips.keys, data.categories.filter { it.parentId != null }.map { it.name }.toSet())
    }

    @Test fun public_leaf_ids_and_custom_owner_leaves_match_the_board() {
        val data = seed()
        val headphones = data.categories.single { it.name == "헤드폰" }
        assertEquals("C026", headphones.id)
        assertEquals("G003", headphones.parentId)
        assertEquals("PUBLIC", headphones.kind)
        assertNull(headphones.version)
        val customs = data.categories.filter { it.kind == "CUSTOM" }
        assertEquals(mapOf("오디오 케이블·DAC" to "G003", "백패킹 소품" to "G006", "레고" to "G007"),
            customs.associate { it.name to it.parentId })
        customs.forEach { assertEquals(1, it.version); Uuid.parse(it.id) }
    }

    @Test fun all_category_purpose_and_card_references_resolve_without_duplicate_ids() {
        val data = seed()
        val categoryIds = data.categories.map { it.id }.toSet()
        val purposeIds = data.purposes.map { it.id }.toSet()
        val itemIds = data.items.map { it.id }.toSet()
        assertEquals(data.categories.size, categoryIds.size)
        assertEquals(data.purposes.size, purposeIds.size)
        assertEquals(data.items.size, itemIds.size)
        data.categories.forEach { if (it.parentId != null) assertTrue(it.parentId in categoryIds) }
        data.items.forEach {
            Uuid.parse(it.id); Uuid.parse(it.clientSubmissionId)
            assertTrue(it.category.id in categoryIds)
            if (it.purpose.id != null) assertTrue(it.purpose.id in purposeIds)
        }
        data.purposes.forEach { Uuid.parse(it.id) }
        data.displayMetadata.cards.forEach { assertTrue(it.itemId in itemIds) }
        assertEquals(purposeIds, data.displayMetadata.purposeCandidateCounts.keys)
    }

    @Test fun purpose_board_counts_are_actual_membership_and_carrier_stays_empty() {
        val data = seed()
        assertEquals(7, data.purposes.size)
        assertEquals(0, data.items.count { it.purpose.id == data.carrierId })
        assertEquals(5, data.items.count { it.purpose.id == data.commuteId })
        assertEquals(listOf(5, 3, 2, 4, 3, 2, 0), data.purposes.map { purpose -> data.items.count { it.purpose.id == purpose.id && it.lifecycleStatus == LifecycleStatus.ACTIVE } })
        assertEquals(5, data.displayMetadata.purposeCandidateCounts[data.commuteId])
        assertEquals(listOf(5, 3, 2, 4, 3, 2, 0), data.purposes.map { data.displayMetadata.purposeCandidateCounts[it.id] })
        assertEquals(69, data.items.size)
    }

    @Test fun seeded_purpose_summary_matches_card_order_real_counts_and_relative_activity() = runTest {
        val data = seed()
        val session = MutableAuthSession(); session.changeAccount("A")
        val store = FakeStore(session, Clock { now }, IdGenerator { Uuid.random().toString() })
        store.seed(data).successValue()
        val repo = FakePurposeRepository(store)
        val catalog = repo.summary().successValue()
        assertEquals(data.purposes.map { it.id }, catalog.purposes.map { it.id })
        assertEquals(listOf(5, 3, 2, 4, 3, 2, 0), catalog.purposes.map { it.candidateCount })
        assertEquals(listOf(1L, 3L, 7L, 14L, 21L, 30L, 30L), catalog.purposes.map { (now - it.activity.at).inWholeDays })
        assertEquals(List(6) { "CANDIDATE_ADDED" } + "CREATED", catalog.purposes.map { it.activity.kind })
        catalog.purposes.forEach { purpose ->
            val items = FakeItemListRepository(store).byPurpose(purpose.id).successValue()
            assertEquals(purpose.candidateCount, items.totalCount)
            assertEquals(items.items.take(4).map { it.id }, purpose.previews.map { it.itemId })
            assertEquals(purpose.activity, repo.detail(purpose.id).successValue().activity)
        }
        val before = catalog.purposes
        store.seed(data).successValue()
        assertEquals(before, repo.summary().successValue().purposes)
    }

    @Test fun seeded_home_matches_the_fhome_board_without_touching_the_board_counts() = runTest {
        val data = seed()
        // The home-only items carry no category or purpose and are not list-eligible.
        assertEquals(4, data.homeItems.size)
        assertTrue(data.homeItems.all { it.category.id == null && it.purpose.id == null })
        val session = MutableAuthSession(); session.changeAccount("A")
        val store = FakeStore(session, Clock { now }, IdGenerator { Uuid.random().toString() })
        store.seed(data).successValue()
        val home = FakeHomeRepository(store)
        val summary = home.summary().successValue()
        assertEquals(listOf(1, 3, 3), summary.groups.map { it.count })
        assertEquals(listOf("마샬 MAJOR V", "호카 스피드고트 6", "발뮤다 더 토스터"), summary.groups[2].previews.map { it.item.product.name })
        assertEquals(listOf(null, "오크 원목 사이드 테이블", "FiiO BTR7"), summary.groups[1].previews.map { it.item.product.name })
        assertEquals(listOf("29cm.co.kr"), summary.groups[0].previews.map { it.item.sourceUrl.substringAfter("://").substringBefore('/') })
        assertEquals(69, store.items(null, null).successValue().size)
        // The board's analyzing card stays: the debug driver never completes a seeded one.
        assertEquals(0, DebugAnalysisDriver(store, Clock { now + 1.days }).advance().successValue())
        assertEquals(1, home.summary().successValue().groups[0].count)
    }

    @Test fun every_chip_number_is_real_membership() {
        val data = seed()
        val names = data.categories.associate { it.id to it.name }
        val counts = data.items.groupingBy { names.getValue(it.category.id!!) }.eachCount()
        assertEquals(boardChips.filterValues { it > 0 }, counts)
        assertEquals(69, boardChips.values.sum())
        val dac = data.items.filter { names[it.category.id] == "오디오 케이블·DAC" }
        assertEquals(listOf("FiiO BTR7" to "259000", "오디오퀘스트 DragonFly Red" to "299000"), dac.map { it.product.name to it.product.price!!.canonical })
    }

    @Test fun headphones_stay_the_newest_items() {
        val data = seed()
        val newestEight = data.items.sortedByDescending { it.createdAt }.take(8)
        assertTrue(newestEight.all { it.category.id == "C026" })
    }

    @Test fun category_list_is_canonical_for_marshall_without_a_second_candidate_item() {
        val data = seed()
        val item = data.items.single { it.product.name == "마샬 MAJOR V" }
        assertNull(item.purpose.id)
        assertEquals(ValueSource.UNASSIGNED, item.purpose.source)
        assertEquals(ReviewStatus.PENDING, item.reviewStatus)
        val cards = data.displayMetadata.cards.filter { it.itemId == item.id }
        assertEquals(listOf("l5", "c5"), cards.map { it.cardKey })
        assertEquals(listOf("목적 미지정 · 1주 전 확인", "29CM · 1주 전 확인"), cards.map { it.meta })
    }

    @Test fun airpods_card_photos_are_display_metadata_for_one_domain_item() {
        val data = seed()
        val item = data.items.single { it.product.name == "애플 AirPods Max" }
        assertNull(item.product.imageUrl)
        val cards = data.displayMetadata.cards.filter { it.itemId == item.id }
        assertEquals(listOf("l4", "c3"), cards.map { it.cardKey })
        assertEquals(listOf("#FFFFFF", "#E9E8E4"), cards.map { it.faceHex })
        assertEquals(listOf(1.0, 4.0 / 3.0), cards.map { it.heightToWidthRatio })
    }

    @Test fun prices_preserve_board_decimal_inputs_without_float_conversion() {
        assertEquals(listOf("549000", "499000", "389000", "769000", "229000", "1190000", "279000", "219000"),
            seed().items.take(8).map { it.product.price!!.canonical })
    }

    @Test fun same_clock_and_id_sequence_are_deterministic_and_time_is_injected() {
        val a = seed()
        assertEquals(a, seed())
        a.purposes.forEach { assertTrue(it.createdAt <= now - 30.days); assertEquals(it.createdAt, it.updatedAt) }
        a.items.forEach { assertTrue(it.createdAt <= now); assertEquals(it.createdAt, it.updatedAt) }
    }

    @Test fun item_creation_times_are_staggered_in_board_order_l1_to_l8() {
        val data = seed()
        val boardOrder = data.displayMetadata.cards.filter { it.cardKey.startsWith("l") }.map { it.itemId }
        assertEquals(listOf("l1", "l2", "l3", "l4", "l5", "l6", "l7", "l8"),
            data.displayMetadata.cards.filter { it.cardKey.startsWith("l") }.map { it.cardKey })
        assertEquals(boardOrder, data.items.take(8).map { it.id })
        assertEquals(now, data.items.first().createdAt)
        data.items.zipWithNext().forEach { (newer, older) -> assertTrue(newer.createdAt > older.createdAt) }
    }
}
