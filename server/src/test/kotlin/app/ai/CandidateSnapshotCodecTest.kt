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
}
