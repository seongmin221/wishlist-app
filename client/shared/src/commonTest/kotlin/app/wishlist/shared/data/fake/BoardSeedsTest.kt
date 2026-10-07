@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.wishlist.shared.data.fake

import app.wishlist.shared.core.Clock
import app.wishlist.shared.core.IdGenerator
import app.wishlist.shared.model.ReviewStatus
import app.wishlist.shared.model.ValueSource
import kotlin.test.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

class BoardSeedsTest {
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
        assertEquals(30, data.displayMetadata.categoryChipCounts.size)
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
        assertEquals(categoryIds.filter { it.startsWith("G") }.size + data.displayMetadata.categoryChipCounts.size, categoryIds.size)
        assertEquals(purposeIds, data.displayMetadata.purposeCandidateCounts.keys)
    }

    @Test fun board_counts_are_separate_from_membership_and_carrier_stays_empty() {
        val data = seed()
        assertEquals(7, data.purposes.size)
        assertEquals(0, data.items.count { it.purpose.id == data.carrierId })
        assertEquals(4, data.items.count { it.purpose.id == data.commuteId })
        assertEquals(5, data.displayMetadata.purposeCandidateCounts[data.commuteId])
        assertEquals(listOf(5, 3, 2, 4, 3, 2, 0), data.purposes.map { data.displayMetadata.purposeCandidateCounts[it.id] })
        assertEquals(69, data.displayMetadata.categoryChipCounts.values.sum())
        assertEquals(8, data.items.size)
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
            seed().items.map { it.product.price!!.canonical })
    }

    @Test fun same_clock_and_id_sequence_are_deterministic_and_time_is_injected() {
        val a = seed()
        assertEquals(a, seed())
        a.purposes.forEach { assertEquals(now, it.createdAt); assertEquals(now, it.updatedAt) }
        a.items.forEach { assertTrue(it.createdAt <= now); assertEquals(it.createdAt, it.updatedAt) }
    }

    @Test fun item_creation_times_are_staggered_in_board_order_l1_to_l8() {
        val data = seed()
        val boardOrder = data.displayMetadata.cards.filter { it.cardKey.startsWith("l") }.map { it.itemId }
        assertEquals(listOf("l1", "l2", "l3", "l4", "l5", "l6", "l7", "l8"),
            data.displayMetadata.cards.filter { it.cardKey.startsWith("l") }.map { it.cardKey })
        assertEquals(boardOrder, data.items.map { it.id })
        assertEquals(now, data.items.first().createdAt)
        data.items.zipWithNext().forEach { (newer, older) -> assertTrue(newer.createdAt > older.createdAt) }
    }
}
