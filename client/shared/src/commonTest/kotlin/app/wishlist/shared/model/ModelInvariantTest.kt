package app.wishlist.shared.model

import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ModelInvariantTest {
    @Test
    fun item_versions_are_positive() {
        for (version in listOf(0, -1)) assertFailsWith<IllegalArgumentException> { itemFixture(version = version) }
        assertEquals(1, itemFixture().version)
    }

    @Test
    fun category_reference_excludes_missing_reason() {
        assertFailsWith<IllegalArgumentException> {
            ItemCategory(id = "C026", missingReason = CategoryMissingReason.CUSTOM_CATEGORY_DELETED)
        }
        assertNull(ItemCategory(id = "C026").missingReason)
        assertEquals(CategoryMissingReason.AI_ABSTAINED, ItemCategory(missingReason = CategoryMissingReason.AI_ABSTAINED).missingReason)
    }

    @Test
    fun b2_catalog_supports_groups_public_and_custom_leaves_with_raw_ids() {
        val group = Category("G003", "전자기기", parentId = null, kind = null)
        val publicLeaf = Category("C026", "헤드폰", parentId = "G003", kind = "PUBLIC")
        val custom = Category("9c532ef0-f3bc-4ea4-9ef4-a4542b087910", "출퇴근 기기", "G003", "CUSTOM", 1)
        assertNull(group.kind)
        assertNull(group.version)
        assertEquals("G003", publicLeaf.parentId)
        assertNull(publicLeaf.version)
        assertEquals(1, custom.version)
        assertEquals("FUTURE_KIND", Category("future-id", "새 분류", null, "FUTURE_KIND").kind)
    }

    @Test
    fun custom_category_requires_positive_version() {
        for (version in listOf(null, 0, -1)) assertFailsWith<IllegalArgumentException> {
            Category("custom-raw-id", "기기", "G003", "CUSTOM", version)
        }
    }

    @Test
    fun user_can_explicitly_unassign_purpose_and_future_keys_are_preserved() {
        assertEquals(ItemPurpose(id = null, source = ValueSource.USER), itemFixture().copy(purpose = ItemPurpose(null, ValueSource.USER)).purpose)
        val purpose = Purpose("purpose-raw-id", "출퇴근", null, "future-color", "future-icon", 1, fixtureTime, fixtureTime)
        assertEquals("future-color", purpose.colorKey)
        assertEquals("future-icon", purpose.iconKey)
        assertEquals(setOf("coral", "mustard", "periwinkle", "cyan", "mint", "pink"), PurposeKeys.colorKeys)
        assertEquals(setOf("heart", "home", "plane", "gift", "tent", "music", "star", "book"), PurposeKeys.iconKeys)
    }

    @Test
    fun server_product_category_analysis_and_timestamps_are_lossless_snapshots() {
        val product = ProductSnapshot("상품", "https://img.example/p", DecimalAmount.parseOrNull("12345678901234567890.12"), "USD", "브랜드", "판매처", fixtureTime, ValueSource.AI, ValueSource.USER)
        val category = ItemCategory("C026", ValueSource.AI, null, "헤드폰", "G003", "PUBLIC")
        val item = itemFixture().copy(product = product, category = category, analysis = ItemAnalysis(AnalysisStatus.PARTIAL, "FUTURE_FAILURE"), clientCreatedAt = fixtureTime)
        assertEquals("12345678901234567890.12", item.product.price?.canonical)
        assertEquals("헤드폰", item.category.name)
        assertEquals("G003", item.category.parentId)
        assertEquals("PUBLIC", item.category.kind)
        assertEquals("FUTURE_FAILURE", item.analysis.failureCode)
        assertEquals(fixtureTime, item.product.metadataCheckedAt)
        assertEquals(fixtureTime, item.clientCreatedAt)
        assertNull(ItemCategory().name)
        assertNull(ItemCategory().parentId)
        assertNull(ItemCategory().kind)
    }

    @Test
    fun local_submission_keeps_binding_retry_identity_and_public_error() {
        val error = ClientError(ErrorKind.NETWORK)
        val local = LocalSubmission("same-submission-key", "https://shop.example/item", fixtureTime, "account-A", SubmissionStatus.PENDING, null, error)
        val retry = local.copy(submissionStatus = SubmissionStatus.SUBMITTING)
        assertEquals("same-submission-key", retry.clientSubmissionId)
        assertEquals("account-A", retry.accountBinding)
        assertEquals(error, retry.lastSubmissionError)
        assertNull(LocalSubmission("new-key", local.sourceUrl, fixtureTime).accountBinding)
    }

    @Test
    fun kotlin_instants_have_iso_string_views_for_swift_consumers() {
        val item = itemFixture(completed = fixtureTime).copy(clientCreatedAt = fixtureTime)
        assertEquals("2026-10-07T00:00:00Z", item.createdAtIso)
        assertEquals("2026-10-07T00:00:00Z", item.updatedAtIso)
        assertEquals("2026-10-07T00:00:00Z", item.manualCompletionAtIso)
        assertEquals("2026-10-07T00:00:00Z", item.clientCreatedAtIso)
        assertNull(itemFixture().manualCompletionAtIso)
        assertNull(itemFixture().clientCreatedAtIso)
        assertEquals("2026-10-07T00:00:00Z", ProductSnapshot(metadataCheckedAt = fixtureTime).metadataCheckedAtIso)
        assertNull(ProductSnapshot().metadataCheckedAtIso)
        val purpose = Purpose("purpose", "목적", null, "coral", "heart", 1, fixtureTime, fixtureTime)
        assertEquals("2026-10-07T00:00:00Z", purpose.createdAtIso)
        assertEquals("2026-10-07T00:00:00Z", purpose.updatedAtIso)
        assertEquals("2026-10-07T00:00:00Z", LocalSubmission("submission", "url", fixtureTime).createdAtIso)
        assertEquals("2026-10-07T00:00:00Z", Archive("archive", "제목", "purpose", ArchivePurposeSnapshot("목적", null, "coral", "heart"), fixtureTime).createdAtIso)
    }

    @Test
    fun decimal_parser_normalizes_without_losing_precision_or_sign() {
        val examples = listOf(
            "00019.9900" to "19.99", "+0001.020" to "1.02", "000" to "0", "-000.000" to "0",
            "-019.9950" to "-19.995", "0.000000000000000000001" to "0.000000000000000000001",
            "123456789012345678901234567890.12345678901234567890" to "123456789012345678901234567890.1234567890123456789",
        )
        for ((raw, expected) in examples) assertEquals(expected, DecimalAmount.parseOrNull(raw)?.canonical, raw)
        assertEquals(DecimalAmount.parseOrNull("1.00"), DecimalAmount.parseOrNull("+01"))
    }

    @Test
    fun malformed_and_nonfinite_decimal_returns_null_without_throwing() {
        for (raw in listOf("", " ", "NaN", "Infinity", "-Infinity", "1e3", "1E-3", "1,000", "--1", "+", "-", "1..2", ".5", "1.", " 1", "1 ", "１２.５")) {
            assertNull(DecimalAmount.parseOrNull(raw), raw)
        }
    }
}
