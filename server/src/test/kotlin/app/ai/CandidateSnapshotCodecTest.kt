package app.ai

import java.util.UUID
import kotlin.test.*

class CandidateSnapshotCodecTest {
    @Test fun `versioned snapshot retains labels and structured custom data across reuse`() {
        val id = UUID.randomUUID().toString()
        val snapshot = CandidateSnapshot(
            setOf("C026", id), setOf("purpose"), mapOf("C026" to "헤드폰", id to "desk"),
            mapOf("purpose" to "gift"), UUID.randomUUID().toString(),
            mapOf(id to CustomCategoryCandidate(3, "desk],;", "G003", "a\nb", listOf("keyboard"))),
        )
        assertEquals(snapshot, CandidateSnapshotCodec.decode(CandidateSnapshotCodec.encode(snapshot)))
    }

    @Test fun `legacy public snapshot remains readable and malformed typed fields are rejected`() {
        assertEquals(1, CandidateSnapshotCodec.decode("""{"categories":["C026"],"purposes":[]}""")?.schemaVersion)
        for (raw in listOf(
            "[]", "{}", """{"categories":[26],"purposes":[]}""",
            """{"categories":["C026"],"purposes":{},"schema_version":2}""",
            """{"categories":["C026"],"purposes":[],"schema_version":"2"}""",
        )) assertNull(CandidateSnapshotCodec.decode(raw), raw)
    }
    @Test fun `schema v3 keeps activity order and structured purpose evidence`() {
        val ids = listOf("ffffffff-ffff-4fff-8fff-ffffffffffff", "00000000-0000-4000-8000-000000000001")
        val purposes = ids.mapIndexed { i, id -> PurposeCandidate(id, "목적 $i ],;:", if (i == 0) "설명" else null, listOf("상품 $i")) }
        val snapshot = CandidateSnapshot(setOf("C026"), ids.toCollection(LinkedHashSet()), ownerId = UUID.randomUUID().toString(),
            schemaVersion = 3, purposeCandidates = purposes)
        val decoded = assertNotNull(CandidateSnapshotCodec.decode(CandidateSnapshotCodec.encode(snapshot)))
        assertEquals(purposes, decoded.purposeCandidates)
        assertEquals(ids, decoded.purposeIds.toList())
    }

    @Test fun `schema v3 rejects duplicate non canonical oversized and label based purposes`() {
        val owner = UUID.randomUUID()
        fun raw(purposes: String, extra: String = "") =
            """{"schema_version":3,"owner_id":"$owner","custom_categories":{},"categories":["C026"],"purposes":$purposes$extra}"""
        val id = UUID.randomUUID().toString()
        val row = """{"id":"$id","name":"n","description":null,"item_names":[]}"""
        assertNotNull(CandidateSnapshotCodec.decode(raw("[$row]")))
        for (bad in listOf(raw("[$row,$row]"), raw("""[{"id":"PUR_GIFT","name":"n","description":null,"item_names":[]}]"""),
            raw("[" + List(11) { """{"id":"${UUID.randomUUID()}","name":"n","description":null,"item_names":[]}""" }.joinToString(",") + "]"),
            raw("[$row]", ""","purpose_labels":{}"""), raw("""["$id"]"""))) assertNull(CandidateSnapshotCodec.decode(bad), bad)
    }
}
